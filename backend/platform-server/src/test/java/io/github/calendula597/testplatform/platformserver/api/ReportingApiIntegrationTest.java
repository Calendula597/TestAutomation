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
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportingApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String OUTSIDER_USERNAME = "member.b";
    private static final String OUTSIDER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final Path EVIDENCE_DIR = Path.of(System.getProperty("java.io.tmpdir"),
            "reporting-evidence-" + UUID.randomUUID());
    private static final Path BACKUP_DIR = Path.of(System.getProperty("java.io.tmpdir"),
            "reporting-backup-" + UUID.randomUUID());

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String MASTER_KEY = newMasterKey();
    private static long jobId;
    private static String storageKey;

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
        registry.add("app.backup.dir", () -> BACKUP_DIR.toString());
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

    private record BinaryResult(int status, String contentType, byte[] body) {
    }

    private BrowserClient clientWithSession() {
        return clientWithSession(port);
    }

    private BrowserClient clientWithSession(int targetPort) {
        CookieManager cookies = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(cookies).build();
        RestClient rest = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + targetPort)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
                .build();
        return new BrowserClient(rest, cookies);
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

    private HttpResult put(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.PUT, path, body);
    }

    private BinaryResult download(BrowserClient client, String path) {
        return client.rest().get().uri(path).exchange((request, response) -> {
            byte[] bytes = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
            return new BinaryResult(response.getStatusCode().value(),
                    response.getHeaders().getFirst("Content-Type"), bytes);
        });
    }

    private BrowserClient login(String username, String password) {
        BrowserClient client = clientWithSession();
        call(client, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(client, "/api/v1/auth/login",
                Map.of("username", username, "password", password)).status()).isEqualTo(200);
        return client;
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "reporting-project");
    }

    @Test
    @Order(0)
    void setupUsersProjectExecutionAndEvidence() throws Exception {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        for (String username : List.of(MEMBER_USERNAME, OUTSIDER_USERNAME)) {
            String password = username.equals(MEMBER_USERNAME) ? MEMBER_PASSWORD : OUTSIDER_PASSWORD;
            assertThat(post(admin, "/api/v1/system/users",
                    Map.of("username", username, "password", password)).status()).isEqualTo(201);
        }
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "reporting-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);
        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "Reporting Worker", "capabilities", List.of("WEB"),
                        "version", "0.1.0", "maxConcurrency", 1));
        assertThat(worker.status()).isEqualTo(201);
        long workerId = worker.body().get("id").asLong();
        long adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, ADMIN_USERNAME);
        jdbc.update("INSERT INTO execution_job (job_type, project_id, payload, status, requeue_on_lost, created_by) "
                        + "VALUES ('WEB', ?, ?, 'PASSED', false, ?)", projectId(),
                "{\"caseVersionId\":1,\"environmentId\":1}", adminId);
        jobId = jdbc.queryForObject("SELECT id FROM execution_job ORDER BY id DESC LIMIT 1", Long.class);
        Path runDirectory = EVIDENCE_DIR.resolve("run-1");
        Files.createDirectories(runDirectory);
        Files.write(runDirectory.resolve("screen.png"), new byte[]{1, 2, 3, 4});
        storageKey = "run-1/screen.png";
        String result = "{\"detail\":{\"status\":\"PASSED\",\"durationMs\":1000,"
                + "\"steps\":[{\"index\":0,\"action\":\"CLICK\",\"status\":\"PASSED\"}],"
                + "\"evidence\":[{\"id\":\"evidence-1\",\"kind\":\"SCREENSHOT\","
                + "\"path\":\"" + storageKey + "\",\"filename\":\"screen.png\","
                + "\"contentType\":\"image/png\"}]}}";
        jdbc.update("INSERT INTO execution_attempt (job_id, attempt_number, worker_id, lease_token, "
                        + "lease_expires_at, status, result, started_at, finished_at) "
                        + "VALUES (?, 1, ?, 'lease', ?, 'PASSED', ?, now() - interval '2 seconds', now())",
                jobId, workerId, OffsetDateTime.now().plusHours(1), result);
    }

    @Test
    @Order(1)
    void exportsUseUnifiedPermissionsAndFormats() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        String base = "/api/v1/projects/" + projectId() + "/executions/" + jobId + "/export?format=";

        BinaryResult html = download(admin, base + "html");
        assertThat(html.status()).isEqualTo(200);
        assertThat(new String(html.body())).contains("执行报告 #" + jobId, "尝试 #1");

        BinaryResult junit = download(admin, base + "junit");
        assertThat(junit.status()).isEqualTo(200);
        assertThat(new String(junit.body())).contains("<testsuite", "attempt-1");

        BinaryResult csv = download(admin, base + "csv");
        assertThat(csv.status()).isEqualTo(200);
        assertThat(new String(csv.body())).startsWith("jobId,attemptNumber,status,durationMs,resultJson");

        BinaryResult xlsx = download(admin, base + "xlsx");
        assertThat(xlsx.status()).isEqualTo(200);
        assertThat(xlsx.body()).startsWith(new byte[]{'P', 'K'});

        BinaryResult raw = download(admin, base + "raw");
        assertThat(raw.status()).isEqualTo(200);
        assertThat(new String(raw.body())).contains("evidence-1", "CLICK");
        assertThat(download(outsider, base + "html").status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void backupRestoresDatabaseAndEvidenceIntoEmptyEnvironment() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult backup = post(admin, "/api/v1/system/backups", Map.of());
        assertThat(backup.status()).isEqualTo(200);
        String filename = backup.body().get("filename").asText();
        assertThat(backup.body().get("bytes").asLong()).isGreaterThan(0);

        HttpResult restored = post(admin, "/api/v1/system/restore", Map.of("filename", filename));
        assertThat(restored.status()).isEqualTo(200);
        String database = restored.body().get("database").asText();
        Path evidenceRoot = Path.of(restored.body().get("evidenceDir").asText());
        assertThat(Files.exists(evidenceRoot.resolve(storageKey))).isTrue();

        var restoredApplication = new org.springframework.boot.builder.SpringApplicationBuilder(
                io.github.calendula597.testplatform.platformserver.PlatformServerApplication.class)
                .run("--server.port=0", "--spring.datasource.url=jdbc:postgresql://localhost:"
                                + POSTGRES.getFirstMappedPort() + "/" + database,
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--app.secrets.master-key=" + MASTER_KEY,
                        "--app.evidence.dir=" + evidenceRoot,
                        "--app.backup.dir=" + BACKUP_DIR.resolve("empty-environment-backups"),
                        "--logging.level.org.springframework.security=INFO");
        try (restoredApplication) {
            int restoredPort = ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)
                    restoredApplication).getWebServer().getPort();
            BrowserClient restoredClient = clientWithSession(restoredPort);
            call(restoredClient, HttpMethod.GET, "/api/v1/auth/csrf", null);
            assertThat(post(restoredClient, "/api/v1/auth/login",
                    Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(200);
            HttpResult history = get(restoredClient,
                    "/api/v1/projects/" + projectId() + "/executions/" + jobId);
            assertThat(history.status()).isEqualTo(200);
            assertThat(history.body().get("status").asText()).isEqualTo("PASSED");
            BinaryResult evidence = download(restoredClient, "/api/v1/projects/" + projectId()
                    + "/executions/" + jobId + "/evidence/evidence-1");
            assertThat(evidence.status()).isEqualTo(200);
            assertThat(evidence.body()).containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    @Order(3)
    void retentionPrunesOnlyFilesAndRecordsResult() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult initial = get(admin, "/api/v1/projects/" + projectId() + "/evidence-policy");
        assertThat(initial.body().get("retentionDays").asInt()).isEqualTo(30);
        assertThat(put(admin, "/api/v1/projects/" + projectId() + "/evidence-policy",
                Map.of("retentionDays", 1)).status()).isEqualTo(200);
        Files.setLastModifiedTime(EVIDENCE_DIR.resolve(storageKey),
                java.nio.file.attribute.FileTime.from(OffsetDateTime.now().minusDays(2).toInstant()));
        assertThat(Files.getLastModifiedTime(EVIDENCE_DIR.resolve(storageKey)).toInstant())
                .isBefore(OffsetDateTime.now().minusDays(1).toInstant());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE result LIKE ?",
                Integer.class, "%" + storageKey + "%")).isEqualTo(1);
        assertThat(Files.getLastModifiedTime(EVIDENCE_DIR.resolve(storageKey)).toInstant())
                .isBefore(OffsetDateTime.now().minusDays(1).toInstant());

        HttpResult swept = post(admin, "/api/v1/system/evidence-retention/sweep", Map.of());
        assertThat(swept.status()).isEqualTo(200);
        assertThat(swept.body().get("deletedFiles").asInt()).isEqualTo(1);
        assertThat(download(admin, "/api/v1/projects/" + projectId()
                + "/executions/" + jobId + "/evidence/evidence-1").status()).isEqualTo(410);
        HttpResult detail = get(admin, "/api/v1/projects/" + projectId() + "/executions/" + jobId);
        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        assertThat(detail.body().get("attempts").get(0).get("result").toString())
                .contains("evidence-1", "CLICK");
    }

    @Test
    @Order(4)
    void retentionUsesOwningProjectPolicyAndDefaultThirtyDays() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult project = post(admin, "/api/v1/projects", Map.of("name", "retention-other-project"));
        assertThat(project.status()).isEqualTo(201);
        long otherProject = project.body().get("id").asLong();
        assertThat(get(admin, "/api/v1/projects/" + otherProject + "/evidence-policy")
                .body().get("retentionDays").asInt()).isEqualTo(30);
        long adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, ADMIN_USERNAME);
        long workerId = jdbc.queryForObject("SELECT id FROM worker ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO execution_job (job_type, project_id, payload, status, requeue_on_lost, created_by) "
                + "VALUES ('WEB', ?, ?, 'PASSED', false, ?)", otherProject,
                "{\"caseVersionId\":1,\"environmentId\":1}", adminId);
        long otherJob = jdbc.queryForObject("SELECT id FROM execution_job ORDER BY id DESC LIMIT 1", Long.class);
        String result = "{\"detail\":{\"status\":\"PASSED\",\"durationMs\":1000,\"steps\":[],"
                + "\"evidence\":["
                + "{\"id\":\"other-keep\",\"kind\":\"SCREENSHOT\",\"path\":\"run-other/keep.png\","
                + "\"filename\":\"keep.png\",\"contentType\":\"image/png\"},"
                + "{\"id\":\"other-old\",\"kind\":\"SCREENSHOT\",\"path\":\"run-other/old.png\","
                + "\"filename\":\"old.png\",\"contentType\":\"image/png\"}]}}";
        jdbc.update("INSERT INTO execution_attempt (job_id, attempt_number, worker_id, lease_token, "
                        + "lease_expires_at, status, result, finished_at) VALUES (?, 1, ?, 'other-lease', ?, 'PASSED', ?, now())",
                otherJob, workerId, OffsetDateTime.now().plusHours(1), result);
        Path directory = EVIDENCE_DIR.resolve("run-other");
        Files.createDirectories(directory);
        Files.write(directory.resolve("keep.png"), new byte[]{1, 2});
        Files.write(directory.resolve("old.png"), new byte[]{3, 4});
        Files.setLastModifiedTime(directory.resolve("keep.png"),
                java.nio.file.attribute.FileTime.from(OffsetDateTime.now().minusDays(2).toInstant()));
        Files.setLastModifiedTime(directory.resolve("old.png"),
                java.nio.file.attribute.FileTime.from(OffsetDateTime.now().minusDays(31).toInstant()));

        HttpResult swept = post(admin, "/api/v1/system/evidence-retention/sweep", Map.of());

        assertThat(swept.status()).isEqualTo(200);
        assertThat(swept.body().get("deletedFiles").asInt()).isEqualTo(1);
        assertThat(download(admin, "/api/v1/projects/" + otherProject + "/executions/" + otherJob
                + "/evidence/other-keep").status()).isEqualTo(200);
        assertThat(download(admin, "/api/v1/projects/" + otherProject + "/executions/" + otherJob
                + "/evidence/other-old").status()).isEqualTo(410);
        HttpResult detail = get(admin, "/api/v1/projects/" + otherProject + "/executions/" + otherJob);
        assertThat(detail.body().get("status").asText()).isEqualTo("PASSED");
        assertThat(detail.body().get("attempts").get(0).get("result").toString()).contains("other-old");
    }

    @Test
    @Order(5)
    void exportUsesSecondsAndInvalidInputsAreRejected() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String base = "/api/v1/projects/" + projectId() + "/executions/" + jobId + "/export?format=";
        BinaryResult junit = download(admin, base + "junit");
        assertThat(junit.status()).isEqualTo(200);
        assertThat(new String(junit.body())).contains("time=\"2.0\"");
        assertThat(download(admin, base + "yaml").status()).isEqualTo(400);
        assertThat(put(admin, "/api/v1/projects/" + projectId() + "/evidence-policy",
                Map.of("retentionDays", 0)).status()).isEqualTo(400);
        assertThat(post(admin, "/api/v1/system/restore",
                Map.of("filename", "../not-a-backup.zip")).status()).isEqualTo(400);
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        assertThat(get(outsider, "/api/v1/projects/" + projectId() + "/evidence-policy").status()).isEqualTo(404);
    }
}
