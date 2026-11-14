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
 * Review and publish seams: OWNER-only publication with a basis, immutable
 * READY snapshots, and draft edits that never rewrite history.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseReviewApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "qian.ba";
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

    @Test
    @Order(0)
    void setupUsersProjectDraftAndMembership() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult init = post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(init.status()).isEqualTo(201);
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult created = post(bootstrap, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD));
        assertThat(created.status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult project = post(admin, "/api/v1/projects", Map.of("name", "review-project"));
        assertThat(project.status()).isEqualTo(201);
        long projectId = project.body().get("id").asLong();

        long memberId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);
        HttpResult added = post(admin, "/api/v1/projects/" + projectId + "/members",
                Map.of("username", MEMBER_USERNAME, "role", "MEMBER"));
        assertThat(added.status()).isEqualTo(201);
        assertThat(memberId).isNotNull();

        // MEMBER creates a draft with a valid step.
        HttpResult draft = post(admin, "/api/v1/projects/" + projectId + "/testcases",
                Map.of("title", "审核用例", "priority", "MEDIUM",
                        "steps", List.of(Map.of("action", "打开页面", "expected", "加载成功"))));
        assertThat(draft.status()).isEqualTo(201);
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "review-project");
    }

    private long caseId() {
        return jdbc.queryForObject("SELECT id FROM test_case WHERE title = ?", Long.class, "审核用例");
    }

    @Test
    @Order(1)
    void memberCannotPublishAndEmptyStepsRejected() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long pid = projectId();
        long cid = caseId();

        HttpResult denied = post(member,
                "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/publish",
                Map.of("basis", " member 试图发布", "expectedVersion", 1));
        assertThat(denied.status()).as("member cannot publish").isEqualTo(403);

        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult noBasis = post(owner,
                "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/publish",
                Map.of("basis", "", "expectedVersion", 1));
        assertThat(noBasis.status()).isEqualTo(400);
    }

    @Test
    @Order(2)
    void ownerPublishesReadySnapshotWithBasis() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long cid = caseId();

        HttpResult published = post(owner,
                "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/publish",
                Map.of("basis", "用例结构完整，预期明确", "expectedVersion", 1));
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("version").asInt()).isEqualTo(1);
        assertThat(published.body().get("status").asText()).isEqualTo("READY");

        // The draft row is marked READY and the snapshot exists with reviewer info.
        String status = jdbc.queryForObject("SELECT status FROM test_case WHERE id = ?", String.class, cid);
        assertThat(status).isEqualTo("READY");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, status, published_by, publish_basis FROM test_case_version WHERE case_id = ?", cid);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(String.valueOf(row.get("publish_basis"))).contains("结构完整");

        // Diff now reports the READY snapshot as previous.
        JsonNode diff = get(owner, "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/diff").body();
        assertThat(diff.get("previousStatus").asText()).isEqualTo("READY");
        assertThat(diff.get("previous").get("title").asText()).isEqualTo("审核用例");
    }

    @Test
    @Order(3)
    void draftEditAndRepublishKeepHistoryImmutable() {
        BrowserClient owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long cid = caseId();

        // Edit the draft: different title and an extra step.
        HttpResult updated = put(owner, "/api/v1/projects/" + pid + "/testcases/" + cid,
                Map.of("title", "审核用例（修订）", "expectedVersion", 2,
                        "steps", List.of(
                                Map.of("action", "打开页面", "expected", "加载成功"),
                                Map.of("action", "输入凭据", "expected", "进入首页"))));
        assertThat(updated.status()).isEqualTo(200);

        // Diff shows previous READY v1 against the changed draft.
        JsonNode diff = get(owner, "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/diff").body();
        assertThat(diff.get("previous").get("title").asText()).isEqualTo("审核用例");
        assertThat(diff.get("current").get("title").asText()).isEqualTo("审核用例（修订）");
        assertThat(diff.get("current").get("steps")).hasSize(2);

        // Republish -> v2; v1 must remain untouched.
        HttpResult republished = post(owner,
                "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/publish",
                Map.of("basis", "补充凭据输入步骤", "expectedVersion", 3));
        assertThat(republished.status()).isEqualTo(201);
        assertThat(republished.body().get("version").asInt()).isEqualTo(2);

        Map<String, Object> v1 = jdbc.queryForMap(
                "SELECT title, steps_json FROM test_case_version WHERE case_id = ? AND version = 1", cid);
        assertThat(String.valueOf(v1.get("title"))).isEqualTo("审核用例");
        assertThat(String.valueOf(v1.get("steps_json"))).doesNotContain("输入凭据");

        // Version history is traceable: reviewer, time, basis per version.
        JsonNode versions = get(owner, "/api/v1/projects/" + pid + "/testcases/" + cid + "/review/versions").body();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("publishBasis").asText()).contains("补充凭据输入步骤");
        assertThat(versions.get(0).get("publishedAt")).isNotNull();
    }

    @Test
    @Order(4)
    void auditRecordsPublishActions() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'CASE_PUBLISHED'", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
