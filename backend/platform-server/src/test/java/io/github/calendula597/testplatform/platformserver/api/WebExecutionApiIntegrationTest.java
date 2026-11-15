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
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Chromium behavior through project APIs and the Worker protocol.
 * Browser processes are checked after normal, cancelled and timed-out runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String OUTSIDER_USERNAME = "member.b";
    private static final String OUTSIDER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String WEB_SECRET = "web-secret-" + UUID.randomUUID();

    private static HttpServer DEMO;
    private static java.util.concurrent.ExecutorService DEMO_EXECUTOR;
    private static int demoPort;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String MASTER_KEY = newMasterKey();
    private static final Path EVIDENCE_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "web-exec-evidence-" + UUID.randomUUID());

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

    private static void respond(HttpServer server, String contextPath, String contentType, String body) {
        server.createContext(contextPath, exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
    }

    @BeforeAll
    static void startDemoSite() throws Exception {
        DEMO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        demoPort = DEMO.getAddress().getPort();

        String formPage = """
                <html><head><title>演示站点</title></head><body>
                <form id="f"><input name="q"/><button type="submit">搜索</button></form>
                <div id="result"></div>
                <script>
                document.getElementById('f').addEventListener('submit', function(e){
                  e.preventDefault();
                  document.getElementById('result').textContent =
                    '已搜索 ' + document.querySelector('input[name=q]').value;
                });
                </script>
                </body></html>""";
        respond(DEMO, "/form", "text/html; charset=utf-8", formPage);
        respond(DEMO, "/iframe-host", "text/html; charset=utf-8", """
                <html><head><title>宿主页</title></head><body>
                <h1>宿主</h1>
                <iframe name="appframe" src="/form" width="400" height="300"></iframe>
                </body></html>""");
        respond(DEMO, "/upload", "text/html; charset=utf-8", """
                <html><body>
                <form action="/upload-receive" method="post" enctype="multipart/form-data">
                <input type="file" name="f"/>
                <button type="submit">上传</button>
                </form>
                </body></html>""");
        DEMO.createContext("/upload-receive", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"code\":\"OK\",\"bytes\":" + body.length + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        respond(DEMO, "/downloads", "text/html; charset=utf-8", """
                <html><body>
                <a id="dl" href="/download">下载</a>
                </body></html>""");
        DEMO.createContext("/download", exchange -> {
            byte[] body = "demo-file-content-42".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"demo.txt\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        DEMO.createContext("/redirect-evil", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example.com/phish");
            exchange.sendResponseHeaders(302, -1);
        });
        DEMO_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(4);
        DEMO.setExecutor(DEMO_EXECUTOR);
        DEMO.start();
    }

    @AfterAll
    static void stopDemoSite() {
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
    io.github.calendula597.testplatform.platformserver.worker.WebExecutor webExecutor;

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

    private record BinaryResult(int status, String contentType, byte[] body) {
    }

    private BinaryResult download(BrowserClient client, String path) {
        return client.rest().get().uri(path).exchange((request, response) -> {
            byte[] bytes = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
            String contentType = response.getHeaders().getFirst("Content-Type");
            return new BinaryResult(response.getStatusCode().value(), contentType, bytes);
        });
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

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "web-project");
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
        for (String user : List.of(MEMBER_USERNAME, OUTSIDER_USERNAME)) {
            String password = user.equals(MEMBER_USERNAME) ? MEMBER_PASSWORD : OUTSIDER_PASSWORD;
            assertThat(post(admin, "/api/v1/system/users",
                    Map.of("username", user, "password", password)).status()).isEqualTo(201);
        }
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "web-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);

        HttpResult env = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "演示环境",
                        "plainVariables", Map.of("baseUrl", "http://127.0.0.1:" + demoPort),
                        "secrets", Map.of("password", WEB_SECRET),
                        "targets", List.of("http://127.0.0.1:" + demoPort)));
        assertThat(env.status()).isEqualTo(201);

        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Web Worker", "capabilities", List.of("WEB"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("web", worker.body().get("token").asText());
    }

    private long createReadyWebCase(BrowserClient member, BrowserClient admin, String title,
                                    Map<String, Object> execution) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "WEB");
        create.put("execution", execution);
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "执行浏览器步骤", "expected", "断言全部通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "T17 基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }

    /** Claim, run the real executor with a worker-side control, report. */
    private void runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl control) {
        HttpResult claimed = workerPost(WORKER_TOKENS.get("web"),
                "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).as("应有排队任务可领取").isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long envId = claimed.body().get("job").get("payload").get("environmentId").asLong();

        var effectiveControl = control == null ? workerControl(claimed) : control;
        java.util.Set<Long> before = playwrightProcesses();
        var outcome = webExecutor.execute(caseVersionId, envId, effectiveControl);
        java.util.Set<Long> leaked = playwrightProcesses();
        leaked.removeAll(before);
        assertThat(leaked).as("执行结束后不应残留浏览器或驱动进程").isEmpty();
        Map<String, Object> detail = objectMapper.readValue(
                objectMapper.writeValueAsString(outcome), Map.class);
        HttpResult reported = workerPost(WORKER_TOKENS.get("web"),
                "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).as(reported.body().toString()).isEqualTo(200);
    }

    private java.util.Set<Long> playwrightProcesses() {
        return ProcessHandle.allProcesses()
                .filter(process -> {
                    String command = process.info().commandLine().orElse("").toLowerCase();
                    return command.contains("playwright")
                            && (command.contains("chrome") || command.contains("node"));
                })
                .map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
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
                HttpResult heartbeat = workerPost(WORKER_TOKENS.get("web"),
                        "/api/v1/worker/v1/heartbeat", Map.of("attemptIds", List.of(attemptId)));
                assertThat(heartbeat.status()).isEqualTo(200);
                for (JsonNode attempt : heartbeat.body().get("attempts")) {
                    if (attempt.get("attemptId").asLong() == attemptId) {
                        return attempt.get("cancelRequested").asBoolean();
                    }
                }
                return false;
            }
        };
    }

    private long triggerExecution(BrowserClient member, long caseId) {
        return triggerExecution(member, caseId, null);
    }

    private long triggerExecution(BrowserClient member, long caseId, Integer timeoutSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("caseId", caseId);
        body.put("environmentId", environmentId());
        if (timeoutSeconds != null) {
            body.put("timeoutSeconds", timeoutSeconds);
        }
        HttpResult triggered = post(member, "/api/v1/projects/" + projectId() + "/executions", body);
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
        JsonNode detail = objectMapper.readTree(result).get("detail");
        return detail.has("detail") ? detail.get("detail") : detail;
    }

    @Test
    @Order(1)
    void browserExecutesNavigationInteractionAndAssertions() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "input[name=q]", "value", "hello"),
                        Map.of("action", "CLICK", "selector", "button[type=submit]"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#result",
                                "value", "已搜索 hello"),
                        Map.of("action", "ASSERT_TITLE", "value", "演示站点")));

        long caseId = createReadyWebCase(member, admin, "表单搜索", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("steps").size()).isEqualTo(4);
        for (JsonNode step : result.get("steps")) {
            assertThat(step.get("status").asText()).isEqualTo("PASSED");
        }

        // Evidence exists for real: trace and final screenshot on disk.
        JsonNode evidence = result.get("evidence");
        assertThat(evidence.size()).isGreaterThanOrEqualTo(2);
        boolean hasTrace = false;
        boolean hasScreenshot = false;
        for (JsonNode entry : evidence) {
            Path file = EVIDENCE_DIR.resolve(entry.get("path").asText());
            assertThat(Files.exists(file)).as("证据文件应存在: " + file).isTrue();
            if ("TRACE".equals(entry.get("kind").asText())) {
                hasTrace = true;
            }
            if ("SCREENSHOT".equals(entry.get("kind").asText())) {
                hasScreenshot = true;
            }
        }
        assertThat(hasTrace).isTrue();
        assertThat(hasScreenshot).isTrue();

        // Authorized download: the member streams the artifact bytes.
        String tracePath = null;
        for (JsonNode entry : evidence) {
            if ("TRACE".equals(entry.get("kind").asText())) {
                tracePath = entry.get("id").asText();
            }
        }
        String evidenceUrl = "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/" + tracePath;
        BinaryResult download = download(member, evidenceUrl);
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.contentType()).startsWith("application/zip");
        assertThat(download.body()).isNotEmpty();

        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        assertThat(download(outsider, evidenceUrl).status()).isEqualTo(404);
        assertThat(download(member, "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/does-not-exist").status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void iframeStepsRunInsideTheFrameContext() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/iframe-host",
                "steps", List.of(
                        Map.of("action", "FRAME", "selector", "iframe[name=appframe]"),
                        Map.of("action", "FILL", "selector", "input[name=q]", "value", "frame"),
                        Map.of("action", "CLICK", "selector", "button[type=submit]"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#result",
                                "value", "已搜索 frame"),
                        Map.of("action", "MAIN_FRAME"),
                        Map.of("action", "ASSERT_TEXT", "selector", "h1", "value", "宿主")));

        long caseId = createReadyWebCase(member, admin, "iframe 内表单", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode steps = latestResult(jobId).get("steps");
        assertThat(steps.size()).isEqualTo(6);
        assertThat(steps.get(4).get("status").asText()).isEqualTo("PASSED");
    }

    @Test
    @Order(3)
    void uploadAndDownloadProduceRealTransfers() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        String fileContent = "上传内容-".repeat(10);
        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/upload",
                "steps", List.of(
                        Map.of("action", "UPLOAD", "selector", "input[type=file]",
                                "value", fileContent),
                        Map.of("action", "CLICK", "selector", "button[type=submit]"),
                        Map.of("action", "ASSERT_TEXT", "selector", "body", "value", "OK")));

        long uploadCase = createReadyWebCase(member, admin, "文件上传", execution);
        long uploadJob = triggerExecution(member, uploadCase);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        assertThat(jobStatus(uploadJob)).as(latestResult(uploadJob).toString()).isEqualTo("PASSED");
        JsonNode uploadStep = latestResult(uploadJob).get("steps").get(0);
        assertThat(uploadStep.get("status").asText()).isEqualTo("PASSED");

        Map<String, Object> downloadExecution = Map.of(
                "startUrl", "{{baseUrl}}/downloads",
                "steps", List.of(
                        Map.of("action", "DOWNLOAD", "selector", "#dl"),
                        Map.of("action", "ASSERT_TEXT", "selector", "body", "value", "下载")));

        long downloadCase = createReadyWebCase(member, admin, "文件下载", downloadExecution);
        long downloadJob = triggerExecution(member, downloadCase);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        assertThat(jobStatus(downloadJob)).isEqualTo("PASSED");
        JsonNode downloadStep = latestResult(downloadJob).get("steps").get(0);
        assertThat(downloadStep.get("status").asText()).isEqualTo("PASSED");
        // The downloaded file is preserved as evidence.
        assertThat(downloadStep.has("evidence")).isTrue();
    }

    @Test
    @Order(4)
    void locatorFailuresAndAssertionFailuresAreClassifiedApart() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> locatorCase = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(Map.of("action", "CLICK", "selector", "#nonexistent")));
        long locatorId = createReadyWebCase(member, admin, "定位失败", locatorCase);
        long locatorJob = triggerExecution(member, locatorId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        // A locator failure is an execution problem, not a business failure.
        assertThat(jobStatus(locatorJob)).isEqualTo("ERROR");
        JsonNode locatorResult = latestResult(locatorJob);
        assertThat(locatorResult.get("steps").get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(locatorResult.get("steps").get(0).get("error").asText()).contains("定位");
        // A failure screenshot exists as evidence.
        Integer screenshots = 0;
        for (JsonNode entry : locatorResult.get("evidence")) {
            if ("SCREENSHOT".equals(entry.get("kind").asText())) {
                screenshots++;
            }
        }
        assertThat(screenshots).isGreaterThanOrEqualTo(1);

        Map<String, Object> assertionCase = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(Map.of("action", "ASSERT_TEXT", "selector", "#result",
                        "value", "错误预期")));
        long assertionId = createReadyWebCase(member, admin, "业务断言失败", assertionCase);
        long assertionJob = triggerExecution(member, assertionId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        // The page worked; the expectation was wrong.
        assertThat(jobStatus(assertionJob)).isEqualTo("ASSERTION_FAILED");
        assertThat(latestResult(assertionJob).get("steps").get(0).get("status").asText())
                .isEqualTo("ASSERTION_FAILED");
    }

    @Test
    @Order(5)
    void allowlistGuardsStartUrlAndEveryNavigation() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> offList = Map.of(
                "startUrl", "http://evil.example.com/login",
                "steps", List.of(Map.of("action", "ASSERT_TITLE", "value", "x")));
        long offListId = createReadyWebCase(member, admin, "越权起始页", offList);
        long offListJob = triggerExecution(member, offListId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        assertThat(jobStatus(offListJob)).isEqualTo("ERROR");
        assertThat(latestResult(offListJob).get("reason").asText()).contains("目标不在白名单");

        Map<String, Object> evilRedirect = Map.of(
                "startUrl", "{{baseUrl}}/redirect-evil",
                "steps", List.of(Map.of("action", "ASSERT_TITLE", "value", "x")));
        long redirectId = createReadyWebCase(member, admin, "外跳导航", evilRedirect);
        long redirectJob = triggerExecution(member, redirectId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());
        assertThat(jobStatus(redirectJob)).isEqualTo("ERROR");
        assertThat(latestResult(redirectJob).get("reason").asText()).contains("目标不在白名单");
    }

    @Test
    @Order(6)
    void cancellationBetweenStepsAbortsAndCleansUp() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "input[name=q]", "value", "x"),
                        Map.of("action", "WAIT", "value", "500"),
                        Map.of("action", "WAIT", "value", "500"),
                        Map.of("action", "WAIT", "value", "500"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#result",
                                "value", "never")));
        long caseId = createReadyWebCase(member, admin, "取消执行", execution);
        long jobId = triggerExecution(member, caseId);

        // Claim, then cancel from the control plane like a user would.
        HttpResult claimed = workerPost(WORKER_TOKENS.get("web"),
                "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        assertThat(call(admin, HttpMethod.POST,
                "/api/v1/system/queue/jobs/" + jobId + "/cancel", Map.of()).status()).isEqualTo(200);

        var control = workerControl(claimed);
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long envId = claimed.body().get("job").get("payload").get("environmentId").asLong();
        java.util.Set<Long> before = playwrightProcesses();
        var outcome = webExecutor.execute(caseVersionId, envId, control);
        java.util.Set<Long> leaked = playwrightProcesses();
        leaked.removeAll(before);
        assertThat(leaked).as("取消后不应残留浏览器或驱动进程").isEmpty();
        assertThat(outcome.status()).isEqualTo("CANCELLED");
        assertThat(outcome.steps().size()).as("取消后不再推进剩余步骤").isLessThanOrEqualTo(4);

        Map<String, Object> detail = objectMapper.readValue(
                objectMapper.writeValueAsString(outcome), Map.class);
        HttpResult reported = workerPost(WORKER_TOKENS.get("web"),
                "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).isEqualTo(200);
        assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
        String attemptStatus = jdbc.queryForObject(
                "SELECT status FROM execution_attempt WHERE id = ?", String.class, attemptId);
        assertThat(attemptStatus).isEqualTo("CANCELLED");
    }

    @Test
    @Order(7)
    void executorDeadlineAbortsTheRunAsTimedOut() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(
                        Map.of("action", "WAIT", "value", "3000"),
                        Map.of("action", "WAIT", "value", "3000"),
                        Map.of("action", "ASSERT_TITLE", "value", "演示站点")));
        long caseId = createReadyWebCase(member, admin, "执行超时", execution);
        long jobId = triggerExecution(member, caseId, 3);

        // A null control asks the test Worker to derive its deadline and
        // cancellation state from the claimed job and heartbeat protocol.
        runQueuedJob(null);
        assertThat(jobStatus(jobId)).isEqualTo("TIMED_OUT");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("reason").asText()).contains("超时");
        // Browser evidence was still written before the abort.
        assertThat(result.get("evidence").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(8)
    void secretVariablesResolveAtExecutionAndAreMaskedInResults() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<String, Object> execution = Map.of(
                "startUrl", "{{baseUrl}}/form",
                "steps", List.of(
                        Map.of("action", "FILL", "selector", "input[name=q]", "value", "{{password}}"),
                        Map.of("action", "CLICK", "selector", "button[type=submit]"),
                        Map.of("action", "ASSERT_TEXT", "selector", "#result",
                                "value", "已搜索 {{password}}")));
        long caseId = createReadyWebCase(member, admin, "密钥变量", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob(io.github.calendula597.testplatform.platformserver.worker.ExecutionControl.none());

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        assertThat(latestResult(jobId).toString()).doesNotContain(WEB_SECRET);
        HttpResult detail = get(member, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
        assertThat(detail.body().toString()).doesNotContain(WEB_SECRET);
    }

    @Test
    @Order(9)
    void evidenceDownloadRejectsTraversalInStoredMetadata() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long jobId = jdbc.queryForObject("SELECT id FROM execution_job ORDER BY id LIMIT 1", Long.class);
        Path outside = EVIDENCE_DIR.resolveSibling("outside-" + UUID.randomUUID() + ".txt");
        Files.writeString(outside, "not-downloadable");
        try {
            String malicious = "{\"detail\":{\"evidence\":[{\"id\":\"traversal\","
                    + "\"kind\":\"TRACE\",\"path\":\"../" + outside.getFileName() + "\"}]}}";
            jdbc.update("UPDATE execution_attempt SET result = ? WHERE job_id = ?", malicious, jobId);
            BinaryResult response = download(member, "/api/v1/projects/" + projectId()
                    + "/executions/" + jobId + "/evidence/traversal");
            assertThat(response.status()).isEqualTo(404);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("not-downloadable");
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
