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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
class WhiteboxExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final Path EVIDENCE_DIR = Path.of(System.getProperty("java.io.tmpdir"),
            "whitebox-evidence-" + UUID.randomUUID());

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String MASTER_KEY = newMasterKey();
    private static final Map<String, String> WORKER_TOKENS = new HashMap<>();

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
        registry.add("app.evidence.dir", () -> EVIDENCE_DIR.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

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

    private String downloadText(BrowserClient client, String path) {
        return client.rest().get().uri(path).exchange((request, response) ->
                response.getBody() == null ? "" : new String(response.getBody().readAllBytes()));
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
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "whitebox-project");
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
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "whitebox-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);
        HttpResult environment = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "白盒演示环境", "plainVariables", Map.of(), "targets", List.of()));
        assertThat(environment.status()).isEqualTo(201);
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Whitebox Worker", "capabilities", List.of("WHITEBOX"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("whitebox", worker.body().get("token").asText());
    }

    private long createReadyWhiteboxCase(BrowserClient admin, String title, String scenario,
                                         Map<String, Object> thresholds) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "WHITEBOX");
        create.put("execution", Map.of(
                "project", "demo-js",
                "scenario", scenario,
                "coverage", Map.of("thresholds", thresholds, "exclude", List.of("src/legacy.js"))));
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "运行真实单测和覆盖率", "expected", "结果分类正确")));
        HttpResult created = post(admin, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "白盒基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }

    private long trigger(BrowserClient admin, long caseId, Integer timeoutSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("caseId", caseId);
        body.put("environmentId", environmentId());
        if (timeoutSeconds != null) {
            body.put("timeoutSeconds", timeoutSeconds);
        }
        HttpResult triggered = post(admin, "/api/v1/projects/" + projectId() + "/executions", body);
        assertThat(triggered.status()).as(triggered.body().toString()).isEqualTo(202);
        return triggered.body().get("jobId").asLong();
    }

    private HttpResult runWorker(BrowserClient admin, long jobId) throws Exception {
        Path entry = Path.of("").toAbsolutePath().resolve("../../workers/whitebox/src/worker.js").normalize();
        Path log = Path.of("target", "whitebox-worker-" + jobId + ".log");
        ProcessBuilder builder = new ProcessBuilder("node", entry.toString())
                .directory(entry.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("CONTROL_PLANE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("WORKER_TOKEN", WORKER_TOKENS.get("whitebox"));
        builder.environment().put("WORKER_ONCE", "1");
        builder.environment().put("EVIDENCE_DIR", EVIDENCE_DIR.toString());
        Process process = builder.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            HttpResult detail = null;
            while (System.nanoTime() < deadline) {
                detail = get(admin, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
                String status = detail.body().get("status").asText();
                if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                    boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                    String output = new String(Files.readAllBytes(log));
                    assertThat(exited).as(output).isTrue();
                    assertThat(process.exitValue()).as(output).isEqualTo(0);
                    return detail;
                }
                if (!process.isAlive()) {
                    String output = new String(Files.readAllBytes(log));
                    throw new AssertionError("Worker 提前退出: " + output);
                }
                Thread.sleep(100);
            }
            throw new AssertionError("等待白盒执行结束超时");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Order(1)
    void realTestsAndCoveragePass() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyWhiteboxCase(admin, "白盒通过", "pass",
                Map.of("statements", 90, "branches", 80, "functions", 100));
        long jobId = trigger(admin, caseId, 120);

        HttpResult detail = runWorker(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("category").asText()).isEqualTo("PASSED");
        assertThat(result.get("testResults").get("total").asInt()).isEqualTo(4);
        assertThat(result.get("coverage").get("statements").asDouble()).isEqualTo(100);
        assertThat(result.get("coverage").get("branches").asDouble()).isEqualTo(100);
        assertThat(result.get("coverage").get("functions").asDouble()).isEqualTo(100);
        assertThat(result.get("exclusions").toString()).contains("src/legacy.js");
        assertThat(result.get("engineVersion").asText()).isEqualTo("5.1.0");
        assertThat(result.get("evidence")).isNotEmpty();
        String evidenceId = result.get("evidence").get(0).get("id").asText();
        HttpResult download = get(admin, "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/" + evidenceId);
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.body().get("success").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void partialTestsFailCoverageThreshold() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyWhiteboxCase(admin, "覆盖率不达标", "partial",
                Map.of("statements", 100, "branches", 100, "functions", 100));
        long jobId = trigger(admin, caseId, 120);

        HttpResult detail = runWorker(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(detail.body().get("attempts").get(0).get("result")
                .get("category").asText()).isEqualTo("COVERAGE_FAILED");
    }

    @Test
    @Order(3)
    void failingTestsAreNotCoverageFailures() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyWhiteboxCase(admin, "测试失败", "fail-tests",
                Map.of("statements", 0, "branches", 0, "functions", 0));
        long jobId = trigger(admin, caseId, 120);

        HttpResult detail = runWorker(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("category").asText()).isEqualTo("TEST_FAILED");
        assertThat(result.get("testResults").get("failed").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void toolErrorsAreReportedSeparately() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyWhiteboxCase(admin, "工具错误", "tool-error",
                Map.of("statements", 0, "branches", 0, "functions", 0));
        long jobId = trigger(admin, caseId, 120);

        HttpResult detail = runWorker(admin, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ERROR");
        assertThat(detail.body().get("attempts").get(0).get("result")
                .get("category").asText()).isEqualTo("TOOL_ERROR");
    }

    @Test
    @Order(5)
    void timeoutStopsToolProcess() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyWhiteboxCase(admin, "白盒超时", "slow",
                Map.of("statements", 0, "branches", 0, "functions", 0));
        long jobId = trigger(admin, caseId, 1);

        HttpResult detail = runWorker(admin, jobId);

        assertThat(detail.body().get("status").asText()).as(detail.body().toString()).isEqualTo("TIMED_OUT");
        assertThat(detail.body().get("attempts").get(0).get("result").get("reason").asText())
                .isIn("TIMEOUT", "执行超时");
        try (var runs = Files.list(EVIDENCE_DIR)) {
            runs.forEach(run -> assertThat(run.resolve("project")).doesNotExist());
        }
    }

    @Test
    @Order(6)
    void genericReportsAreStoredWithoutExecution() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String jacoco = "<report name=\"demo\"><counter type=\"LINE\" covered=\"8\" missed=\"2\"/></report>";
        HttpResult imported = post(admin, "/api/v1/projects/" + projectId() + "/report-imports",
                Map.of("language", "JAVA", "filename", "jacoco.xml", "content", jacoco));
        assertThat(imported.status()).isEqualTo(201);
        long id = imported.body().get("id").asLong();

        HttpResult list = get(admin, "/api/v1/projects/" + projectId() + "/report-imports");
        assertThat(list.body().get(0).get("language").asText()).isEqualTo("JAVA");
        String content = downloadText(admin, "/api/v1/projects/" + projectId() + "/report-imports/" + id + "/content");
        assertThat(content).contains("covered=\"8\"");
    }
}
