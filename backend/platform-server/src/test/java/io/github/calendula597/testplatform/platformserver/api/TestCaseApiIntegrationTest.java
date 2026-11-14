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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test case grid seams: structured step editing, filtering-friendly row
 * data, batch updates and explicit conflict on concurrent saves.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestCaseApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "sun.qi";
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
    void setupUsersProjectsAndRequirement() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult init = post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(init.status()).isEqualTo(201);
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult created = post(bootstrap, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD));
        assertThat(created.status()).isEqualTo(201);
        createProject(bootstrap, "case-project");

        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "case-project");
        HttpResult requirement = post(bootstrap, "/api/v1/projects/" + projectId + "/requirements",
                Map.of("title", "登录需求", "body", "登录业务说明", "sourceType", "MARKDOWN",
                        "acceptanceCriteria", "- 可登录"));
        assertThat(requirement.status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void createCaseWithOrderedSteps() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "case-project");
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录需求");

        HttpResult created = post(owner, "/api/v1/projects/" + projectId + "/testcases",
                Map.of("title", "正常登录用例", "priority", "HIGH", "requirementId", requirementId,
                        "steps", List.of(
                                Map.of("action", "打开登录页", "testData", "", "expected", "页面正常加载"),
                                Map.of("action", "输入正确凭据", "testData", "admin/正确密码", "expected", "进入首页"))));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.body().get("version").asInt()).isEqualTo(1);
        assertThat(created.body().get("steps")).hasSize(2);
        assertThat(created.body().get("steps").get(0).get("stepOrder").asInt()).isEqualTo(1);
        assertThat(created.body().get("requirementTitle").asText()).isEqualTo("登录需求");

        HttpResult invalid = post(owner, "/api/v1/projects/" + projectId + "/testcases",
                Map.of("title", "", "priority", "HIGH"));
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    @Order(2)
    void gridRowsCarryCoreFieldsAndAreProjectScoped() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "case-project");

        // sun.qi is not a member of case-project yet: list must be 404-scoped.
        assertThat(get(member, "/api/v1/projects/" + projectA + "/testcases").status()).isEqualTo(404);

        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        JsonNode rows = get(owner, "/api/v1/projects/" + projectA + "/testcases").body();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title").asText()).isEqualTo("正常登录用例");
        assertThat(rows.get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(rows.get(0).get("priority").asText()).isEqualTo("HIGH");
    }

    @Test
    @Order(3)
    void concurrentSaveSurfacesExplicitConflictWithoutOverwrite() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "case-project");
        long caseId = jdbc.queryForObject("SELECT id FROM test_case WHERE title = ?", Long.class, "正常登录用例");

        // Save with the CURRENT version -> succeeds and bumps the version.
        HttpResult ok = put(owner, "/api/v1/projects/" + projectId + "/testcases/" + caseId,
                Map.of("title", "正常登录用例（修订）", "expectedVersion", 1,
                        "steps", List.of(Map.of("action", "打开登录页", "expected", "页面正常加载"))));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("version").asInt()).isEqualTo(2);

        // Another writer still holds the stale version -> explicit 409.
        HttpResult stale = put(owner, "/api/v1/projects/" + projectId + "/testcases/" + caseId,
                Map.of("title", "被覆盖的标题", "expectedVersion", 1, "steps", List.of()));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asText()).contains("版本冲突");

        // The stale save must NOT have overwritten anything.
        HttpResult after = get(owner, "/api/v1/projects/" + projectId + "/testcases/" + caseId);
        assertThat(after.body().get("title").asText()).isEqualTo("正常登录用例（修订）");
        assertThat(after.body().get("version").asInt()).isEqualTo(2);

        // Steps survived: the stale save did not wipe them.
        assertThat(after.body().get("steps")).hasSize(1);
        assertThat(after.body().get("steps").get(0).get("action").asText()).isEqualTo("打开登录页");
    }

    @Test
    @Order(4)
    void batchUpdateTouchesSelectedRowsOnly() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long projectId = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "case-project");

        for (int i = 1; i <= 2; i++) {
            HttpResult created = post(owner, "/api/v1/projects/" + projectId + "/testcases",
                    Map.of("title", "批量用例 " + i));
            assertThat(created.status()).isEqualTo(201);
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM test_case WHERE title LIKE '批量用例%' ORDER BY id", Long.class);
        assertThat(ids).hasSize(2);

        HttpResult batch = post(owner, "/api/v1/projects/" + projectId + "/testcases/batch-update",
                Map.of("ids", ids, "fields", Map.of("priority", "LOW")));
        assertThat(batch.status()).isEqualTo(200);
        JsonNode results = batch.body().get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("status").asInt()).isEqualTo(200);

        Integer lows = jdbc.queryForObject(
                "SELECT count(*) FROM test_case WHERE title LIKE '批量用例%' AND priority = 'LOW'", Integer.class);
        assertThat(lows).isEqualTo(2);

        // Batch update also bumps versions and audits each row's change.
        Integer versions = jdbc.queryForObject(
                "SELECT count(*) FROM test_case WHERE title LIKE '批量用例%' AND version = 2", Integer.class);
        assertThat(versions).isEqualTo(2);
    }

    @Test
    @Order(5)
    void requirementAuditTrailIncludesTestCaseActions() {
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action IN "
                        + "('TESTCASE_CREATED','TESTCASE_UPDATED','TESTCASE_BATCH_UPDATED')",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(4);
    }
}
