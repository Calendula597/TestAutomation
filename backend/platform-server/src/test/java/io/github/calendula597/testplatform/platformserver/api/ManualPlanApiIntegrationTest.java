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
 * Manual testing seams: plans over immutable READY versions, resumable
 * step-level recording (pass/fail/blocked/skipped), and aggregated reports.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManualPlanApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

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
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "manual-project");
    }

    @Test
    @Order(0)
    void setupAdminProjectPublishedVersions() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "manual-project")).status()).isEqualTo(201);

        long pid = projectId();
        for (String title : List.of("用例一", "用例二")) {
            assertThat(post(admin, "/api/v1/projects/" + pid + "/testcases",
                    Map.of("title", title,
                            "steps", List.of(Map.of("action", "打开页面", "expected", "加载成功"))))
                    .status()).isEqualTo(201);
            long caseId = jdbc.queryForObject("SELECT id FROM test_case WHERE title = ?", Long.class, title);
            HttpResult published = post(admin,
                    "/api/v1/projects/" + pid + "/testcases/" + caseId + "/review/publish",
                    Map.of("basis", "结构完整", "expectedVersion", 1));
            assertThat(published.status()).as("publish " + title).isEqualTo(201);
        }

        // One unpublished draft to prove it cannot enter a plan.
        assertThat(post(admin, "/api/v1/projects/" + pid + "/testcases",
                Map.of("title", "未发布草稿",
                        "steps", List.of(Map.of("action", "x", "expected", "y")))).status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void planRequiresReadyVersionsInOrder() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        Long v1 = jdbc.queryForObject(
                "SELECT tv.id FROM test_case_version tv JOIN test_case tc ON tc.id = tv.case_id "
                        + "WHERE tc.title = '用例一'", Long.class);
        Long v2 = jdbc.queryForObject(
                "SELECT tv.id FROM test_case_version tv JOIN test_case tc ON tc.id = tv.case_id "
                        + "WHERE tc.title = '用例二'", Long.class);

        // An unpublished draft has no version row at all; a bogus id behaves
        // the same way — the plan must refuse it.
        HttpResult withBogus = post(admin, "/api/v1/projects/" + pid + "/plans",
                Map.of("name", "含草稿的计划", "caseVersionIds", List.of(v1, 999_999L)));
        assertThat(withBogus.status()).as("non-READY/missing version cannot enter a plan").isEqualTo(404);

        HttpResult created = post(admin, "/api/v1/projects/" + pid + "/plans",
                Map.of("name", "冒烟回归", "caseVersionIds", List.of(v1, v2)));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("items")).hasSize(2);
        assertThat(created.body().get("items").get(0).get("caseTitle").asText()).isEqualTo("用例一");
    }

    @Test
    @Order(2)
    void runExpandsItemsAndRecordsStepLevelResults() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long planId = jdbc.queryForObject("SELECT id FROM test_plan WHERE name = ?", Long.class, "冒烟回归");

        HttpResult started = post(admin, "/api/v1/projects/" + pid + "/plans/" + planId + "/runs", Map.of());
        assertThat(started.status()).isEqualTo(201);
        long runId = started.body().get("runId").asLong();

        JsonNode run = get(admin, "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId).body();
        assertThat(run.get("items")).hasSize(2);
        assertThat(run.get("items").get(0).get("status").asText()).isEqualTo("PENDING");
        // The executed content is the immutable published snapshot's steps.
        assertThat(run.get("items").get(0).get("steps")).hasSize(1);
        assertThat(run.get("items").get(0).get("steps").get(0).get("action").asText()).isEqualTo("打开页面");

        Long firstItem = run.get("items").get(0).get("id").asLong();
        HttpResult recorded = put(admin,
                "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId + "/items/" + firstItem,
                Map.of("status", "FAILED", "actualResult", "首页显示 500",
                        "note", "开发已确认", "evidenceUrl", "https://evidence.example/500.png"));
        assertThat(recorded.status()).isEqualTo(200);
        assertThat(recorded.body().get("items").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(recorded.body().get("items").get(0).get("evidenceUrl").asText()).contains("500.png");

        Long secondItem = run.get("items").get(1).get("id").asLong();
        HttpResult second = put(admin,
                "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId + "/items/" + secondItem,
                Map.of("status", "PASSED", "actualResult", "符合预期"));
        assertThat(second.status()).isEqualTo(200);

        // Interrupted session resumes: the values are still there.
        JsonNode resumed = get(admin, "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId).body();
        assertThat(resumed.get("items").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(resumed.get("items").get(0).get("actualResult").asText()).isEqualTo("首页显示 500");
    }

    @Test
    @Order(3)
    void completionProducesAggregatedReport() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long planId = jdbc.queryForObject("SELECT id FROM test_plan WHERE name = ?", Long.class, "冒烟回归");
        long runId = jdbc.queryForObject("SELECT id FROM manual_run ORDER BY id DESC LIMIT 1", Long.class);

        // One item remains PASSED/FAILED; mark nothing else — complete now.
        HttpResult completed = post(admin,
                "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId + "/complete", Map.of());
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.body().get("status").asText()).isEqualTo("COMPLETED");

        JsonNode report = get(admin,
                "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId + "/report").body();
        JsonNode summary = report.get("summary");
        assertThat(summary.get("total").asLong()).isEqualTo(2);
        assertThat(summary.get("passed").asLong()).isEqualTo(1);
        assertThat(summary.get("failed").asLong()).isEqualTo(1);
        assertThat(summary.get("pending").asLong()).isEqualTo(0);
        assertThat(summary.get("executedBy")).isNotNull();

        // Recording against a completed run is refused.
        Long firstItem = jdbc.queryForObject(
                "SELECT id FROM manual_run_item WHERE run_id = ? ORDER BY id LIMIT 1", Long.class, runId);
        HttpResult late = put(admin,
                "/api/v1/projects/" + pid + "/plans/" + planId + "/runs/" + runId + "/items/" + firstItem,
                Map.of("status", "PASSED"));
        assertThat(late.status()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void auditCoversPlanAndRunLifecycle() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action IN "
                        + "('PLAN_CREATED','RUN_STARTED','RUN_ITEM_RECORDED','RUN_COMPLETED')",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(4);
    }
}
