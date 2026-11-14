package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.agent.ModelClient;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * Coverage matrix seams: explicit status ladder per requirement (not a case
 * count), traceability to executions, and cross-project invisibility.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoverageApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @TestConfiguration
    static class FakeModelConfig {
        @Bean
        @Primary
        ModelClient fakeModelClient() {
            return new ModelClient("", "", "") {
                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public String model() {
                    return "fake-model";
                }

                @Override
                public Completion complete(String systemPrompt, String userPrompt) {
                    return new Completion(
                            "{\"clarifications\":[{\"question\":\"目标响应时间？\",\"blocking\":true}],"
                                    + "\"coveragePoints\":[]}",
                            10, 10);
                }
            };
        }
    }

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

    private HttpResult get(BrowserClient client, String path) {
        return call(client, HttpMethod.GET, path, null);
    }

    private HttpResult put(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.PUT, path, body);
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult result = post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    private long projectId(String name) {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, name);
    }

    @Test
    @Order(0)
    void setupProjectsRequirementsCasesAndRuns() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "cov-project-a")).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "cov-project-b")).status()).isEqualTo(201);
        long pidA = projectId("cov-project-a");

        // Four requirements covering different matrix statuses.
        for (String title : List.of("未覆盖需求", "已覆盖需求", "执行失败需求", "生成中需求")) {
            assertThat(post(admin, "/api/v1/projects/" + pidA + "/requirements",
                    Map.of("title", title, "body", "正文", "sourceType", "MARKDOWN",
                            "acceptanceCriteria", "- 标准")).status()).isEqualTo(201);
        }

        // Publish a READY version for 已覆盖需求 and 执行失败需求, each linked
        // to the requirement through the case's requirementId.
        for (String title : List.of("已覆盖需求", "执行失败需求")) {
            long requirementId = jdbc.queryForObject("SELECT id FROM requirement WHERE title = ?",
                    Long.class, title);
            assertThat(post(admin, "/api/v1/projects/" + pidA + "/testcases",
                    Map.of("title", "用例-" + title, "requirementId", requirementId,
                            "steps", List.of(Map.of("action", "打开页面", "expected", "加载成功"))))
                    .status()).isEqualTo(201);
            long caseId = jdbc.queryForObject("SELECT id FROM test_case WHERE title = ?",
                    Long.class, "用例-" + title);
            assertThat(post(admin, "/api/v1/projects/" + pidA + "/testcases/" + caseId + "/review/publish",
                    Map.of("basis", "已审核", "expectedVersion", 1)).status()).isEqualTo(201);
        }

        // Plan + run over both published versions; record one PASSED and one FAILED.
        long vCovered = jdbc.queryForObject(
                "SELECT tv.id FROM test_case_version tv JOIN test_case tc ON tc.id = tv.case_id "
                        + "WHERE tc.title = '用例-已覆盖需求'", Long.class);
        long vFailed = jdbc.queryForObject(
                "SELECT tv.id FROM test_case_version tv JOIN test_case tc ON tc.id = tv.case_id "
                        + "WHERE tc.title = '用例-执行失败需求'", Long.class);
        HttpResult plan = post(admin, "/api/v1/projects/" + pidA + "/plans",
                Map.of("name", "覆盖回归", "caseVersionIds", List.of(vCovered, vFailed)));
        assertThat(plan.status()).isEqualTo(201);
        long planId = plan.body().get("id").asLong();
        HttpResult run = post(admin, "/api/v1/projects/" + pidA + "/plans/" + planId + "/runs", Map.of());
        long runId = run.body().get("runId").asLong();

        JsonNode runDetail = get(admin, "/api/v1/projects/" + pidA + "/plans/" + planId + "/runs/" + runId).body();
        for (JsonNode item : runDetail.get("items")) {
            long caseVersionId = jdbc.queryForObject(
                    "SELECT case_version_id FROM test_plan_item WHERE id = ?",
                    Long.class, item.get("planItemId").asLong());
            String status = caseVersionId == vCovered ? "PASSED" : "FAILED";
            assertThat(put(admin, "/api/v1/projects/" + pidA + "/plans/" + planId + "/runs/"
                    + runId + "/items/" + item.get("id").asLong(),
                    Map.of("status", status, "actualResult", "记录")).status()).isEqualTo(200);
        }

        // A pending agent session for 生成中需求 (quota-safe: cancel others first).
        long generatingReq = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "生成中需求");
        assertThat(post(admin, "/api/v1/projects/" + pidA + "/agent/sessions",
                Map.of("requirementId", generatingReq)).status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void matrixShowsExplicitStatusLadder() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pidA = projectId("cov-project-a");

        JsonNode matrix = get(admin, "/api/v1/projects/" + pidA + "/coverage").body();
        List<Map<String, Object>> sessionDebug = jdbc.queryForList(
                "SELECT id, requirement_id, status, error_message FROM agent_session");
        System.out.println("DEBUG sessions: " + sessionDebug);
        Map<String, String> statusByTitle = new java.util.HashMap<>();
        for (JsonNode row : matrix) {
            statusByTitle.put(row.get("title").asText(), row.get("status").asText());
        }

        assertThat(statusByTitle.get("未覆盖需求")).isEqualTo("NOT_COVERED");
        assertThat(statusByTitle.get("生成中需求"))
                .as("sessions=%s", jdbc.queryForList(
                        "SELECT id, requirement_id, status FROM agent_session"))
                .isEqualTo("GENERATING");
        assertThat(statusByTitle.get("已覆盖需求")).isEqualTo("EXEC_PASSED");
        assertThat(statusByTitle.get("执行失败需求")).isEqualTo("EXEC_FAILED");

        // Traceability: the failed requirement row carries its READY version
        // and the FAILED run status.
        for (JsonNode row : matrix) {
            if ("执行失败需求".equals(row.get("title").asText())) {
                assertThat(row.get("caseVersions")).hasSize(1);
                assertThat(row.get("caseVersions").get(0).get("latestRunStatus").asText())
                        .isEqualTo("FAILED");
            }
        }
    }

    @Test
    @Order(2)
    void crossProjectTraceIsInvisible() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pidB = projectId("cov-project-b");

        JsonNode matrixB = get(admin, "/api/v1/projects/" + pidB + "/coverage").body();
        assertThat(matrixB).isEmpty(); // project B has no requirements at all
    }

    @Test
    @Order(3)
    void requirementUpdateDoesNotRewriteHistoricalCoverage() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pidA = projectId("cov-project-a");
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "执行失败需求");
        long caseVersionId = jdbc.queryForObject(
                "SELECT tv.id FROM test_case_version tv JOIN test_case tc ON tc.id = tv.case_id "
                        + "WHERE tc.title = '用例-执行失败需求'", Long.class);

        // Update the requirement -> v2. The historical execution record still
        // points at the same immutable case version.
        HttpResult updated = put(admin, "/api/v1/projects/" + pidA + "/requirements/" + requirementId,
                Map.of("body", "需求已修订为 v2 内容。"));
        assertThat(updated.status()).isEqualTo(200);

        Long stillLinked = jdbc.queryForObject(
                "SELECT count(*) FROM manual_run_item mri "
                        + "JOIN test_plan_item tpi ON tpi.id = mri.plan_item_id "
                        + "WHERE tpi.case_version_id = ?",
                Long.class, caseVersionId);
        assertThat(stillLinked).isEqualTo(1);

        // The matrix still reports the execution result for this requirement.
        JsonNode matrix = get(admin, "/api/v1/projects/" + pidA + "/coverage").body();
        for (JsonNode row : matrix) {
            if ("执行失败需求".equals(row.get("title").asText())) {
                assertThat(row.get("status").asText()).isEqualTo("EXEC_FAILED");
                assertThat(row.get("currentVersion").asInt()).isEqualTo(2);
            }
        }
    }
}
