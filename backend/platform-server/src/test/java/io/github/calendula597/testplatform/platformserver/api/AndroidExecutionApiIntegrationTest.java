package io.github.calendula597.testplatform.platformserver.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

@EnabledIfSystemProperty(named = "android.e2e", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AndroidExecutionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.android";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().resolve("../..").normalize();
    private static final Path SDK = Path.of(System.getProperty("android.sdk",
            WORKSPACE.resolve("tools/android-sdk").toString()));
    private static final Path ADB = SDK.resolve("platform-tools/adb.exe");
    private static final String SERIAL = emulatorSerial();
    private static final String MASTER_KEY = newMasterKey();
    private static String workerToken;
    private static Process appium;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static String newMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String emulatorSerial() {
        try {
            String devices = adb("devices", "-l");
            return devices.lines().filter(line -> line.startsWith("emulator-") && line.contains(" device "))
                    .map(line -> line.split("\\s+")[0]).findFirst()
                    .orElseThrow(() -> new AssertionError("请先启动已安装系统镜像的 Android 模拟器"));
        } catch (Exception e) {
            throw new AssertionError("无法查询 Android 模拟器", e);
        }
    }

    private static String adb(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(ADB.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("ADB 命令超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError(output);
        }
        return output.trim();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.secrets.master-key", () -> MASTER_KEY);
        registry.add("app.worker.android-sdk", SDK::toString);
        registry.add("app.worker.android-demo-apk",
                () -> WORKSPACE.resolve("workers/appium/demo/android/build/demo.apk").toString());
        registry.add("app.worker.appium-base-url", () -> "http://127.0.0.1:4724");
        registry.add("app.worker.appium-home", () -> WORKSPACE.resolve("workers/appium/.appium").toString());
        registry.add("app.evidence.dir", () -> Path.of(System.getProperty("java.io.tmpdir"),
                "android-evidence-" + UUID.randomUUID()).toString());
    }

    @BeforeAll
    static void startAppium() throws Exception {
        assertThat(Files.isRegularFile(WORKSPACE.resolve("workers/appium/demo/android/build/demo.apk"))).isTrue();
        assertThat(adb("-s", SERIAL, "shell", "getprop", "sys.boot_completed")).isEqualTo("1");
        ProcessBuilder builder = new ProcessBuilder(
                WORKSPACE.resolve("workers/appium/node_modules/node/bin/node.exe").toString(),
                WORKSPACE.resolve("workers/appium/node_modules/appium/index.js").toString(),
                "--address", "127.0.0.1", "--port", "4724", "--log-level", "error",
                "--allow-insecure", "*:session_discovery")
                .directory(WORKSPACE.resolve("workers/appium").toFile())
                .redirectErrorStream(true)
                .redirectOutput(Path.of("target", "appium-android-test.log").toFile());
        builder.environment().put("APPIUM_HOME", WORKSPACE.resolve("workers/appium/.appium").toString());
        builder.environment().put("ANDROID_HOME", SDK.toString());
        builder.environment().put("ANDROID_SDK_ROOT", SDK.toString());
        appium = builder.start();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = client.send(java.net.http.HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:4724/status"))
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
    static void stopAppiumAndEmulator() {
        if (appium != null) {
            appium.destroyForcibly();
        }
        try {
            adb("-s", SERIAL, "emu", "kill");
        } catch (Exception | AssertionError ignored) {
            // The emulator closes the ADB control channel during shutdown.
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.github.calendula597.testplatform.platformserver.worker.AndroidExecutor androidExecutor;

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
            byte[] raw = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
            return new HttpResult(response.getStatusCode().value(), objectMapper.readTree(
                    new String(raw).isEmpty() ? "{}" : new String(raw)));
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

    private HttpResult workerPost(String path, Object body) {
        return send(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer " + workerToken).build()
                .post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body));
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password)).status()).isEqualTo(200);
        return client;
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = 'android-project'", Long.class);
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
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "android-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "Android 14 模拟器", "plainVariables", Map.of("note", "appium-real"),
                        "secrets", Map.of(), "targets", List.of())).status()).isEqualTo(201);
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Android Worker", "capabilities", List.of("ANDROID"),
                        "version", "0.1.0", "maxConcurrency", 2));
        assertThat(worker.status()).isEqualTo(201);
        workerToken = worker.body().get("token").asText();
    }

    private long createReadyCase(BrowserClient member, BrowserClient admin, String title,
                                 List<Map<String, Object>> steps) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "ANDROID");
        create.put("execution", Map.of("app", "demo-android", "deviceSerial", SERIAL, "reset", true, "steps", steps));
        create.put("steps", List.of(Map.of("stepOrder", 1, "action", "操作 Android 演示应用", "expected", "断言通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/" + caseId + "/review/publish",
                Map.of("basis", "Android 模拟器基线", "expectedVersion", 1)).status()).isEqualTo(201);
        return caseId;
    }

    private HttpResult claim(BrowserClient member, long caseId) {
        HttpResult triggered = post(member, "/api/v1/projects/" + projectId() + "/executions",
                Map.of("caseId", caseId, "environmentId", environmentId()));
        assertThat(triggered.status()).isEqualTo(202);
        HttpResult claimed = workerPost("/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).isEqualTo(200);
        assertThat(claimed.body().get("job").get("id").asLong()).isEqualTo(triggered.body().get("jobId").asLong());
        return claimed;
    }

    private HttpResult executeClaimed(BrowserClient member, HttpResult claimed) {
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long environmentId = claimed.body().get("job").get("payload").get("environmentId").asLong();
        Instant deadline = Instant.now().plusSeconds(claimed.body().get("job").get("timeoutSeconds").asLong());
        var outcome = androidExecutor.execute(caseVersionId, environmentId,
                new io.github.calendula597.testplatform.platformserver.worker.ExecutionControl() {
                    public Instant deadline() { return deadline; }
                    public boolean cancelled() { return false; }
                });
        Map<String, Object> detail = objectMapper.convertValue(outcome, Map.class);
        HttpResult reported = workerPost("/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).as(reported.body().toString()).isEqualTo(200);
        return get(member, "/api/v1/projects/" + projectId() + "/executions/"
                + claimed.body().get("job").get("id").asLong());
    }

    @Test
    @Order(1)
    void realUiAutomator2InstallsLaunchesAssertsAndReportsDevice() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyCase(member, admin, "Android 真实安装与操作", List.of(
                Map.of("action", "FILL", "selector", "noteInput", "value", "{{note}}"),
                Map.of("action", "CLICK", "selector", "saveButton"),
                Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "Saved: {{note}}")));

        HttpResult detail = executeClaimed(member, claim(member, caseId));

        assertThat(detail.body().get("status").asText()).as(detail.body().toString()).isEqualTo("PASSED");
        JsonNode result = detail.body().get("attempts").get(0).get("result");
        assertThat(result.get("appType").asText()).isEqualTo("ANDROID_APK");
        assertThat(result.get("driverName").asText()).isEqualTo("UiAutomator2");
        assertThat(result.get("driverVersion").asText()).isEqualTo("9.1.0");
        assertThat(result.get("deviceSerial").asText()).isEqualTo(SERIAL);
        assertThat(result.get("deviceModel").asText()).isNotEqualTo("unknown");
        assertThat(result.get("platformVersion").asText()).isEqualTo("14");
        assertThat(result.get("evidence")).isNotEmpty();
        String evidenceId = result.get("evidence").get(0).get("id").asText();
        byte[] screenshot = member.rest().get().uri("/api/v1/projects/" + projectId() + "/executions/"
                        + detail.body().get("id").asLong() + "/evidence/" + evidenceId)
                .retrieve().body(byte[].class);
        assertThat(screenshot).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        JsonNode sessions = objectMapper.readTree(HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:4724/appium/sessions")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        assertThat(sessions.get("value")).isEmpty();
    }

    @Test
    @Order(2)
    void explicitResetDoesNotReuseApplicationData() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyCase(member, admin, "Android 显式重置", List.of(
                Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "Ready")));

        HttpResult detail = executeClaimed(member, claim(member, caseId));

        assertThat(detail.body().get("status").asText()).as(detail.body().toString()).isEqualTo("PASSED");
        assertThat(adb("-s", SERIAL, "shell", "pm", "list", "packages", "io.github.calendula597.demo"))
                .doesNotContain("io.github.calendula597.demo");
    }

    @Test
    @Order(3)
    void deviceIsExclusiveAcrossConcurrentWorkerAttempts() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyCase(member, admin, "Android 设备互斥", List.of(
                Map.of("action", "WAIT", "value", "3000"),
                Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "Ready")));
        HttpResult first = claim(member, caseId);
        HttpResult second = claim(member, caseId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResult> running = pool.submit(() -> executeClaimed(member, first));
            Thread.sleep(500);
            Future<HttpResult> contending = pool.submit(() -> executeClaimed(member, second));
            List<HttpResult> results = List.of(running.get(120, TimeUnit.SECONDS),
                    contending.get(120, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.body().get("status").asText())
                    .contains("PASSED", "ERROR");
            assertThat(results.stream().filter(result -> result.body().get("status").asText().equals("ERROR"))
                    .findFirst().orElseThrow().body().toString()).contains("设备正被其他执行占用");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(4)
    void disconnectedDeviceIsExecutionError() throws Exception {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long caseId = createReadyCase(member, admin, "Android 设备断连", List.of(
                Map.of("action", "ASSERT_TEXT", "selector", "statusLabel", "value", "Ready"),
                Map.of("action", "WAIT", "value", "30000")));
        HttpResult claimed = claim(member, caseId);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResult> running = pool.submit(() -> executeClaimed(member, claimed));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            boolean launched = false;
            while (System.nanoTime() < deadline && !launched) {
                try {
                    launched = !adb("-s", SERIAL, "shell", "pidof", "io.github.calendula597.demo").isEmpty();
                } catch (AssertionError ignored) {
                }
                Thread.sleep(250);
            }
            assertThat(launched).isTrue();
            try {
                adb("-s", SERIAL, "emu", "kill");
            } catch (AssertionError ignored) {
            }
            HttpResult detail = running.get(120, TimeUnit.SECONDS);
            assertThat(detail.body().get("status").asText()).isEqualTo("ERROR");
            assertThat(detail.body().get("attempts").get(0).get("result").get("reason").asText()).contains("设备");
        } finally {
            pool.shutdownNow();
        }
    }
}
