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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker protocol seams (T14): registration with scoped credentials and
 * real capability declarations, PostgreSQL-backed persistent queue with
 * atomic claiming, leases, heartbeats and attempt validation, and real
 * dependency diagnostics with explicit missing-dependency states.
 *
 * Lost-lease recovery, cancellation and timeout are exercised against the
 * real sweeper with short test intervals. The test acts as the Worker HTTP
 * client; claim atomicity, terminal-report idempotency and stale-report
 * rejection are asserted through the public protocol only.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkerQueueApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Short intervals so the real sweeper exercises lost leases and
        // timeouts quickly without flaky margins.
        registry.add("app.worker.sweep-interval-ms", () -> "300");
        registry.add("app.worker.lease-seconds", () -> "30");
        registry.add("app.worker.offline-after-seconds", () -> "5");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.github.calendula597.testplatform.platformserver.worker.DiagnosticRunner diagnosticRunner;

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

    /** Worker-style client: Bearer credential, no session cookies. */
    private RestClient workerClient(String token) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().build()));
        if (token != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
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

    private HttpResult call(RestClient client, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        if (body != null) {
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        return send(spec);
    }

    /** Browser-style call: carries the session CSRF token on writes. */
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
        return call(workerClient(token), HttpMethod.POST, path, body);
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client.rest(), HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult result = call(client, HttpMethod.POST, "/api/v1/auth/login",
                Map.of("username", username, "password", password));
        assertThat(result.status()).isEqualTo(200);
        return client;
    }

    private void awaitCondition(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + description);
    }

    private static final Map<Long, String> WORKER_TOKENS = new java.util.HashMap<>();

    private long registerWorker(BrowserClient admin, String name, List<String> capabilities) {
        HttpResult created = post(admin, "/api/v1/system/workers",
                Map.of("name", name, "capabilities", capabilities, "version", "0.1.0",
                        "maxConcurrency", 2));
        assertThat(created.status())
                .as("注册 Worker %s", name).isEqualTo(201);
        long id = created.body().get("id").asLong();
        WORKER_TOKENS.put(id, created.body().get("token").asText());
        return id;
    }

    private long triggerDiagnostic(BrowserClient admin, long workerId, Integer leaseSeconds,
                                   Integer timeoutSeconds) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (leaseSeconds != null) {
            body.put("leaseSeconds", leaseSeconds);
        }
        if (timeoutSeconds != null) {
            body.put("timeoutSeconds", timeoutSeconds);
        }
        HttpResult triggered = post(admin, "/api/v1/system/workers/" + workerId + "/diagnostics", body);
        assertThat(triggered.status()).isEqualTo(202);
        return triggered.body().get("jobId").asLong();
    }

    record ClaimOutcome(int status, long jobId, long attemptId, int attemptNumber, String leaseToken) {
    }

    private ClaimOutcome claim(String token) {
        HttpResult result = workerPost(token, "/api/v1/worker/v1/jobs/claim", Map.of());
        if (result.status() == 204) {
            return new ClaimOutcome(204, 0, 0, 0, null);
        }
        assertThat(result.status()).as("claim 应返回 200 或 204").isEqualTo(200);
        return new ClaimOutcome(200,
                result.body().get("job").get("id").asLong(),
                result.body().get("attempt").get("id").asLong(),
                result.body().get("attempt").get("attemptNumber").asInt(),
                result.body().get("attempt").get("leaseToken").asText());
    }

    @Test
    @Order(0)
    void setupUsers() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap.rest(), HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD)).status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void registrationIssuesScopedCredentialOnceAndDeclaresCapabilities() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        HttpResult created = post(admin, "/api/v1/system/workers",
                Map.of("name", "主 Worker", "capabilities", List.of("DIAGNOSTIC", "API", "WEB"),
                        "version", "0.1.0", "maxConcurrency", 2));
        assertThat(created.status()).isEqualTo(201);
        long worker1 = created.body().get("id").asLong();
        String token = created.body().get("token").asText();
        WORKER_TOKENS.put(worker1, token);

        // The credential is scoped and shown once: prefixed, long, random.
        assertThat(token).startsWith("wpk_");
        assertThat(token.length()).isGreaterThanOrEqualTo(30);

        // Only a hash is stored; plaintext never lands in the database.
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM worker WHERE id = ?",
                String.class, worker1);
        assertThat(storedHash).isNotEqualTo(token).hasSize(64);

        HttpResult second = post(admin, "/api/v1/system/workers",
                Map.of("name", "备用 Worker", "capabilities", List.of("DIAGNOSTIC"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(second.status()).isEqualTo(201);
        long worker2 = second.body().get("id").asLong();
        WORKER_TOKENS.put(worker2, second.body().get("token").asText());

        // A duplicate name is rejected.
        assertThat(post(admin, "/api/v1/system/workers",
                Map.of("name", "主 Worker", "capabilities", List.of("DIAGNOSTIC"),
                        "version", "0.1.0", "maxConcurrency", 1)).status()).isEqualTo(400);

        // ADMIN-only surface.
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        assertThat(post(member, "/api/v1/system/workers",
                Map.of("name", "越权 Worker", "capabilities", List.of("DIAGNOSTIC"))).status()).isEqualTo(403);

        // Listed with declared capabilities, version, concurrency and a
        // derived visibility status before the first heartbeat.
        JsonNode listed = get(admin, "/api/v1/system/workers").body();
        assertThat(listed).hasSize(2);
        JsonNode view1 = listed.get(0);
        assertThat(view1.get("name").asText()).isEqualTo("主 Worker");
        assertThat(view1.get("capabilities").toString()).contains("DIAGNOSTIC", "API", "WEB");
        assertThat(view1.get("version").asText()).isEqualTo("0.1.0");
        assertThat(view1.get("maxConcurrency").asInt()).isEqualTo(2);
        assertThat(view1.get("status").asText()).isEqualTo("NEVER_SEEN");

        // The worker credential cannot touch admin surfaces.
        HttpResult workerOnAdminApi = call(workerClient(token), HttpMethod.GET,
                "/api/v1/system/workers", null);
        assertThat(workerOnAdminApi.status()).isEqualTo(401);

        // Registration is audited without credential material.
        Integer tokenLeaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE detail LIKE '%wpk_%'", Integer.class);
        assertThat(tokenLeaks).isZero();
    }

    @Test
    @Order(2)
    void protocolRejectsUnknownCredentialsAndIdleWorkersGetNoJobs() {
        long worker1 = WORKER_TOKENS.keySet().stream().min(Long::compare).orElseThrow();

        assertThat(workerPost("wpk_invalid", "/api/v1/worker/v1/jobs/claim", Map.of()).status())
                .isEqualTo(401);
        assertThat(call(workerClient(null), HttpMethod.POST, "/api/v1/worker/v1/jobs/claim", Map.of()).status())
                .isEqualTo(401);

        // No diagnostic job queued yet: capable worker claims nothing.
        assertThat(claim(WORKER_TOKENS.get(worker1)).status()).isEqualTo(204);

        // A worker without the DIAGNOSTIC capability never claims diagnostics.
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult webWorker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Web Worker", "capabilities", List.of("WEB"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(webWorker.status()).isEqualTo(201);
        long webWorkerId = webWorker.body().get("id").asLong();
        WORKER_TOKENS.put(webWorkerId, webWorker.body().get("token").asText());

        triggerDiagnostic(admin, worker1, null, null);
        assertThat(claim(WORKER_TOKENS.get(webWorkerId)).status()).isEqualTo(204);

        // The capable worker claims it and finishes it so the queue is
        // clean for later tests.
        ClaimOutcome capable = claim(WORKER_TOKENS.get(worker1));
        assertThat(capable.status()).isEqualTo(200);
        HttpResult done = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + capable.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(done.status()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void claimingIsAtomicWithLeaseAndAttemptRecords() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");
        long jobA = triggerDiagnostic(admin, worker1, null, null);
        long jobB = triggerDiagnostic(admin, worker1, null, null);

        // Two concurrent claims race for two queued jobs: each claim takes
        // a distinct job, and no job is ever handed out twice.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ClaimOutcome> first = pool.submit(() -> claim(WORKER_TOKENS.get(worker1)));
        Future<ClaimOutcome> second = pool.submit(() -> claim(WORKER_TOKENS.get(worker1)));
        ClaimOutcome a = first.get(10, TimeUnit.SECONDS);
        ClaimOutcome b = second.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(a.status()).isEqualTo(200);
        assertThat(b.status()).isEqualTo(200);
        assertThat(a.jobId()).as("并发领取不得重复分发同一任务").isNotEqualTo(b.jobId());
        assertThat(List.of(a.jobId(), b.jobId())).containsExactlyInAnyOrder(jobA, jobB);
        for (long jobId : List.of(jobA, jobB)) {
            Integer attemptRows = jdbc.queryForObject(
                    "SELECT count(*) FROM execution_attempt WHERE job_id = ?", Integer.class, jobId);
            assertThat(attemptRows).isEqualTo(1);
            String jobStatus = jdbc.queryForObject(
                    "SELECT status FROM execution_job WHERE id = ?", String.class, jobId);
            assertThat(jobStatus).isEqualTo("RUNNING");
        }
        ClaimOutcome won = a;
        assertThat(won.leaseToken()).startsWith("wpk_lease_");

        // Heartbeat renews the lease: expiry moves strictly forward.
        java.time.OffsetDateTime before = jdbc.queryForObject(
                "SELECT lease_expires_at FROM execution_attempt WHERE id = ?",
                java.time.OffsetDateTime.class, won.attemptId());
        HttpResult beat = workerPost(WORKER_TOKENS.get(worker1), "/api/v1/worker/v1/heartbeat",
                Map.of("attemptIds", List.of(won.attemptId())));
        assertThat(beat.status()).isEqualTo(200);
        java.time.OffsetDateTime after = jdbc.queryForObject(
                "SELECT lease_expires_at FROM execution_attempt WHERE id = ?",
                java.time.OffsetDateTime.class, won.attemptId());
        assertThat(after).isAfter(before);

        // A heartbeat for someone else's attempt is rejected.
        long worker2 = workerIdByName("备用 Worker");
        HttpResult foreign = workerPost(WORKER_TOKENS.get(worker2), "/api/v1/worker/v1/heartbeat",
                Map.of("attemptIds", List.of(won.attemptId())));
        assertThat(foreign.status()).isEqualTo(403);

        // Close both attempts so no dangling RUNNING job leaks into later tests.
        ClaimOutcome other = a == won ? b : a;
        for (ClaimOutcome outcome : List.of(won, other)) {
            HttpResult finish = workerPost(WORKER_TOKENS.get(worker1),
                    "/api/v1/worker/v1/attempts/" + outcome.attemptId() + "/result",
                    Map.of("status", "PASSED"));
            assertThat(finish.status()).isEqualTo(200);
        }
    }

    @Test
    @Order(4)
    void resultValidationBlocksDuplicateAndForeignReports() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");
        long jobId = triggerDiagnostic(admin, worker1, null, null);

        ClaimOutcome won = claim(WORKER_TOKENS.get(worker1));
        assertThat(won.status()).isEqualTo(200);

        // A foreign worker cannot report on this attempt.
        long otherWorker = WORKER_TOKENS.keySet().stream()
                .filter(id -> id != worker1).findFirst().orElseThrow();
        HttpResult foreign = workerPost(WORKER_TOKENS.get(otherWorker),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(foreign.status()).isEqualTo(403);

        // First report lands and moves the job to a terminal state.
        HttpResult reported = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "PASSED", "detail", Map.of("note", "diagnostic done")));
        assertThat(reported.status()).isEqualTo(200);
        assertThat(reported.body().get("jobStatus").asText()).isEqualTo("PASSED");

        // Duplicate report for the same attempt is rejected — no overwrite.
        HttpResult duplicate = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "ERROR"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM execution_job WHERE id = ?",
                String.class, jobId)).isEqualTo("PASSED");
    }

    private long workerIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM worker WHERE name = ?", Long.class, name);
    }

    @Test
    @Order(5)
    void lostLeaseRequeuesDiagnosticsAndOldResultsCannotOverwrite() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");
        long worker2 = workerIdByName("备用 Worker");
        // Short lease so the real sweeper reaps it quickly.
        long jobId = triggerDiagnostic(admin, worker1, 2, null);

        ClaimOutcome lost = claim(WORKER_TOKENS.get(worker1));
        assertThat(lost.status()).isEqualTo(200);

        // Worker1 goes silent; the sweeper must release the job.
        awaitCondition(Duration.ofSeconds(8),
                "失联租约被回收且诊断任务重新入队",
                () -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM execution_job WHERE id = ?", String.class, jobId);
                    return "QUEUED".equals(status);
                });
        assertThat(jdbc.queryForObject(
                "SELECT status FROM execution_attempt WHERE id = ?", String.class, lost.attemptId()))
                .isEqualTo("ERROR");

        // The diagnostic is pinned to its worker: the recovered worker
        // claims again and a new attempt is created.
        ClaimOutcome retry = claim(WORKER_TOKENS.get(worker1));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.jobId()).isEqualTo(jobId);
        assertThat(retry.attemptNumber()).isEqualTo(2);

        // Another worker cannot report on attempt 2 — foreign ownership.
        HttpResult foreign = workerPost(WORKER_TOKENS.get(worker2),
                "/api/v1/worker/v1/attempts/" + retry.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(foreign.status()).isEqualTo(403);

        // The lost worker cannot renew its dead lease or overwrite its own
        // closed attempt — stale results are rejected, never applied.
        HttpResult deadBeat = workerPost(WORKER_TOKENS.get(worker1), "/api/v1/worker/v1/heartbeat",
                Map.of("attemptIds", List.of(lost.attemptId())));
        assertThat(deadBeat.status()).isEqualTo(403);
        HttpResult stale = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + lost.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(stale.status()).isEqualTo(409);

        HttpResult finish = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + retry.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(finish.status()).isEqualTo(200);
    }

    @Test
    @Order(6)
    void timeoutMarksAttemptsAndJobsExplicitly() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");
        // Timeout shorter than the lease: control-plane timeout wins.
        long jobId = triggerDiagnostic(admin, worker1, 30, 2);

        ClaimOutcome won = claim(WORKER_TOKENS.get(worker1));
        assertThat(won.status()).isEqualTo(200);
        assertThat(won.jobId()).isEqualTo(jobId);

        awaitCondition(Duration.ofSeconds(8), "超时被标记为 TIMED_OUT",
                () -> "TIMED_OUT".equals(jdbc.queryForObject(
                        "SELECT status FROM execution_job WHERE id = ?", String.class, jobId)));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM execution_attempt WHERE id = ?", String.class, won.attemptId()))
                .isEqualTo("TIMED_OUT");

        HttpResult terminalHeartbeat = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/heartbeat", Map.of("attemptIds", List.of(won.attemptId())));
        assertThat(terminalHeartbeat.status()).isEqualTo(200);
        assertThat(terminalHeartbeat.body().get("attempts").get(0).get("status").asText())
                .isEqualTo("TIMED_OUT");

        String originalResult = jdbc.queryForObject(
                "SELECT result FROM execution_attempt WHERE id = ?", String.class, won.attemptId());
        HttpResult timeout = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "TIMED_OUT", "detail", Map.of("reason", "执行超时")));
        assertThat(timeout.status()).isEqualTo(200);
        assertThat(timeout.body().get("jobStatus").asText()).isEqualTo("TIMED_OUT");
        assertThat(jdbc.queryForObject(
                "SELECT result FROM execution_attempt WHERE id = ?", String.class, won.attemptId()))
                .isEqualTo(originalResult);

        HttpResult late = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(late.status()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void cancellationWorksForQueuedAndRunningJobs() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");

        // Queued job cancels immediately and is never claimable.
        long queuedId = triggerDiagnostic(admin, worker1, null, null);
        HttpResult cancelled = call(admin, HttpMethod.POST,
                "/api/v1/system/queue/jobs/" + queuedId + "/cancel", Map.of());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(claim(WORKER_TOKENS.get(worker1)).status()).isEqualTo(204);

        // Running job: the heartbeat surfaces the cancel request, the
        // worker reports CANCELLED, and the attempt closes as CANCELLED.
        long runningId = triggerDiagnostic(admin, worker1, 30, null);
        ClaimOutcome won = claim(WORKER_TOKENS.get(worker1));
        assertThat(won.status()).isEqualTo(200);
        assertThat(won.jobId()).isEqualTo(runningId);

        HttpResult heartbeat = workerPost(WORKER_TOKENS.get(worker1), "/api/v1/worker/v1/heartbeat",
                Map.of("attemptIds", List.of(won.attemptId())));
        assertThat(heartbeat.status()).isEqualTo(200);
        assertThat(heartbeat.body().get("attempts").get(0).get("cancelRequested").asBoolean()).isFalse();

        assertThat(call(admin, HttpMethod.POST,
                "/api/v1/system/queue/jobs/" + runningId + "/cancel", Map.of()).status()).isEqualTo(200);

        HttpResult afterCancel = workerPost(WORKER_TOKENS.get(worker1), "/api/v1/worker/v1/heartbeat",
                Map.of("attemptIds", List.of(won.attemptId())));
        assertThat(afterCancel.body().get("attempts").get(0).get("cancelRequested").asBoolean()).isTrue();

        // After cancellation only a CANCELLED result is accepted.
        HttpResult wrongFinish = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "PASSED"));
        assertThat(wrongFinish.status()).isEqualTo(409);

        HttpResult abort = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "CANCELLED"));
        assertThat(abort.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM execution_attempt WHERE id = ?",
                String.class, won.attemptId())).isEqualTo("CANCELLED");

        // Cancelling a terminal job is rejected.
        HttpResult again = call(admin, HttpMethod.POST,
                "/api/v1/system/queue/jobs/" + runningId + "/cancel", Map.of());
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    @Order(8)
    void diagnosticsRunRealDependencyChecksAndAreObservable() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long worker1 = workerIdByName("主 Worker");
        long jobId = triggerDiagnostic(admin, worker1, 30, null);

        ClaimOutcome won = claim(WORKER_TOKENS.get(worker1));
        assertThat(won.status()).isEqualTo(200);
        // The job payload names the real checks to run.
        HttpResult jobDetail = get(admin, "/api/v1/system/queue/jobs/" + jobId);
        assertThat(jobDetail.body().get("payload").get("checks").toString())
                .contains("jvm", "model-provider", "chromium", "appium");

        // The worker side executes the real production probes.
        List<String> checkNames = new ArrayList<>();
        jobDetail.body().get("payload").get("checks").forEach(c -> checkNames.add(c.asText()));
        var outcome = diagnosticRunner.run(checkNames);

        List<Map<String, Object>> checks = outcome.checks().stream()
                .map(c -> Map.<String, Object>of(
                        "name", c.name(), "ok", c.ok(), "detail", c.detail()))
                .toList();

        HttpResult reported = workerPost(WORKER_TOKENS.get(worker1),
                "/api/v1/worker/v1/attempts/" + won.attemptId() + "/result",
                Map.of("status", "PASSED", "checks", checks));
        assertThat(reported.status()).isEqualTo(200);

        // Real, explicit results: the JVM probe passes with a version
        // detail; every probe records a truthful present-or-missing state.
        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM worker_diagnostic WHERE worker_id = ?", Integer.class, worker1);
        assertThat(stored).isEqualTo(1);
        String checksJson = jdbc.queryForObject(
                "SELECT checks FROM worker_diagnostic WHERE worker_id = ?", String.class, worker1);
        assertThat(checksJson).contains("\"jvm\"").contains("\"model-provider\"");

        JsonNode diagnostics = get(admin, "/api/v1/system/workers/" + worker1 + "/diagnostics").body();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(diagnostics.get(0).get("checks").size()).isGreaterThanOrEqualTo(4);
        for (JsonNode check : diagnostics.get(0).get("checks")) {
            assertThat(check.get("detail").asText()).isNotBlank();
        }
    }

    @Test
    @Order(9)
    void queueObservationAndStatisticsDistinguishStates() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        JsonNode jobs = get(admin, "/api/v1/system/queue/jobs").body();
        assertThat(jobs.size()).isGreaterThanOrEqualTo(6);
        for (JsonNode job : jobs) {
            assertThat(job.get("status").asText()).isIn(
                    "QUEUED", "RUNNING", "PASSED", "ASSERTION_FAILED", "ERROR", "CANCELLED", "TIMED_OUT");
        }

        JsonNode filtered = get(admin, "/api/v1/system/queue/jobs?status=TIMED_OUT").body();
        assertThat(filtered.size()).isGreaterThanOrEqualTo(1);
        assertThat(filtered.get(0).get("jobType").asText()).isEqualTo("DIAGNOSTIC");

        JsonNode stats = get(admin, "/api/v1/system/queue/stats").body();
        assertThat(stats.get("counts").get("PASSED").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("counts").get("TIMED_OUT").asInt()).isEqualTo(1);
        assertThat(stats.get("counts").get("CANCELLED").asInt()).isEqualTo(2);
        assertThat(stats.get("total").asInt()).isEqualTo(jobs.size());

        // Attempt timeline is part of the job detail.
        long timedOutJob = filtered.get(0).get("id").asLong();
        JsonNode detail = get(admin, "/api/v1/system/queue/jobs/" + timedOutJob).body();
        assertThat(detail.get("attempts").size()).isEqualTo(1);
        assertThat(detail.get("attempts").get(0).get("status").asText()).isEqualTo("TIMED_OUT");

        // Worker heartbeat age is reflected in the derived status: a
        // worker that never heartbeats is NEVER_SEEN, silent ones age
        // into OFFLINE.
        JsonNode workers = get(admin, "/api/v1/system/workers").body();
        assertThat(workers.size()).isEqualTo(3);
        JsonNode webWorker = null;
        for (JsonNode node : workers) {
            assertThat(node.get("status").asText()).isIn("ONLINE", "OFFLINE", "NEVER_SEEN");
            if ("Web Worker".equals(node.get("name").asText())) {
                webWorker = node;
            }
        }
        assertThat(webWorker.get("status").asText()).isEqualTo("NEVER_SEEN");
    }

    @Test
    @Order(10)
    void workerLifecycleIsAuditedWithoutCredentialMaterial() {
        Integer actions = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action IN "
                        + "('WORKER_REGISTERED','WORKER_DIAGNOSTIC_TRIGGERED','JOB_CANCELLED')",
                Integer.class);
        assertThat(actions).isGreaterThanOrEqualTo(3);

        Integer tokenLeaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE detail LIKE '%wpk_%'", Integer.class);
        assertThat(tokenLeaks).isZero();
    }
}
