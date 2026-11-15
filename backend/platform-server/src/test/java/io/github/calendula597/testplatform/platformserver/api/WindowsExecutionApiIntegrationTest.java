package io.github.calendula597.testplatform.platformserver.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WindowsExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String NOTE_SECRET = "windows-secret-" + UUID.randomUUID();
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().resolve("../..").normalize();

    private static PostgreSQLContainer postgres;

    private static final String MASTER_KEY = newMasterKey();
    private static final Map<String, String> WORKER_TOKENS = new HashMap<>();
    private static Process APPIUM;

    private static String newMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String databaseUrl() {
        String configured = System.getenv("NATIVE_TEST_DB_URL");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            postgres.start();
        }
        return postgres.getJdbcUrl();
    }

    private static String databaseUsername() {
        if (System.getenv("NATIVE_TEST_DB_URL") == null) {
            databaseUrl();
            return postgres.getUsername();
        }
        return System.getenv("NATIVE_TEST_DB_USERNAME");
    }

    private static String databasePassword() {
        if (System.getenv("NATIVE_TEST_DB_URL") == null) {
            databaseUrl();
            return postgres.getPassword();
        }
        return System.getenv("NATIVE_TEST_DB_PASSWORD");
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", WindowsExecutionApiIntegrationTest::databaseUrl);
        registry.add("spring.datasource.username", WindowsExecutionApiIntegrationTest::databaseUsername);
        registry.add("spring.datasource.password", WindowsExecutionApiIntegrationTest::databasePassword);
        registry.add("app.secrets.master-key", () -> MASTER_KEY);
        registry.add("app.worker.windows-demo-script",
                () -> WORKSPACE.resolve("workers/appium/demo/NativeDemo.ps1").toString());
        registry.add("app.worker.appium-home", () -> WORKSPACE.resolve("workers/appium/.appium").toString());
        registry.add("app.worker.sweep-interval-ms", () -> "30000");
        registry.add("app.evidence.dir", () -> Path.of(System.getProperty("java.io.tmpdir"),
                "windows-evidence-" + UUID.randomUUID()).toString());
    }

    @BeforeAll
    static void startAppium() throws Exception {
        databaseUrl();
        Path node = WORKSPACE.resolve("workers/appium/node_modules/node/bin/node.exe");
        Path appium = WORKSPACE.resolve("workers/appium/node_modules/appium/index.js");
        ProcessBuilder builder = new ProcessBuilder(node.toString(), appium.toString(),
                "--address", "127.0.0.1", "--port", "4723", "--log-level", "error")
                .directory(WORKSPACE.resolve("workers/appium").toFile())
                .redirectErrorStream(true)
                .redirectOutput(Path.of("target", "appium-windows-test.log").toFile());
        builder.environment().put("APPIUM_HOME",
                WORKSPACE.resolve("workers/appium/.appium").toString());
        APPIUM = builder.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = client.send(java.net.http.HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:4723/status"))
                        .timeout(Duration.ofSeconds(2)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Appium 未能启动");
    }

    @AfterAll
    static void stopAppium() {
        if (APPIUM != null) {
            APPIUM.destroyForcibly();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.github.calendula597.testplatform.platformserver.worker.WindowsExecutor windowsExecutor;

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

    private int download(BrowserClient client, String path) {
        return client.rest().get().uri(path).exchange((request, response) -> response.getStatusCode().value());
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
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "windows-project");
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
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "windows-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);
        HttpResult environment = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "Windows 演示环境",
                        "plainVariables", Map.of("note", "wincore-real"),
                        "secrets", Map.of("noteSecret", NOTE_SECRET),
                        "targets", List.of()));
        assertThat(environment.status()).isEqualTo(201);
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Windows Worker", "capabilities", List.of("WINDOWS_NATIVE"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("windows", worker.body().get("token").asText());
    }

    private long createReadyWindowsCase(BrowserClient member, BrowserClient admin, String title,
                                        Map<String, Object> execution) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "WINDOWS_NATIVE");
        create.put("execution", execution);
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "操作原生 Windows 界面", "expected", "界面断言通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "原生 Windows 基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
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

    private io.github.calendula597.testplatform.platformserver.worker.ExecutionControl workerControl(
            HttpResult claimed) {
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        Instant deadline = Instant.now().plusSeconds(claimed.body().get("job").get("timeoutSeconds").asLong());
        return new io.github.calendula597.testplatform.platformserver.worker.ExecutionControl() {
            @Override
            public Instant deadline() {
                return deadline;
            }

            @Override
            public boolean cancelled() {
                HttpResult heartbeat = workerPost(WORKER_TOKENS.get("windows"),
                        "/api/v1/worker/v1/heartbeat", Map.of("attemptIds", List.of(attemptId)));
                for (JsonNode attempt : heartbeat.body().get("attempts")) {
                    if (attempt.get("attemptId").asLong() == attemptId) {
                        return attempt.get("cancelRequested").asBoolean();
                    }
                }
                return false;
            }
        };
    }

    private java.util.Set<Long> nativeProcesses() {
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().command().orElse("")
                        .toLowerCase().contains("powershell"))
                .map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
    }

    private HttpResult runQueuedJob(BrowserClient member, long jobId, BrowserClient cancellingAdmin) throws Exception {
        HttpResult claimed = workerPost(WORKER_TOKENS.get("windows"),
                "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long envId = claimed.body().get("job").get("payload").get("environmentId").asLong();
        var control = workerControl(claimed);
        java.util.Set<Long> before = nativeProcesses();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> running;
        try {
            running = executor.submit(() -> windowsExecutor.execute(caseVersionId, envId, control));
            boolean cancelSent = false;
            while (!running.isDone()) {
                if (cancellingAdmin != null && !cancelSent) {
                    java.util.Set<Long> current = nativeProcesses();
                    current.removeAll(before);
                    if (!current.isEmpty()) {
                        assertThat(post(cancellingAdmin,
                                "/api/v1/system/queue/jobs/" + jobId + "/cancel", Map.of()).status()).isEqualTo(200);
                        cancelSent = true;
                    }
                }
                Thread.sleep(50);
            }
            var outcome = (io.github.calendula597.testplatform.platformserver.worker.WindowsExecutor.WindowsOutcome)
                    running.get();
            java.util.Set<Long> leaked = nativeProcesses();
            leaked.removeAll(before);
            assertThat(leaked).as("结束后不应残留原生演示应用进程").isEmpty();
            Map<String, Object> detail = objectMapper.readValue(
                    objectMapper.writeValueAsString(outcome), Map.class);
            HttpResult reported = workerPost(WORKER_TOKENS.get("windows"),
                    "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                    Map.of("status", outcome.status(), "detail", detail));
            assertThat(reported.status()).as(reported.body().toString()).isEqualTo(200);
            return get(member, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
        } finally {
            executor.shutdownNow();
        }
    }

    private HttpResult runQueuedJob(BrowserClient member, long jobId) throws Exception {
        return runQueuedJob(member, jobId, null);
    }

    @Test
    @Order(1)
    void realWinCoreFlowReportsDriverAndScreenshot() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo-winforms",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "noteInput", "value", "{{note}}"),
                        Map.of("action", "CLICK", "selector", "saveButton"),
                        Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "{{note}}")));
        long caseId = createReadyWindowsCase(member, admin, "WinForms 真实流程", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runQueuedJob(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("appType").asText()).isEqualTo("WINFORMS");
        assertThat(result.get("driverName").asText()).isEqualTo("Wincore");
        assertThat(result.get("driverVersion").asText()).isNotEqualTo("unknown");
        assertThat(result.get("evidence")).isNotEmpty();
        String evidenceId = result.get("evidence").get(0).get("id").asText();
        int downloadStatus = download(member, "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/" + evidenceId);
        assertThat(downloadStatus).isEqualTo(200);
    }

    @Test
    @Order(2)
    void assertionFailureMasksSecretsAndCapturesScreenshot() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo-winforms",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "noteInput", "value", "{{noteSecret}}"),
                        Map.of("action", "CLICK", "selector", "saveButton"),
                        Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "wrong")));
        long caseId = createReadyWindowsCase(member, admin, "Windows 断言失败", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runQueuedJob(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ASSERTION_FAILED");
        assertThat(detail.body().toString()).doesNotContain(NOTE_SECRET);
        assertThat(detail.body().get("attempts").get(0).get("result").get("evidence")).isNotEmpty();
    }

    @Test
    @Order(3)
    void locatorFailureIsExecutionError() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo-winforms",
                "steps", List.of(Map.of("action", "CLICK", "selector", "missingButton")));
        long caseId = createReadyWindowsCase(member, admin, "Windows 定位失败", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runQueuedJob(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("ERROR");
        assertThat(detail.body().get("attempts").get(0).get("result")
                .get("steps").get(0).get("status").asText()).isEqualTo("ERROR");
    }

    @Test
    @Order(4)
    void cancellationClosesSessionAndApplication() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo-winforms",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "noteInput", "value", "cancel"),
                        Map.of("action", "WAIT", "value", "30000")));
        long caseId = createReadyWindowsCase(member, admin, "Windows 取消", execution);
        long jobId = trigger(member, caseId, 30);

        HttpResult detail = runQueuedJob(member, jobId, admin);

        assertThat(detail.body().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @Order(5)
    void timeoutClosesSessionAndApplication() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "app", "demo-winforms",
                "steps", List.of(Map.of("action", "WAIT", "value", "30000")));
        long caseId = createReadyWindowsCase(member, admin, "Windows 超时", execution);
        long jobId = trigger(member, caseId, 2);

        HttpResult detail = runQueuedJob(member, jobId);

        assertThat(detail.body().get("status").asText()).isEqualTo("TIMED_OUT");
    }
}
