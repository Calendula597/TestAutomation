package io.github.calendula597.testplatform.platformserver.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutomationApiIntegrationTest {
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.automation";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MASTER_KEY = newMasterKey();
    private static final HttpServer FAILURE_SERVER = startFailureServer();
    private static final String BASE_URL = "http://127.0.0.1:" + FAILURE_SERVER.getAddress().getPort();
    private static long projectA;
    private static long projectB;
    private static long environmentA;
    private static long environmentB;
    private static long caseId;
    private static long otherCaseId;
    private static long scheduleId;
    private static String workerToken;
    private static String triggerToken;
    private static String readToken;
    private static long readTokenId;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static String newMasterKey() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static HttpServer startFailureServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/failure", exchange -> {
                byte[] body = "{\"code\":\"FAIL\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @AfterAll
    static void stopServer() {
        FAILURE_SERVER.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.secrets.master-key", () -> MASTER_KEY);
        registry.add("app.execution.schedule-sweep-ms", () -> "50");
        registry.add("logging.level.org.springframework.security", () -> "INFO");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.github.calendula597.testplatform.platformserver.worker.ApiExecutor apiExecutor;

    private record BrowserClient(RestClient rest, CookieManager cookies) {
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private BrowserClient clientWithSession() {
        CookieManager cookies = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookies).build();
        return new BrowserClient(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient)).build(), cookies);
    }

    private HttpResult send(RestClient.RequestBodySpec spec) {
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
            return new HttpResult(response.getStatusCode().value(),
                    objectMapper.readTree(new String(bytes).isEmpty() ? "{}" : new String(bytes)));
        });
    }

    private HttpResult call(BrowserClient client, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = client.rest().method(method).uri(path);
        if (method != HttpMethod.GET) {
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            String csrf = client.cookies().getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue).findFirst().orElse("");
            spec.headers(headers -> headers.add("X-XSRF-TOKEN", csrf));
        }
        return send(spec);
    }

    private HttpResult post(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.POST, path, body);
    }

    private HttpResult get(BrowserClient client, String path) {
        return call(client, HttpMethod.GET, path, null);
    }

    private HttpResult delete(BrowserClient client, String path) {
        return call(client, HttpMethod.DELETE, path, null);
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password)).status()).isEqualTo(200);
        return client;
    }

    private HttpResult ci(String token, HttpMethod method, long projectId, String path, Object body, String key) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + token).build()
                .method(method).uri("/api/v1/ci/v1/projects/" + projectId + "/executions" + path);
        if (key != null) {
            spec.header("Idempotency-Key", key);
        }
        if (body != null) {
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        }
        return send(spec);
    }

    private HttpResult workerPost(String path, Object body) {
        return send(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + workerToken).build()
                .post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    @Order(0)
    void setupTwoProjectsEnvironmentWorkerAndReadyCases() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD)).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "automation-a")).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "automation-b")).status()).isEqualTo(201);
        projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = 'automation-a'", Long.class);
        projectB = jdbc.queryForObject("SELECT id FROM project WHERE name = 'automation-b'", Long.class);
        long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')", projectA, memberId);
        for (long projectId : List.of(projectA, projectB)) {
            HttpResult environment = post(admin, "/api/v1/projects/" + projectId + "/environments",
                    Map.of("name", "CI 环境", "plainVariables", Map.of("baseUrl", BASE_URL),
                            "secrets", Map.of(), "targets", List.of(BASE_URL)));
            assertThat(environment.status()).isEqualTo(201);
            if (projectId == projectA) {
                environmentA = environment.body().get("id").asLong();
            } else {
                environmentB = environment.body().get("id").asLong();
            }
        }
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        caseId = createReadyCase(member, admin, "CI 真实失败");
        otherCaseId = createReadyCase(member, admin, "不同幂等请求");
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Automation Worker", "capabilities", List.of("API"),
                        "version", "0.1.0", "maxConcurrency", 2));
        assertThat(worker.status()).isEqualTo(201);
        workerToken = worker.body().get("token").asText();
    }

    private long createReadyCase(BrowserClient member, BrowserClient admin, String title) {
        Map<String, Object> execution = Map.of(
                "request", Map.of("method", "GET", "url", "{{baseUrl}}/failure",
                        "headers", Map.of("Accept", "application/json")),
                "assertions", List.of(Map.of("type", "STATUS", "expected", "200")));
        HttpResult created = post(member, "/api/v1/projects/" + projectA + "/testcases", Map.of(
                "title", title, "caseType", "API", "execution", execution,
                "steps", List.of(Map.of("stepOrder", 1, "action", "请求失败接口", "expected", "断言失败"))));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long id = created.body().get("id").asLong();
        assertThat(post(admin, "/api/v1/projects/" + projectA + "/testcases/" + id + "/review/publish",
                Map.of("basis", "CI 自动化基线", "expectedVersion", 1)).status()).isEqualTo(201);
        return id;
    }

    @Test
    @Order(1)
    void schedulesKeepTimezoneAndDoNotOverlapQueuedWork() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        Map<String, Object> request = new HashMap<>();
        request.put("name", "每秒健康检查");
        request.put("caseId", caseId);
        request.put("environmentId", environmentA);
        request.put("cronExpression", "* * * * * *");
        request.put("timeZone", "Asia/Shanghai");
        request.put("enabled", true);
        HttpResult created = post(member, "/api/v1/projects/" + projectA + "/schedules", request);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        scheduleId = created.body().get("id").asLong();
        assertThat(created.body().get("timeZone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(created.body().get("allowOverlap").asBoolean()).isFalse();
        assertThat(post(member, "/api/v1/projects/" + projectA + "/schedules", request).status()).isEqualTo(409);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long count = 0;
        while (System.nanoTime() < deadline && count == 0) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM execution_job WHERE schedule_id = ?", Long.class, scheduleId);
            Thread.sleep(50);
        }
        assertThat(count).isEqualTo(1);
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_job WHERE schedule_id = ?",
                Long.class, scheduleId)).isEqualTo(1);
        assertThat(delete(member, "/api/v1/projects/" + projectA + "/schedules/" + scheduleId).status()).isEqualTo(204);
        HttpResult schedules = get(member, "/api/v1/projects/" + projectA + "/schedules");
        assertThat(schedules.body().get(0).get("enabled").asBoolean()).isFalse();
        assertThat(schedules.body().get(0).get("timeZone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(schedules.body().get(0).get("lastFiredAt").isNull()).isFalse();
    }

    @Test
    @Order(2)
    void ciTokensEnforceScopeProjectAndIdempotencyAndReportRealFailure() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        Map<String, Object> triggerRequest = Map.of("name", "CI 触发", "scopes", List.of("EXECUTION_TRIGGER"),
                "expiresAt", OffsetDateTime.now().plusHours(2).toString());
        assertThat(post(member, "/api/v1/projects/" + projectA + "/ci-tokens", triggerRequest).status()).isEqualTo(403);
        HttpResult trigger = post(admin, "/api/v1/projects/" + projectA + "/ci-tokens", triggerRequest);
        assertThat(trigger.status()).isEqualTo(201);
        triggerToken = trigger.body().get("token").asText();
        HttpResult read = post(admin, "/api/v1/projects/" + projectA + "/ci-tokens",
                Map.of("name", "CI 查询", "scopes", List.of("EXECUTION_READ"),
                        "expiresAt", OffsetDateTime.now().plusHours(2).toString()));
        assertThat(read.status()).isEqualTo(201);
        readToken = read.body().get("token").asText();
        readTokenId = read.body().get("id").asLong();
        assertThat(get(admin, "/api/v1/projects/" + projectA + "/ci-tokens").body().toString())
                .doesNotContain(triggerToken, readToken, "tokenHash");

        Map<String, Object> execution = Map.of("caseId", caseId, "environmentId", environmentA);
        assertThat(ci("", HttpMethod.POST, projectA, "", execution, "no-token-01").status()).isEqualTo(401);
        assertThat(ci(triggerToken, HttpMethod.POST, projectB, "", execution, "cross-project-01").status()).isEqualTo(404);
        assertThat(ci(readToken, HttpMethod.POST, projectA, "", execution, "read-only-01").status()).isEqualTo(403);
        assertThat(ci(triggerToken, HttpMethod.GET, projectA, "/1", null, null).status()).isEqualTo(403);
        assertThat(send(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + triggerToken).build()
                .post().uri("/api/v1/worker/v1/jobs/claim")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of())).status()).isEqualTo(401);
        assertThat(send(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + triggerToken).build()
                .method(HttpMethod.GET).uri("/api/v1/projects/" + projectA + "/executions")).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_job WHERE project_id = ?", Long.class, projectB))
                .isZero();

        HttpResult first = ci(triggerToken, HttpMethod.POST, projectA, "", execution, "build-0001");
        assertThat(first.status()).as(first.body().toString()).isEqualTo(202);
        long jobId = first.body().get("jobId").asLong();
        HttpResult duplicate = ci(triggerToken, HttpMethod.POST, projectA, "", execution, "build-0001");
        assertThat(duplicate.status()).isEqualTo(200);
        assertThat(duplicate.body().get("jobId").asLong()).isEqualTo(jobId);
        assertThat(duplicate.body().get("idempotent").asBoolean()).isTrue();
        assertThat(ci(triggerToken, HttpMethod.POST, projectA, "",
                Map.of("caseId", otherCaseId, "environmentId", environmentA), "build-0001").status()).isEqualTo(409);
        assertThat(ci(readToken, HttpMethod.GET, projectB, "/" + jobId, null, null).status()).isEqualTo(404);

        executeRealJob(jobId);
        HttpResult detail = ci(readToken, HttpMethod.GET, projectA, "/" + jobId, null, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(detail.body().get("attempts").get(0).get("result").get("response")
                .get("statusCode").asInt()).isEqualTo(503);
    }

    private void executeRealJob(long expectedJobId) {
        for (int i = 0; i < 10; i++) {
            HttpResult claimed = workerPost("/api/v1/worker/v1/jobs/claim", Map.of());
            assertThat(claimed.status()).isEqualTo(200);
            if (claimed.body().get("job").get("id").asLong() != expectedJobId) {
                continue;
            }
            long attemptId = claimed.body().get("attempt").get("id").asLong();
            long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
            long environmentId = claimed.body().get("job").get("payload").get("environmentId").asLong();
            var outcome = apiExecutor.execute(caseVersionId, environmentId);
            assertThat(outcome.status()).isEqualTo("ASSERTION_FAILED");
            Map<String, Object> detail = objectMapper.convertValue(outcome, Map.class);
            assertThat(workerPost("/api/v1/worker/v1/attempts/" + attemptId + "/result",
                    Map.of("status", outcome.status(), "detail", detail)).status()).isEqualTo(200);
            return;
        }
        throw new AssertionError("CI 任务未被 Worker 领取");
    }

    @Test
    @Order(3)
    void expiredAndRevokedTokensCannotTriggerOrRead() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult expiring = post(admin, "/api/v1/projects/" + projectA + "/ci-tokens",
                Map.of("name", "短期令牌", "scopes", List.of("EXECUTION_TRIGGER", "EXECUTION_READ"),
                        "expiresAt", OffsetDateTime.now().plusMinutes(5).toString()));
        assertThat(expiring.status()).isEqualTo(201);
        long id = expiring.body().get("id").asLong();
        String raw = expiring.body().get("token").asText();
        jdbc.update("UPDATE ci_token SET expires_at = ? WHERE id = ?", OffsetDateTime.now().minusMinutes(1), id);
        Map<String, Object> execution = Map.of("caseId", caseId, "environmentId", environmentA);
        assertThat(ci(raw, HttpMethod.POST, projectA, "", execution, "expired-token-01").status()).isEqualTo(401);
        assertThat(delete(admin, "/api/v1/projects/" + projectA + "/ci-tokens/" + readTokenId).status()).isEqualTo(204);
        assertThat(ci(readToken, HttpMethod.GET, projectA, "/1", null, null).status()).isEqualTo(401);
        assertThat(post(admin, "/api/v1/projects/" + projectA + "/ci-tokens",
                Map.of("name", "CI 触发", "scopes", List.of("EXECUTION_TRIGGER"),
                        "expiresAt", OffsetDateTime.now().plusHours(1).toString())).status()).isEqualTo(409);
        assertThat(post(admin, "/api/v1/projects/" + projectA + "/ci-tokens",
                Map.of("name", "非法作用域", "scopes", List.of("ADMIN"),
                        "expiresAt", OffsetDateTime.now().plusHours(1).toString())).status()).isEqualTo(400);
    }
}
