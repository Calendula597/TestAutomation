package io.github.calendula597.testplatform.platformserver.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API single-request execution loop (T15): real HTTP against a controlled
 * in-test service, status/JSONPath/text/timing assertions with genuine
 * pass and fail outcomes, network errors vs assertion failures counted
 * separately, masked request/response artifacts and pre-execution
 * allowlist enforcement with redirect re-validation.
 *
 * The test drives the real production ApiExecutor through the worker
 * protocol, exactly as the main Worker process will.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String OUTSIDER_USERNAME = "member.b";
    private static final String OUTSIDER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    private static final String SECRET_TOKEN = "sk-live-secret-99";
    private static final String BASIC_USER = "alice";
    private static final String BASIC_PASS = "wonderland-42";
    private static final String BASIC_HEADER = "Basic "
            + Base64.getEncoder().encodeToString((BASIC_USER + ":" + BASIC_PASS).getBytes(StandardCharsets.UTF_8));

    private static HttpServer DEMO;
    private static int demoPort;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String MASTER_KEY = newMasterKey();

    private static String newMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.secrets.master-key", () -> MASTER_KEY);
    }

    @BeforeAll
    static void startDemoService() throws Exception {
        // Controlled repository-hosted target: a real HTTP server.
        DEMO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        demoPort = DEMO.getAddress().getPort();
        DEMO.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String echoed = body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String apiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            String payload = "{\"code\":\"OK\",\"headers\":{\"Authorization\":\""
                    + (auth == null ? "" : auth.replace("\"", "'")) + "\",\"X-Api-Key\":\""
                    + (apiKey == null ? "" : apiKey) + "\"},\"body\":\"" + echoed.replace("\"", "'")
                    + "\"}";
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.createContext("/text", exchange -> {
            byte[] response = "welcome to demo".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.createContext("/slow", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = "{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.createContext("/redirect-external", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example.com/collect");
            exchange.sendResponseHeaders(302, -1);
        });
        DEMO.setExecutor(Executors.newFixedThreadPool(4));
        DEMO.start();
    }

    @AfterAll
    static void stopDemoService() {
        if (DEMO != null) {
            DEMO.stop(0);
        }
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

    private BrowserClient clientWithSession() {
        CookieManager cookies = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookies).build();
        RestClient rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
                .build();
        return new BrowserClient(rest, cookies);
    }

    private RestClient workerClient(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().build()))
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private HttpResult send(RestClient.RequestBodySpec spec) {
        return spec.exchange((req, res) -> {
            byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
            String content = new String(raw);
            return new HttpResult(res.getStatusCode().value(),
                    objectMapper.readTree(content.isEmpty() ? "{}" : content));
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
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            spec.headers(h -> h.add("X-XSRF-TOKEN", csrf));
        }
        return send(spec);
    }

    private HttpResult post(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.POST, path, body);
    }

    private HttpResult get(BrowserClient client, String path) {
        return call(client, HttpMethod.GET, path, null);
    }

    private HttpResult workerPost(String token, String path, Object body) {
        RestClient.RequestBodySpec spec = workerClient(token).method(HttpMethod.POST).uri(path);
        spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }
        return send(spec);
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult result = call(client, HttpMethod.POST, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    private static final Map<String, String> WORKER_TOKENS = new HashMap<>();

    private String spec(String method, String path, String authType, String bodyType, String body) {
        Map<String, Object> request = new HashMap<>();
        request.put("method", method);
        request.put("url", "{{baseUrl}}" + path);
        request.put("headers", Map.of("Accept", "application/json"));
        if (bodyType != null) {
            request.put("bodyType", bodyType);
            request.put("body", body);
        }
        switch (authType) {
            case "BEARER" -> request.put("auth", Map.of("type", "BEARER", "secretKey", "apiKey"));
            case "BASIC" -> request.put("auth", Map.of("type", "BASIC",
                    "usernameKey", "basicUser", "passwordKey", "basicPass"));
            case "API_KEY" -> request.put("auth", Map.of("type", "API_KEY",
                    "secretKey", "apiKey", "headerName", "X-Api-Key"));
            default -> {
                // NONE
            }
        }
        Map<String, Object> execution = new HashMap<>();
        execution.put("request", request);
        execution.put("assertions", List.of(
                Map.of("type", "STATUS", "expected", "200"),
                Map.of("type", "JSONPATH", "path", "$.code", "expected", "OK")));
        return objectMapper.writeValueAsString(execution);
    }

    /** Member writes an API case; the owner publishes it as a READY version.
     * Returns the case id — executions target its latest READY version. */
    private long createReadyApiCase(BrowserClient member, BrowserClient admin, long projectId,
                                    String title, String executionJson) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "API");
        create.put("execution", objectMapper.readValue(executionJson, Map.class));
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "发送请求并校验响应", "expected", "断言全部通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId + "/testcases", create);
        assertThat(created.status()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();

        HttpResult published = post(admin, "/api/v1/projects/" + projectId + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "T15 首个可执行版本", "expectedVersion", draftVersion));
        assertThat(published.status()).isEqualTo(201);

        return caseId;
    }

    /** Claim the queued job, run the real executor, report through the protocol. */
    private void runQueuedJob(String workerToken) {
        HttpResult claimed = workerPost(workerToken, "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).as("应有排队任务可领取").isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long environmentId = claimed.body().get("job").get("payload").get("environmentId").asLong();

        var outcome = apiExecutor.execute(caseVersionId, environmentId);
        Map<String, Object> detail = objectMapper.readValue(
                objectMapper.writeValueAsString(outcome), Map.class);
        HttpResult reported = workerPost(workerToken,
                "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).isEqualTo(200);
    }

    private long triggerExecution(BrowserClient member, long projectId, long caseId, long environmentId) {
        HttpResult triggered = post(member, "/api/v1/projects/" + projectId + "/executions",
                Map.of("caseId", caseId, "environmentId", environmentId));
        assertThat(triggered.status()).isEqualTo(202);
        return triggered.body().get("jobId").asLong();
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject("SELECT status FROM execution_job WHERE id = ?", String.class, jobId);
    }

    private JsonNode latestResult(long jobId) {
        String result = jdbc.queryForObject(
                "SELECT a.result FROM execution_attempt a WHERE a.job_id = ? "
                        + "AND a.result IS NOT NULL ORDER BY a.id DESC LIMIT 1", String.class, jobId);
        return objectMapper.readTree(result).get("detail");
    }

    @Test
    @Order(0)
    void setupUsersProjectEnvironmentAndWorker() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        for (String user : List.of(MEMBER_USERNAME, OUTSIDER_USERNAME)) {
            String password = user.equals(MEMBER_USERNAME) ? MEMBER_PASSWORD : OUTSIDER_PASSWORD;
            assertThat(post(admin, "/api/v1/system/users",
                    Map.of("username", user, "password", password)).status()).isEqualTo(201);
        }
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "api-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        long project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "api-project");
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                project, member);

        // Environment with variables, secrets and the allowlist (T13 API).
        HttpResult env = post(admin, "/api/v1/projects/" + project + "/environments",
                Map.of("name", "演示环境",
                        "plainVariables", Map.of("baseUrl", "http://127.0.0.1:" + demoPort),
                        "secrets", Map.of("apiKey", SECRET_TOKEN,
                                "basicUser", BASIC_USER, "basicPass", BASIC_PASS),
                        "targets", List.of("http://127.0.0.1:" + demoPort, "http://127.0.0.1:9")));
        assertThat(env.status()).isEqualTo(201);

        // API-capable worker registration (T14 API).
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "API Worker", "capabilities", List.of("API"),
                        "version", "0.1.0", "maxConcurrency", 2));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("api", worker.body().get("token").asText());
    }

    private long environmentId() {
        return jdbc.queryForObject("SELECT id FROM environment ORDER BY id LIMIT 1", Long.class);
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "api-project");
    }

    @Test
    @Order(1)
    void bearerRequestPassesRealAssertionsWithMaskedArtifacts() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseVersionId = createReadyApiCase(member, admin, projectId(),
                "登录成功", spec("POST", "/echo", "BEARER", "JSON", "{\"user\":\"alice\"}"));

        long jobId = triggerExecution(member, projectId(), caseVersionId, environmentId());
        assertThat(jobStatus(jobId)).isEqualTo("QUEUED");

        runQueuedJob(WORKER_TOKENS.get("api"));

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("status").asText()).isEqualTo("PASSED");
        assertThat(result.get("request").get("method").asText()).isEqualTo("POST");
        assertThat(result.get("request").get("url").asText()).contains("/echo");
        // The composed credential never survives into stored artifacts.
        assertThat(result.get("request").get("headers").get("Authorization").asText()).isEqualTo("******");
        assertThat(result.get("response").get("statusCode").asInt()).isEqualTo(200);
        assertThat(result.get("response").toString()).doesNotContain(SECRET_TOKEN);
        assertThat(result.get("assertions").size()).isEqualTo(2);
        for (JsonNode assertion : result.get("assertions")) {
            assertThat(assertion.get("passed").asBoolean()).isTrue();
        }
        assertThat(result.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);

        // Member-visible execution view carries the same masked evidence.
        HttpResult detail = get(member, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        JsonNode attemptResult = detail.body().get("attempts").get(0).get("result");
        assertThat(attemptResult.get("request").get("headers")
                .get("Authorization").asText()).isEqualTo("******");
        assertThat(detail.body().toString()).doesNotContain(SECRET_TOKEN);
    }

    @Test
    @Order(2)
    void basicAndApiKeyAuthenticationsAreSupportedAndMasked() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        long basicVersion = createReadyApiCase(member, admin, projectId(),
                "Basic 认证请求", spec("POST", "/echo", "BASIC", "JSON", "{\"a\":1}"));
        long basicJob = triggerExecution(member, projectId(), basicVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(basicJob)).isEqualTo("PASSED");
        JsonNode basicResult = latestResult(basicJob);
        assertThat(basicResult.get("request").get("headers").get("Authorization").asText())
                .isEqualTo("******");
        assertThat(basicResult.get("response").toString())
                .doesNotContain(BASIC_USER, BASIC_PASS, BASIC_HEADER);

        long apiKeyVersion = createReadyApiCase(member, admin, projectId(),
                "API Key 请求", spec("POST", "/echo", "API_KEY", "TEXT", "hello"));
        long apiKeyJob = triggerExecution(member, projectId(), apiKeyVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(apiKeyJob)).isEqualTo("PASSED");
        JsonNode apiKeyResult = latestResult(apiKeyJob);
        assertThat(apiKeyResult.get("request").get("headers").get("X-Api-Key").asText())
                .isEqualTo("******");
        assertThat(apiKeyResult.get("response").toString()).doesNotContain(SECRET_TOKEN);
    }

    @Test
    @Order(3)
    void assertionFailuresRecordExpectedAndActualSeparately() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String spec = """
                {"request":{"method":"GET","url":"{{baseUrl}}/text"},"assertions":[
                  {"type":"STATUS","expected":"200"},
                  {"type":"TEXT","expected":"goodbye"},
                  {"type":"JSONPATH","path":"$.code","expected":"OK"}]}""";
        long caseVersionId = createReadyApiCase(member, admin, projectId(), "文本断言失败", spec);

        long jobId = triggerExecution(member, projectId(), caseVersionId, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));

        // Assertion failure is not an execution error — the HTTP round trip worked.
        assertThat(jobStatus(jobId)).isEqualTo("ASSERTION_FAILED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(result.get("response").get("statusCode").asInt()).isEqualTo(200);
        JsonNode assertions = result.get("assertions");
        assertThat(assertions.size()).isEqualTo(3);
        assertThat(assertions.get(1).get("passed").asBoolean()).isFalse();
        assertThat(assertions.get(1).get("expected").asText()).isEqualTo("goodbye");
        assertThat(assertions.get(1).get("actual").asText()).contains("welcome");
        // JSONPath on a plain-text body fails explicitly, not silently.
        assertThat(assertions.get(2).get("passed").asBoolean()).isFalse();
    }

    @Test
    @Order(4)
    void networkErrorsAndTimingFailuresAreDistinctOutcomes() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // Allowed target that refuses connections: infrastructure ERROR.
        String refusedSpec = """
                {"request":{"method":"GET","url":"http://127.0.0.1:9/refused"},"assertions":[
                  {"type":"STATUS","expected":"200"}]}""";
        long refusedVersion = createReadyApiCase(member, admin, projectId(), "连接拒绝", refusedSpec);
        long refusedJob = triggerExecution(member, projectId(), refusedVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(refusedJob)).isEqualTo("ERROR");
        JsonNode refusedResult = latestResult(refusedJob);
        assertThat(refusedResult.get("status").asText()).isEqualTo("ERROR");
        assertThat(refusedResult.get("reason").asText()).contains("网络错误");

        // Real latency against the slow endpoint beats a tight threshold.
        String timingSpec = """
                {"request":{"method":"GET","url":"{{baseUrl}}/slow"},"assertions":[
                  {"type":"STATUS","expected":"200"},
                  {"type":"RESPONSE_TIME_MS","expected":"50"}]}""";
        long timingVersion = createReadyApiCase(member, admin, projectId(), "响应时间断言", timingSpec);
        long timingJob = triggerExecution(member, projectId(), timingVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(timingJob)).isEqualTo("ASSERTION_FAILED");
        JsonNode timingResult = latestResult(timingJob);
        assertThat(timingResult.get("durationMs").asLong()).isGreaterThanOrEqualTo(400);
        JsonNode assertions = timingResult.get("assertions");
        assertThat(assertions.get(1).get("passed").asBoolean()).isFalse();
        assertThat(assertions.get(1).get("type").asText()).isEqualTo("RESPONSE_TIME_MS");

        // The two outcome classes are counted separately.
        JsonNode stats = get(admin, "/api/v1/system/queue/stats").body();
        assertThat(stats.get("counts").get("ERROR").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("counts").get("ASSERTION_FAILED").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(5)
    void allowlistRejectsUnauthorizedTargetsBeforeExecutionAndRevalidatesRedirects() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // Outside the allowlist: rejected before any request is issued.
        String offListSpec = """
                {"request":{"method":"GET","url":"http://evil.example.com/steal"},"assertions":[
                  {"type":"STATUS","expected":"200"}]}""";
        long offListVersion = createReadyApiCase(member, admin, projectId(), "越权目标", offListSpec);
        long offListJob = triggerExecution(member, projectId(), offListVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(offListJob)).isEqualTo("ERROR");
        JsonNode offListResult = latestResult(offListJob);
        assertThat(offListResult.get("reason").asText()).contains("目标不在白名单");
        // No request was issued: no response artifact exists at all.
        assertThat(offListResult.get("response").isNull()).isTrue();

        // Redirect leaving the allowlist is re-validated and refused.
        String redirectSpec = """
                {"request":{"method":"GET","url":"{{baseUrl}}/redirect-external"},"assertions":[
                  {"type":"STATUS","expected":"200"}]}""";
        long redirectVersion = createReadyApiCase(member, admin, projectId(), "外跳重定向", redirectSpec);
        long redirectJob = triggerExecution(member, projectId(), redirectVersion, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));
        assertThat(jobStatus(redirectJob)).isEqualTo("ERROR");
        JsonNode redirectResult = latestResult(redirectJob);
        assertThat(redirectResult.get("reason").asText()).contains("目标不在白名单");
        assertThat(redirectResult.get("redirects").toString()).contains("http://evil.example.com/collect");
    }

    @Test
    @Order(6)
    void oversizedResponsesAreTruncatedWithVisibleMarker() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String bigBody = "x".repeat(60_000);
        String spec = "{\"request\":{\"method\":\"POST\",\"url\":\"{{baseUrl}}/echo\","
                + "\"bodyType\":\"TEXT\",\"body\":\"" + bigBody + "\"},"
                + "\"assertions\":[{\"type\":\"STATUS\",\"expected\":\"200\"}]}";
        long caseVersionId = createReadyApiCase(member, admin, projectId(), "大响应截断", spec);

        long jobId = triggerExecution(member, projectId(), caseVersionId, environmentId());
        runQueuedJob(WORKER_TOKENS.get("api"));

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("response").get("bodyTruncated").asBoolean()).isTrue();
        assertThat(result.get("response").get("body").asText().length())
                .isLessThanOrEqualTo(60_000);
    }

    @Test
    @Order(7)
    void triggerValidationAndProjectIsolation() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        long project = projectId();

        // Outsider cannot see the project's executions.
        assertThat(get(outsider, "/api/v1/projects/" + project + "/executions").status()).isEqualTo(404);

        // Only cases with a READY API version are executable.
        Map<String, Object> draftCase = new HashMap<>();
        draftCase.put("title", "未发布草稿");
        draftCase.put("steps", List.of(Map.of("stepOrder", 1, "action", "a", "expected", "b")));
        HttpResult created = post(member, "/api/v1/projects/" + project + "/testcases", draftCase);
        long draftCaseId = created.body().get("id").asLong();
        HttpResult draftTrigger = post(member, "/api/v1/projects/" + project + "/executions",
                Map.of("caseId", draftCaseId, "environmentId", environmentId()));
        assertThat(draftTrigger.status()).isEqualTo(400);

        // A manual (non-API) READY case is not executable either.
        long manualCaseId = createReadyManualCase(member, admin, project);
        HttpResult manualTrigger = post(member, "/api/v1/projects/" + project + "/executions",
                Map.of("caseId", manualCaseId, "environmentId", environmentId()));
        assertThat(manualTrigger.status()).isEqualTo(400);

        // Environment of another project is refused.
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "api-project-b")).status()).isEqualTo(201);
        long projectB = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "api-project-b");
        HttpResult otherEnv = post(admin, "/api/v1/projects/" + projectB + "/environments",
                Map.of("name", "B 环境", "targets", List.of("http://127.0.0.1:" + demoPort)));
        assertThat(otherEnv.status()).isEqualTo(201);
        long otherEnvId = jdbc.queryForObject(
                "SELECT id FROM environment WHERE project_id = ? LIMIT 1", Long.class, projectB);
        long apiCaseId = jdbc.queryForObject(
                "SELECT tv.case_id FROM test_case_version tv JOIN test_case t ON t.id = tv.case_id "
                        + "WHERE tv.status = 'READY' AND tv.case_type = 'API' ORDER BY tv.id LIMIT 1",
                Long.class);
        HttpResult crossEnv = post(member, "/api/v1/projects/" + project + "/executions",
                Map.of("caseId", apiCaseId, "environmentId", otherEnvId));
        assertThat(crossEnv.status()).isEqualTo(400);

        // No secret material ever reaches audit rows.
        Integer leaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE detail LIKE ? OR detail LIKE ?",
                Integer.class, "%" + SECRET_TOKEN + "%", "%" + BASIC_PASS + "%");
        assertThat(leaks).isZero();
        Integer triggered = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'EXECUTION_TRIGGERED'",
                Integer.class);
        assertThat(triggered).isGreaterThanOrEqualTo(1);
    }

    private long createReadyManualCase(BrowserClient member, BrowserClient admin, long project) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", "手工用例");
        create.put("steps", List.of(Map.of("stepOrder", 1, "action", "打开页面", "expected", "可见")));
        HttpResult created = post(member, "/api/v1/projects/" + project + "/testcases", create);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + project + "/testcases/" + caseId + "/review/publish",
                Map.of("basis", "手工基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }
}
