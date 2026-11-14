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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement versioning seams: creation with source and acceptance
 * criteria, append-only versions, and project-scoped authorization.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequirementApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "zhao.liu";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
        assertThat(result.status()).as("login %s -> %s", username, result.body()).isEqualTo(200);
        return client;
    }

    private long createProject(BrowserClient owner, String name) {
        HttpResult created = post(owner, "/api/v1/projects", Map.of("name", name));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asLong();
    }

    @Test
    @Order(0)
    void setupUsersAndProjects() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult init = post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(init.status()).as("initialize").isEqualTo(201);
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult created = post(bootstrap, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD));
        assertThat(created.status()).isEqualTo(201);
        createProject(bootstrap, "req-project-a");
        createProject(bootstrap, "req-project-b");
    }

    @Test
    @Order(1)
    void memberCreatesRequirementWithSourceAndCriteria() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "req-project-a");

        HttpResult created = post(owner, "/api/v1/projects/" + projectId + "/requirements",
                Map.of("title", "登录功能",
                        "body", "用户输入账号密码后进入首页。",
                        "sourceType", "MARKDOWN",
                        "acceptanceCriteria", "- 正确凭据登录成功\n- 错误凭据提示错误"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("currentVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("sourceType").asText()).isEqualTo("MARKDOWN");

        HttpResult invalid = post(owner, "/api/v1/projects/" + projectId + "/requirements",
                Map.of("title", "空验收标准", "body", "内容", "sourceType", "MARKDOWN",
                        "acceptanceCriteria", ""));
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    @Order(2)
    void nonMemberCannotAccessProjectRequirements() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "req-project-a");

        assertThat(get(member, "/api/v1/projects/" + projectA + "/requirements").status()).isEqualTo(404);
        HttpResult denied = post(member, "/api/v1/projects/" + projectA + "/requirements",
                Map.of("title", "越权", "body", "x", "sourceType", "MARKDOWN", "acceptanceCriteria", "- y"));
        assertThat(denied.status()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void updateAppendsNewVersionAndHistoryStaysImmutable() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "req-project-a");
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录功能");

        HttpResult updated = put(owner, "/api/v1/projects/" + projectId + "/requirements/" + requirementId,
                Map.of("body", "用户输入账号密码并勾选记住我后进入首页。"));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("currentVersion").asInt()).isEqualTo(2);
        assertThat(updated.body().get("body").asText()).contains("记住我");

        // Version 1 is untouched.
        HttpResult v1 = get(owner, "/api/v1/projects/" + projectId + "/requirements/" + requirementId + "/versions/1");
        assertThat(v1.status()).isEqualTo(200);
        assertThat(v1.body().get("body").asText()).isEqualTo("用户输入账号密码后进入首页。");
        assertThat(v1.body().get("acceptanceCriteria").asText()).contains("正确凭据登录成功");

        // Version list shows both, in order.
        JsonNode versions = get(owner, "/api/v1/projects/" + projectId + "/requirements/" + requirementId + "/versions").body();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);

        // Database never saw an UPDATE on requirement_version rows.
        Integer mutationCount = jdbc.queryForObject(
                "SELECT count(*) FROM requirement_version WHERE id NOT IN ("
                        + "SELECT min(id) FROM requirement_version GROUP BY requirement_id, version)",
                Integer.class);
        assertThat(mutationCount).isZero();
    }

    @Test
    @Order(4)
    void viewerRoleCanReadButNotWrite() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "req-project-a");
        long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录功能");

        HttpResult added = post(owner, "/api/v1/projects/" + projectId + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "VIEWER"));
        assertThat(added.status()).isEqualTo(201);

        BrowserClient viewer = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        assertThat(get(viewer, "/api/v1/projects/" + projectId + "/requirements").status()).isEqualTo(200);
        assertThat(get(viewer, "/api/v1/projects/" + projectId + "/requirements/" + requirementId).status())
                .isEqualTo(200);

        HttpResult writeDenied = post(viewer, "/api/v1/projects/" + projectId + "/requirements",
                Map.of("title", "只读越权", "body", "x", "sourceType", "MARKDOWN", "acceptanceCriteria", "- y"));
        assertThat(writeDenied.status()).isEqualTo(403);
        HttpResult updateDenied = put(viewer, "/api/v1/projects/" + projectId + "/requirements/" + requirementId,
                Map.of("body", "篡改"));
        assertThat(updateDenied.status()).isEqualTo(403);
    }

    @Test
    @Order(5)
    void requirementAuditTrailExists() {
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action IN ('REQUIREMENT_CREATED','REQUIREMENT_UPDATED')",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(2);
    }
}
