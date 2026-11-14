package io.github.calendula597.testplatform.platformserver.api;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Environment seams: plain variables, encrypted secret references, target
 * allowlists and versioned masked snapshots. Secrets never appear as
 * plaintext in responses, audit records or storage; the allowlist check is
 * the pre-execution gate and re-validates redirect targets.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnvironmentApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String VIEWER_USERNAME = "member.b";
    private static final String VIEWER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String OUTSIDER_USERNAME = "member.c";
    private static final String OUTSIDER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    private static final String SECRET_V1 = "sk-super-secret-001";
    private static final String SECRET_V2 = "sk-super-secret-002";
    private static final String SECRET_DB = "db-pass-plain-42";

    private static final String MASTER_KEY = newMasterKey();

    private static String newMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.secrets.master-key", () -> MASTER_KEY);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private record BrowserClient(RestClient rest, CookieManager cookies) {
    }

    private BrowserClient clientWithSession() {
        CookieManager cookies = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookies).build();
        RestClient rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
                .build();
        return new BrowserClient(rest, cookies);
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private HttpResult call(BrowserClient client, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = client.rest().method(method).uri(path);
        if (method != HttpMethod.GET) {
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            String csrf = client.cookies().getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            spec.headers(h -> h.add("X-XSRF-TOKEN", csrf));
        }
        return spec.exchange((req, res) -> {
            byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
            String content = new String(raw);
            return new HttpResult(res.getStatusCode().value(),
                    objectMapper.readTree(content.isEmpty() ? "{}" : content));
        });
    }

    private HttpResult post(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.POST, path, body);
    }

    private HttpResult put(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.PUT, path, body);
    }

    private HttpResult get(BrowserClient client, String path) {
        return call(client, HttpMethod.GET, path, null);
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult result = post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    @Test
    @Order(0)
    void setupUsersAndProjects() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        for (String user : List.of(MEMBER_USERNAME, VIEWER_USERNAME, OUTSIDER_USERNAME)) {
            String password = switch (user) {
                case MEMBER_USERNAME -> MEMBER_PASSWORD;
                case VIEWER_USERNAME -> VIEWER_PASSWORD;
                default -> OUTSIDER_PASSWORD;
            };
            assertThat(post(admin, "/api/v1/system/users",
                    Map.of("username", user, "password", password)).status()).isEqualTo(201);
        }
        // Admin owns the project; member.a is MEMBER, member.b is VIEWER,
        // member.c is an outsider with no membership.
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "env-project")).status()).isEqualTo(201);

        long memberA = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        long memberB = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, VIEWER_USERNAME);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                project, memberA);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'VIEWER')",
                project, memberB);
    }

    @Test
    @Order(1)
    void ownerCreatesEnvironmentWithVariablesSecretsAndTargets() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");

        HttpResult created = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "冒烟环境",
                        "plainVariables", Map.of("baseUrl", "https://api.demo.local"),
                        "secrets", Map.of("apiKey", SECRET_V1),
                        "targets", List.of("https://api.demo.local")));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("name").asText()).isEqualTo("冒烟环境");
        assertThat(created.body().get("currentVersion").asInt()).isEqualTo(1);

        // Detail view: plain variables readable, secrets masked.
        HttpResult detail = get(admin, "/api/v1/projects/" + project + "/environments/"
                + created.body().get("id").asLong());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("plainVariables").get("baseUrl").asText())
                .isEqualTo("https://api.demo.local");
        JsonNode secrets = detail.body().get("secrets");
        assertThat(secrets).hasSize(1);
        assertThat(secrets.get(0).get("key").asText()).isEqualTo("apiKey");
        assertThat(secrets.get(0).get("hasValue").asBoolean()).isTrue();
        assertThat(secrets.get(0).get("maskedValue").asText()).doesNotContain(SECRET_V1);
        assertThat(detail.body().get("targets").toString()).contains("https://api.demo.local");

        // The secret never appears as plaintext in any response, and the
        // stored snapshot holds ciphertext, not the raw value.
        assertThat(detail.body().toString()).doesNotContain(SECRET_V1);
        String stored = jdbc.queryForObject(
                "SELECT secrets FROM environment_version ev "
                        + "JOIN environment e ON e.id = ev.environment_id "
                        + "WHERE e.project_id = ? AND ev.version = 1", String.class, project);
        assertThat(stored).doesNotContain(SECRET_V1);
        assertThat(stored).contains("v1:");
    }

    @Test
    @Order(2)
    void memberReadsAndValidatesTargetsButCannotConfigure() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");
        long environmentId = jdbc.queryForObject(
                "SELECT id FROM environment WHERE project_id = ? LIMIT 1", Long.class, project);

        // MEMBER can read and use the pre-execution gate, not configure.
        HttpResult listed = get(member, "/api/v1/projects/" + project + "/environments");
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).hasSize(1);

        HttpResult editDenied = post(member, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "成员环境"));
        assertThat(editDenied.status()).isEqualTo(403);

        HttpResult putDenied = put(member, "/api/v1/projects/" + project + "/environments/" + environmentId,
                Map.of("plainVariables", Map.of("baseUrl", "https://evil.local")));
        assertThat(putDenied.status()).isEqualTo(403);

        // Allowed target passes the pre-execution gate.
        HttpResult allowed = post(member, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/validate-target",
                Map.of("url", "https://api.demo.local/v1/login"));
        assertThat(allowed.status()).isEqualTo(200);
        assertThat(allowed.body().get("allowed").asBoolean()).isTrue();
        assertThat(allowed.body().get("checks").get(0).get("matchedTarget").asText())
                .isEqualTo("https://api.demo.local");

        // Unauthorized target is rejected before execution.
        HttpResult rejected = post(member, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/validate-target",
                Map.of("url", "https://evil.example.com/steal"));
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("allowed").asBoolean()).isFalse();

        // A redirect hop is re-validated: the original URL is allowed but the
        // redirect leaves the allowlist, so the whole check fails.
        HttpResult redirect = post(member, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/validate-target",
                Map.of("url", "https://api.demo.local/v1/login",
                        "redirectUrl", "http://evil.example.com/collect"));
        assertThat(redirect.status()).isEqualTo(200);
        assertThat(redirect.body().get("allowed").asBoolean()).isFalse();

        // Malformed URLs fail validation explicitly.
        HttpResult malformed = post(member, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/validate-target", Map.of("url", "notaurl"));
        assertThat(malformed.status()).isEqualTo(400);

        // VIEWER cannot invoke the execution precondition.
        BrowserClient viewer = login(VIEWER_USERNAME, VIEWER_PASSWORD);
        HttpResult viewerDenied = post(viewer, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/validate-target",
                Map.of("url", "https://api.demo.local/v1/login"));
        assertThat(viewerDenied.status()).isEqualTo(403);
    }

    @Test
    @Order(3)
    void updateAppendsVersionedMaskedSnapshot() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");
        long environmentId = jdbc.queryForObject(
                "SELECT id FROM environment WHERE project_id = ? LIMIT 1", Long.class, project);

        // v2: rotate apiKey, add a second secret, extend targets.
        HttpResult v2 = put(admin, "/api/v1/projects/" + project + "/environments/" + environmentId,
                Map.of("plainVariables", Map.of("baseUrl", "https://api.demo.local",
                        "timeoutSeconds", "30"),
                        "secretWrites", Map.of("apiKey", SECRET_V2, "dbPassword", SECRET_DB),
                        "targets", List.of("https://api.demo.local", "http://127.0.0.1:8080")));
        assertThat(v2.status()).isEqualTo(200);
        assertThat(v2.body().get("currentVersion").asInt()).isEqualTo(2);

        // v3: remove dbPassword again — explicit null removes the key.
        Map<String, String> removal = new java.util.HashMap<>();
        removal.put("dbPassword", null);
        HttpResult v3 = put(admin, "/api/v1/projects/" + project + "/environments/" + environmentId,
                Map.of("plainVariables", Map.of("baseUrl", "https://api.demo.local",
                        "timeoutSeconds", "30"),
                        "secretWrites", removal,
                        "targets", List.of("https://api.demo.local", "http://127.0.0.1:8080")));
        assertThat(v3.status()).isEqualTo(200);
        assertThat(v3.body().get("currentVersion").asInt()).isEqualTo(3);

        JsonNode versions = get(admin, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/versions").body();
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(2).get("version").asInt()).isEqualTo(3);

        // v1 snapshot kept the original secret reference, masked.
        JsonNode v1Secrets = versions.get(0).get("secrets");
        assertThat(v1Secrets.get(0).get("key").asText()).isEqualTo("apiKey");
        assertThat(v1Secrets.get(0).get("hasValue").asBoolean()).isTrue();
        // v2 carried both secrets.
        assertThat(versions.get(1).get("secrets")).hasSize(2);
        // v3 dropped dbPassword.
        JsonNode v3Secrets = versions.get(2).get("secrets");
        assertThat(v3Secrets).hasSize(1);
        assertThat(v3Secrets.get(0).get("key").asText()).isEqualTo("apiKey");

        // No snapshot ever exposes secret material.
        assertThat(versions.toString()).doesNotContain(SECRET_V1, SECRET_V2, SECRET_DB);
    }

    @Test
    @Order(4)
    void secretsNeverReachAuditOrResponses() {
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");
        long environmentId = jdbc.queryForObject(
                "SELECT id FROM environment WHERE project_id = ? LIMIT 1", Long.class, project);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        JsonNode detail = get(admin, "/api/v1/projects/" + project + "/environments/"
                + environmentId).body();
        JsonNode versions = get(admin, "/api/v1/projects/" + project + "/environments/"
                + environmentId + "/versions").body();
        assertThat(detail.toString()).doesNotContain(SECRET_V1, SECRET_V2, SECRET_DB);
        assertThat(versions.toString()).doesNotContain(SECRET_V1, SECRET_V2, SECRET_DB);

        Integer leakedAudit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE detail LIKE ?",
                Integer.class, "%sk-super-secret%");
        assertThat(leakedAudit).isZero();

        // Environment operations are auditable without any secret material.
        Integer envAudits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action LIKE 'ENVIRONMENT_%'",
                Integer.class);
        assertThat(envAudits).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(5)
    void validationAndIsolationRules() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project");

        // Outsider is a non-member: 404, never leaked existence.
        HttpResult denied = get(outsider, "/api/v1/projects/" + project + "/environments");
        assertThat(denied.status()).isEqualTo(404);

        // Blank name.
        HttpResult blankName = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", " "));
        assertThat(blankName.status()).isEqualTo(400);

        // Duplicate name within the same project.
        HttpResult duplicate = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "冒烟环境"));
        assertThat(duplicate.status()).isEqualTo(400);

        // Renaming into an existing name fails with a validation error,
        // not a database constraint violation.
        assertThat(post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "性能环境")).status()).isEqualTo(201);
        long environmentId = jdbc.queryForObject(
                "SELECT id FROM environment WHERE project_id = ? LIMIT 1", Long.class, project);
        HttpResult renameCollision = put(admin,
                "/api/v1/projects/" + project + "/environments/" + environmentId,
                Map.of("name", "性能环境"));
        assertThat(renameCollision.status()).isEqualTo(400);

        // Invalid target pattern.
        HttpResult badTarget = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "坏目标环境", "targets", List.of("ftp://files.demo.local")));
        assertThat(badTarget.status()).isEqualTo(400);

        // Secret key cannot be blank.
        HttpResult blankSecretKey = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "坏密钥环境", "secrets", Map.of(" ", "value")));
        assertThat(blankSecretKey.status()).isEqualTo(400);

        // Same name is fine in another project (scoping, not global).
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "env-project-b")).status()).isEqualTo(201);
        long projectB = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "env-project-b");
        HttpResult otherProject = post(admin, "/api/v1/projects/" + projectB + "/environments",
                Map.of("name", "冒烟环境"));
        assertThat(otherProject.status()).isEqualTo(201);
    }
}
