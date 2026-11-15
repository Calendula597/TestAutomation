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
class ElectronExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String NOTE_SECRET = "electron-secret-" + UUID.randomUUID();

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
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
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
        HttpResult result = post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "electron-project");
    }

    private long environmentId() {
        return jdbc.queryForObject("SELECT id FROM environment ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    @Order(0)
    void setupUsersProjectEnvironmentAndWorkers() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD)).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "electron-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);

        HttpResult environment = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "Electron 演示环境",
                        "plainVariables", Map.of("note", "hello-electron"),
                        "secrets", Map.of("noteSecret", NOTE_SECRET),
                        "targets", List.of()));
        assertThat(environment.status()).isEqualTo(201);

        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Electron Worker", "capabilities", List.of("ELECTRON"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("electron", worker.body().get("token").asText());
        HttpResult otherWorker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Other Electron Worker", "capabilities", List.of("ELECTRON"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(otherWorker.status()).isEqualTo(201);
        WORKER_TOKENS.put("other", otherWorker.body().get("token").asText());
    }

    private long createReadyElectronCase(BrowserClient member, BrowserClient admin, String title,
                                         Map<String, Object> execution) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "ELECTRON");
        create.put("execution", execution);
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "执行 Electron 渲染流程", "expected", "渲染与主进程断言通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "Electron 执行基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }

    private long trigger(BrowserClient member, long caseId, Integer timeoutSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("caseId", caseId);
        body.put("environmentId", environmentId());
        if (timeoutSeconds != null) {
            body.put("timeoutSeconds", timeoutSeconds);
        }
        HttpResult triggered = post(member, "/api/v1/projects/" + projectId() + "/executions", body);
        assertThat(triggered.status()).as(triggered.body().toString()).isEqualTo(202);
        return triggered.body().get("jobId").asLong();
    }

    private HttpResult executionDetail(BrowserClient member, long jobId) {
        return get(member, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
    }

    private java.util.Set<Long> electronProcesses() {
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().command().orElse("").toLowerCase().contains("electron"))
                .map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
    }

    private HttpResult runWorker(BrowserClient member, long jobId) throws Exception {
        return runWorker(member, jobId, null);
    }

    private HttpResult runWorker(BrowserClient member, long jobId, BrowserClient cancellingAdmin) throws Exception {
        Path entry = Path.of("").toAbsolutePath()
                .resolve("../../workers/electron/src/worker.js").normalize();
        Path log = Path.of("target", "electron-worker-" + jobId + ".log");
        ProcessBuilder builder = new ProcessBuilder("node", entry.toString())
                .directory(entry.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("CONTROL_PLANE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("WORKER_TOKEN", WORKER_TOKENS.get("electron"));
        builder.environment().put("WORKER_ONCE", "1");
        java.util.Set<Long> before = electronProcesses();
        Process process = builder.start();
        boolean cancelSent = false;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            HttpResult detail = null;
            while (System.nanoTime() < deadline) {
                if (cancellingAdmin != null && !cancelSent) {
                    java.util.Set<Long> running = electronProcesses();
                    running.removeAll(before);
                    if (!running.isEmpty()) {
                        long attemptId = jdbc.queryForObject(
                                "SELECT id FROM execution_attempt WHERE job_id = ?", Long.class, jobId);
                        assertThat(workerPost(WORKER_TOKENS.get("other"),
                                "/api/v1/worker/v1/attempts/" + attemptId + "/execution", Map.of()).status())
                                .isEqualTo(403);
                        assertThat(post(cancellingAdmin,
                                "/api/v1/system/queue/jobs/" + jobId + "/cancel", Map.of()).status()).isEqualTo(200);
                        cancelSent = true;
                    }
                }
                detail = executionDetail(member, jobId);
                String status = detail.body().get("status").asText();
                if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                    boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                    String output = new String(java.nio.file.Files.readAllBytes(log));
                    assertThat(exited).as(output).isTrue();
                    assertThat(process.exitValue()).as(output).isEqualTo(0);
                    java.util.Set<Long> leaked = electronProcesses();
                    leaked.removeAll(before);
                    assertThat(leaked).as("结束后不应残留 Electron 应用进程").isEmpty();
                    return detail;
                }
                if (!process.isAlive()) {
                    String output = new String(java.nio.file.Files.readAllBytes(log));
                    throw new AssertionError("Worker 提前退出: " + output);
                }
                Thread.sleep(100);
            }
            throw new AssertionError("等待 Electron 执行结束超时");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Order(1)
    void workerExecutesRendererAndExplicitMainProcessHook() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo",
                "steps", List.of(
                        Map.of("action", "MAIN_HOOK", "name", "getSavedNotes", "value", "[]"),
                        Map.of("action", "FILL", "selector", "#note", "value", "{{note}}"),
                        Map.of("action", "CLICK", "selector", "#save"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#notes", "value", "{{note}}"),
                        Map.of("action", "MAIN_HOOK", "name", "getSavedNotes", "value", "{{note}}")));
        long caseId = createReadyElectronCase(member, admin, "Electron 渲染与主进程", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runWorker(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("steps")).hasSize(5);
        assertThat(result.get("steps").get(4).get("action").asText()).isEqualTo("MAIN_HOOK");
        assertThat(result.get("steps").get(4).get("actual").asText()).contains("hello-electron");

        long secondJobId = trigger(member, caseId, 30);
        HttpResult isolated = runWorker(member, secondJobId);
        assertThat(isolated.body().get("status").asText()).isEqualTo("PASSED");
        assertThat(isolated.body().get("attempts").get(0).get("result")
                .get("steps").get(0).get("actual").asText()).isEqualTo("[]");
    }

    @Test
    @Order(2)
    void failedAssertionMasksSecretsAndCleansApplication() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "#note", "value", "{{noteSecret}}"),
                        Map.of("action", "CLICK", "selector", "#save"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#notes", "value", "wrong-expected")));
        long caseId = createReadyElectronCase(member, admin, "Electron 断言失败与脱敏", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runWorker(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(detail.body().toString()).doesNotContain(NOTE_SECRET);
    }

    @Test
    @Order(3)
    void locatorFailureReturnsExecutionError() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo",
                "steps", List.of(Map.of("action", "CLICK", "selector", "#does-not-exist")));
        long caseId = createReadyElectronCase(member, admin, "Electron 定位失败", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runWorker(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ERROR");
        assertThat(detail.body().get("attempts").get(0).get("result")
                .get("steps").get(0).get("status").asText()).isEqualTo("ERROR");
    }

    @Test
    @Order(4)
    void cancellationStopsApplicationAndReleasesWorker() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "#note", "value", "cancel"),
                        Map.of("action", "WAIT", "value", "30000"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#notes", "value", "never")));
        long caseId = createReadyElectronCase(member, admin, "Electron 取消", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runWorker(member, jobId, admin);

        assertThat(detail.body().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @Order(5)
    void timeoutStopsApplicationAndReleasesWorker() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo",
                "steps", List.of(Map.of("action", "WAIT", "value", "30000")));
        long caseId = createReadyElectronCase(member, admin, "Electron 超时", execution);
        long jobId = trigger(member, caseId, 2);

        HttpResult detail = runWorker(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("TIMED_OUT");
    }
}
