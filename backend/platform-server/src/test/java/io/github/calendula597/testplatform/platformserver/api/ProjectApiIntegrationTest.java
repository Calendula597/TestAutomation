package io.github.calendula597.testplatform.platformserver.api;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03 acceptance seams: two users, two projects; every project endpoint
 * enforces membership and role server-side, and cross-project access is
 * rejected (404 to non-members, 403 to under-privileged members).
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "wang.wu";
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

    private HttpResult call(BrowserClient client, HttpMethod method, String path, Object body,
                            Map<String, String> extraHeaders) {
        RestClient.RequestBodySpec spec = client.rest().method(method).uri(path);
        if (method != HttpMethod.GET) {
            spec.contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
        }
        return spec.headers(headers -> extraHeaders.forEach(headers::add))
                .exchange((req, res) -> {
                    byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
                    String content = new String(raw);
                    return new HttpResult(res.getStatusCode().value(),
                            objectMapper.readTree(content.isEmpty() ? "{}" : content));
                });
    }

    /** Write request carrying the CSRF cookie value, like the frontend client. */
    private HttpResult call(BrowserClient client, HttpMethod method, String path, Object body) {
        String csrf = client.cookies().getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
        return call(client, method, path, body, Map.of("X-XSRF-TOKEN", csrf));
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

    private HttpResult delete(BrowserClient client, String path) {
        return call(client, HttpMethod.DELETE, path, null);
    }

    /** Logs the account in; returns the client holding a live session. */
    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        HttpResult csrfResult = call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(csrfResult.status()).as("csrf for %s", username).isEqualTo(200);
        HttpResult result = post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status())
                .as("login %s -> %s", username, result.body())
                .isEqualTo(200);
        return client;
    }

    @Test
    @Order(0)
    void setupUsers() {
        // One-time initialization creates the ADMIN (and logs it in).
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult init = post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(init.status()).as("initialize").isEqualTo(201);

        // Bootstrap's CSRF cookie was rotated by the login; fetch a fresh one.
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult created = post(bootstrap, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD, "role", "MEMBER"));
        assertThat(created.status()).as("create second user").isEqualTo(201);
    }

    @Test
    @Order(1)
    void creatorBecomesOwnerAndSeesOwnProjectsOnly() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);

        HttpResult p1 = post(admin, "/api/v1/projects", Map.of("name", "项目一", "description", "admin 的项目"));
        assertThat(p1.status()).isEqualTo(201);
        assertThat(p1.body().get("myRole").asText()).isEqualTo("OWNER");
        long p1Id = p1.body().get("id").asLong();

        HttpResult p2 = post(admin, "/api/v1/projects", Map.of("name", "项目二"));
        assertThat(p2.status()).isEqualTo(201);
        long p2Id = p2.body().get("id").asLong();

        HttpResult p3 = post(member, "/api/v1/projects", Map.of("name", "wang 的项目"));
        assertThat(p3.status()).isEqualTo(201);
        long p3Id = p3.body().get("id").asLong();

        // Lists are scoped to the caller's memberships.
        JsonNode adminList = get(admin, "/api/v1/projects").body();
        assertThat(adminList).hasSize(2);
        JsonNode memberList = get(member, "/api/v1/projects").body();
        assertThat(memberList).hasSize(1);
        assertThat(memberList.get(0).get("id").asLong()).isEqualTo(p3Id);

        // Detail requires membership; the owner can read their own project.
        assertThat(get(admin, "/api/v1/projects/" + p1Id).status()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void nonMemberGets404OnEveryProjectEndpoint() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long p1Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "项目一");
        long p3Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "wang 的项目");

        // member has no membership in 项目一: detail, archive, member management
        // must all behave as if the project does not exist.
        assertThat(get(member, "/api/v1/projects/" + p1Id).status()).isEqualTo(404);
        assertThat(put(member, "/api/v1/projects/" + p1Id + "/archive", Map.of()).status()).isEqualTo(404);
        assertThat(post(member, "/api/v1/projects/" + p1Id + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "VIEWER")).status()).isEqualTo(404);
        assertThat(get(member, "/api/v1/projects/" + p1Id + "/members").status()).isEqualTo(404);

        // The reverse direction too: admin cannot touch wang 的项目.
        assertThat(get(admin, "/api/v1/projects/" + p3Id).status()).isEqualTo(404);
        assertThat(put(admin, "/api/v1/projects/" + p3Id + "/archive", Map.of()).status()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void viewerCanReadButNotModify() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long p1Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "项目一");

        HttpResult added = post(admin, "/api/v1/projects/" + p1Id + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "VIEWER"));
        assertThat(added.status()).isEqualTo(201);

        // Viewer reads detail and member list.
        HttpResult detail = get(member, "/api/v1/projects/" + p1Id);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("myRole").asText()).isEqualTo("VIEWER");
        assertThat(get(member, "/api/v1/projects/" + p1Id + "/members").status()).isEqualTo(200);

        // Viewer cannot archive nor manage members.
        assertThat(put(member, "/api/v1/projects/" + p1Id + "/archive", Map.of()).status()).isEqualTo(403);
        assertThat(post(member, "/api/v1/projects/" + p1Id + "/members",
                Map.of("username", ADMIN_USERNAME, "role", "VIEWER")).status()).isEqualTo(403);
    }

    @Test
    @Order(4)
    void memberCannotPerformOwnerActions() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long p1Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "项目一");
        long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);

        HttpResult promoted = put(admin, "/api/v1/projects/" + p1Id + "/members/" + memberId + "/role",
                Map.of("role", "MEMBER"));
        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.body().get("role").asText()).isEqualTo("MEMBER");

        assertThat(put(member, "/api/v1/projects/" + p1Id + "/archive", Map.of()).status()).isEqualTo(403);
        assertThat(post(member, "/api/v1/projects/" + p1Id + "/members",
                Map.of("username", ADMIN_USERNAME, "role", "VIEWER")).status()).isEqualTo(403);
    }

    @Test
    @Order(5)
    void archiveIsOwnerOnlyAndLocksMutations() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long p1Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "项目一");
        long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);

        HttpResult archived = put(admin, "/api/v1/projects/" + p1Id + "/archive", Map.of());
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.body().get("status").asText()).isEqualTo("ARCHIVED");

        // Members keep read access to the archived project...
        assertThat(get(member, "/api/v1/projects/" + p1Id).status()).isEqualTo(200);
        // ...but mutations are refused.
        assertThat(put(admin, "/api/v1/projects/" + p1Id + "/archive", Map.of()).status()).isEqualTo(409);
        assertThat(post(admin, "/api/v1/projects/" + p1Id + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "VIEWER")).status()).isEqualTo(409);
        assertThat(put(admin, "/api/v1/projects/" + p1Id + "/members/" + memberId + "/role",
                Map.of("role", "OWNER")).status()).isEqualTo(409);
    }

    @Test
    @Order(6)
    void projectCannotLoseItsLastOwner() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long p3Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "wang 的项目");
        long adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, ADMIN_USERNAME);
        long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);

        // wang.wu is the sole OWNER of wang 的项目? No — admin cannot reach it.
        // Instead: 项目二 has admin as sole OWNER. Adding a second owner then
        // demoting them is allowed; touching the LAST owner is refused.
        long p2Id = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "项目二");
        HttpResult added = post(admin, "/api/v1/projects/" + p2Id + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "OWNER"));
        assertThat(added.status()).isEqualTo(201);

        // With two owners, demoting and then removing the other one is fine.
        HttpResult demoteOtherOwner = put(admin, "/api/v1/projects/" + p2Id + "/members/" + memberId + "/role",
                Map.of("role", "VIEWER"));
        assertThat(demoteOtherOwner.status()).isEqualTo(200);
        HttpResult removedOther = delete(admin, "/api/v1/projects/" + p2Id + "/members/" + memberId);
        assertThat(removedOther.status()).isEqualTo(204);

        // Demoting or removing the LAST owner must be refused.
        HttpResult demoteLastOwner = put(admin, "/api/v1/projects/" + p2Id + "/members/" + adminId + "/role",
                Map.of("role", "MEMBER"));
        assertThat(demoteLastOwner.status()).isEqualTo(409);
        assertThat(demoteLastOwner.body().get("message").asText()).contains("至少需要一名项目负责人");

        HttpResult removeLastOwner = delete(admin, "/api/v1/projects/" + p2Id + "/members/" + adminId);
        assertThat(removeLastOwner.status()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void projectAuditTrailCoversLifecycle() {
        List<Map<String, Object>> actions = jdbc.queryForList("SELECT action FROM audit_event ORDER BY id");
        assertThat(actions).extracting(a -> a.get("action"))
                .contains("PROJECT_CREATED", "MEMBER_ADDED", "MEMBER_ROLE_CHANGED",
                        "PROJECT_ARCHIVED", "MEMBER_REMOVED");
    }
}
