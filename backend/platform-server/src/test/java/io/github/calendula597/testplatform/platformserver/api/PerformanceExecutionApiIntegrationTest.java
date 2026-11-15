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
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String MASTER_KEY = newMasterKey();
    private static final Map<String, String> WORKER_TOKENS = new HashMap<>();
    private static HttpServer DEMO;
    private static java.util.concurrent.ExecutorService DEMO_EXECUTOR;
    private static int demoPort;

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
        registry.add("app.evidence.dir", () -> java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "performance-evidence-" + UUID.randomUUID()).toString());
        registry.add("app.worker.sweep-interval-ms", () -> "300000");
    }

    @BeforeAll
    static void startDemo() throws Exception {
        DEMO = HttpServer.create(new InetSocketAddress(0), 0);
        demoPort = DEMO.getAddress().getPort();
        DEMO.createContext("/ok", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        DEMO.createContext("/fail", exchange -> {
            byte[] body = "failed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        DEMO_EXECUTOR = Executors.newFixedThreadPool(2);
        DEMO.setExecutor(DEMO_EXECUTOR);
        DEMO.start();
    }

    @AfterAll
    static void stopDemo() {
        if (DEMO != null) {
            DEMO.stop(0);
        }
        if (DEMO_EXECUTOR != null) {
            DEMO_EXECUTOR.shutdownNow();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.github.calendula597.testplatform.platformserver.worker.PerformanceExecutor performanceExecutor;

    private record BrowserClient(RestClient rest, CookieManager cookies) {
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private BrowserClient clientWithSession() {
        CookieManager cookies = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookies).build();
        RestClient rest = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
                .build();
        return new BrowserClient(rest, cookies);
    }

    private RestClient workerClient(String token) {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().build()))
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    private HttpResult send(RestClient.RequestBodySpec spec) {
        return spec.exchange((request, response) -> {
            byte[] raw = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
            String content = new String(raw);
            return new HttpResult(response.getStatusCode().value(),
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
        assertThat(post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password)).status()).isEqualTo(200);
        return client;
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "performance-project");
    }

    private long environmentId() {
        return jdbc.queryForObject("SELECT id FROM environment ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    @Order(0)
    void setupUsersProjectEnvironmentAndWorker() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD)).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "performance-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);
        String baseUrl = "http://host.docker.internal:" + demoPort;
        HttpResult environment = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "压测演示环境", "plainVariables", Map.of("baseUrl", baseUrl),
                        "targets", List.of(baseUrl)));
        assertThat(environment.status()).isEqualTo(201);
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Performance Worker", "capabilities", List.of("PERFORMANCE"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("performance", worker.body().get("token").asText());
    }

    private long createReadyPerformanceCase(BrowserClient admin, String title, Map<String, Object> execution) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "PERFORMANCE");
        create.put("execution", execution);
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "发起受限压测", "expected", "指标满足项目阈值")));
        HttpResult created = post(admin, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "压测基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }

    private long trigger(BrowserClient client, long caseId, Integer timeoutSeconds, boolean confirmed) {
        Map<String, Object> body = new HashMap<>();
        body.put("caseId", caseId);
        body.put("environmentId", environmentId());
        body.put("confirmed", confirmed);
        if (timeoutSeconds != null) {
            body.put("timeoutSeconds", timeoutSeconds);
        }
        HttpResult triggered = post(client, "/api/v1/projects/" + projectId() + "/executions", body);
        assertThat(triggered.status()).as(triggered.body().toString()).isEqualTo(202);
        return triggered.body().get("jobId").asLong();
    }

    private HttpResult runQueuedJob(BrowserClient admin, long jobId) throws Exception {
        HttpResult claimed = workerPost(WORKER_TOKENS.get("performance"),
                "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long envId = claimed.body().get("job").get("payload").get("environmentId").asLong();
        java.time.Instant deadline = java.time.Instant.now()
                .plusSeconds(claimed.body().get("job").get("timeoutSeconds").asLong());
        var control = new io.github.calendula597.testplatform.platformserver.worker.ExecutionControl() {
            @Override
            public java.time.Instant deadline() {
                return deadline;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
        var outcome = performanceExecutor.execute(caseVersionId, envId, control);
        Map<String, Object> detail = objectMapper.readValue(
                objectMapper.writeValueAsString(outcome), Map.class);
        HttpResult reported = workerPost(WORKER_TOKENS.get("performance"),
                "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).as(reported.body().toString()).isEqualTo(200);
        return get(admin, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
    }

    private Map<String, Object> execution(String target, int durationSeconds, Map<String, Object> thresholds) {
        return Map.of("target", target,
                "stages", List.of(Map.of("durationSeconds", durationSeconds, "target", 1)),
                "thresholds", thresholds);
    }

    @Test
    @Order(1)
    void realK6MetricsMatchSummaryExport() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyPerformanceCase(admin, "k6 指标通过", execution("{{baseUrl}}/ok", 2,
                Map.of("maxErrorRate", 0, "maxP95Ms", 1500, "maxP99Ms", 2000,
                        "minThroughputPerSecond", 1)));
        long jobId = trigger(admin, caseId, 30, true);

        HttpResult detail = runQueuedJob(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("engine").asText()).isEqualTo("k6");
        assertThat(result.get("engineVersion").asText()).isEqualTo("2.4.0");
        assertThat(result.get("metrics").get("count").asLong()).isGreaterThan(0);
        assertThat(result.get("metrics").get("errorRate").asDouble()).isEqualTo(0);
        assertThat(result.get("metrics").get("p90Ms").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(result.get("metrics").get("p95Ms").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(result.get("metrics").get("p99Ms").asDouble()).isGreaterThanOrEqualTo(0);
        String evidenceId = result.get("evidence").get(0).get("id").asText();
        HttpResult summary = get(admin, "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/" + evidenceId);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.body().get("metrics").get("http_reqs").get("count").asLong())
                .isEqualTo(result.get("metrics").get("count").asLong());
    }

    @Test
    @Order(2)
    void thresholdFailureUsesRealMetrics() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyPerformanceCase(admin, "k6 阈值失败", execution("{{baseUrl}}/ok", 1,
                Map.of("maxP95Ms", 0)));
        long jobId = trigger(admin, caseId, 30, true);

        HttpResult detail = runQueuedJob(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        JsonNode threshold = detail.body().get("attempts").get(0).get("result")
                .get("thresholds").get(0);
        assertThat(threshold.get("name").asText()).isEqualTo("maxP95Ms");
        assertThat(threshold.get("passed").asBoolean()).isFalse();
        assertThat(threshold.get("actual").asDouble()).isGreaterThan(0);
    }

    @Test
    @Order(3)
    void httpErrorsFeedErrorRateThreshold() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyPerformanceCase(admin, "k6 错误率失败", execution("{{baseUrl}}/fail", 1,
                Map.of("maxErrorRate", 0)));
        long jobId = trigger(admin, caseId, 30, true);

        HttpResult detail = runQueuedJob(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(detail.body().get("attempts").get(0).get("result")
                .get("metrics").get("errorRate").asDouble()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void memberCannotCreateOrTriggerPerformanceAndConfirmationIsRequired() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        Map<String, Object> create = new HashMap<>();
        create.put("title", "普通成员越权压测");
        create.put("caseType", "PERFORMANCE");
        create.put("execution", execution("{{baseUrl}}/ok", 1, Map.of("maxP95Ms", 1000)));
        create.put("steps", List.of());
        assertThat(post(member, "/api/v1/projects/" + projectId() + "/testcases", create).status())
                .isEqualTo(403);

        long caseId = createReadyPerformanceCase(admin, "压测权限与确认", execution("{{baseUrl}}/ok", 1,
                Map.of("maxP95Ms", 1000)));
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("caseId", caseId);
        trigger.put("environmentId", environmentId());
        trigger.put("confirmed", true);
        assertThat(post(member, "/api/v1/projects/" + projectId() + "/executions", trigger).status())
                .isEqualTo(403);
        trigger.put("confirmed", false);
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/executions", trigger).status())
                .isEqualTo(400);
    }

    @Test
    @Order(5)
    void loadLimitAndAllowlistRejectBeforeExecution() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> excessive = new HashMap<>(execution("{{baseUrl}}/ok", 1, Map.of("maxP95Ms", 1000)));
        excessive.put("stages", List.of(Map.of("durationSeconds", 1, "target", 100)));
        Map<String, Object> create = new HashMap<>();
        create.put("title", "超限压测");
        create.put("caseType", "PERFORMANCE");
        create.put("execution", excessive);
        create.put("steps", List.of());
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases", create).status())
                .isEqualTo(400);

        long evilCase = createReadyPerformanceCase(admin, "非白名单压测",
                execution("http://evil.example.com", 1, Map.of("maxP95Ms", 1000)));
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("caseId", evilCase);
        trigger.put("environmentId", environmentId());
        trigger.put("confirmed", true);
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/executions", trigger).status())
                .isEqualTo(400);
    }

    @Test
    @Order(6)
    void timeoutKillsK6Container() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyPerformanceCase(admin, "压测超时", execution("{{baseUrl}}/ok", 20,
                Map.of("maxP95Ms", 1000)));
        long jobId = trigger(admin, caseId, 1, true);

        HttpResult detail = runQueuedJob(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("TIMED_OUT");
        String containers = new String(new ProcessBuilder("docker", "ps", "-q",
                "--filter", "ancestor=grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276")
                .start().getInputStream().readAllBytes()).trim();
        assertThat(containers).isEmpty();
    }
}
