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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API chaining and parameterization seams (T16): response extraction feeds
 * later requests in a chain, data rows keep per-row results, secrets flow
 * only through environment references (a data row carrying a secret value
 * is rejected), and OpenAPI import produces reviewable drafts that are
 * never published or executed directly.
 *
 * The controlled in-test service verifies the chain for real: the profile
 * endpoint returns DENIED unless the token extracted from the login
 * response is replayed correctly.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiChainingApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    private static final String SECRET_TOKEN = "sk-live-secret-99";
    private static final String ISSUED_TOKEN = "abc123def456";

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
        DEMO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        demoPort = DEMO.getAddress().getPort();
        // Issues a token that the profile endpoint really verifies.
        DEMO.createContext("/login", exchange -> {
            byte[] response = ("{\"code\":\"OK\",\"token\":\"" + ISSUED_TOKEN + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.createContext("/me", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Token");
            String payload = ISSUED_TOKEN.equals(token)
                    ? "{\"code\":\"OK\",\"user\":\"alice\"}"
                    : "{\"code\":\"DENIED\"}";
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.createContext("/greet", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String name = query == null ? "" : query.replace("name=", "");
            byte[] response = ("{\"code\":\"OK\",\"name\":\"" + name + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        DEMO.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
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

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "chain-project");
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
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "chain-project")).status()).isEqualTo(201);
        long member = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId(), member);

        HttpResult env = post(admin, "/api/v1/projects/" + projectId() + "/environments",
                Map.of("name", "演示环境",
                        "plainVariables", Map.of("baseUrl", "http://127.0.0.1:" + demoPort),
                        "secrets", Map.of("apiKey", SECRET_TOKEN),
                        "targets", List.of("http://127.0.0.1:" + demoPort)));
        assertThat(env.status()).isEqualTo(201);

        HttpResult worker = post(admin, "/api/v1/system/workers",
                Map.of("name", "API Worker", "capabilities", List.of("API"),
                        "version", "0.1.0", "maxConcurrency", 2));
        assertThat(worker.status()).isEqualTo(201);
        WORKER_TOKENS.put("api", worker.body().get("token").asText());
    }

    /** Member writes an API case; the owner publishes it; returns case id. */
    private long createReadyApiCase(BrowserClient member, BrowserClient admin, String title,
                                    Map<String, Object> execution) {
        Map<String, Object> create = new HashMap<>();
        create.put("title", title);
        create.put("caseType", "API");
        create.put("execution", execution);
        create.put("steps", List.of(Map.of("stepOrder", 1,
                "action", "发送请求并校验响应", "expected", "断言全部通过")));
        HttpResult created = post(member, "/api/v1/projects/" + projectId() + "/testcases", create);
        assertThat(created.status()).isEqualTo(201);
        long caseId = created.body().get("id").asLong();
        int draftVersion = created.body().get("version").asInt();
        assertThat(post(admin, "/api/v1/projects/" + projectId() + "/testcases/"
                        + caseId + "/review/publish",
                Map.of("basis", "T16 基线", "expectedVersion", draftVersion)).status()).isEqualTo(201);
        return caseId;
    }

    private void runQueuedJob() {
        HttpResult claimed = workerPost(WORKER_TOKENS.get("api"),
                "/api/v1/worker/v1/jobs/claim", Map.of());
        assertThat(claimed.status()).as("应有排队任务可领取").isEqualTo(200);
        long attemptId = claimed.body().get("attempt").get("id").asLong();
        long caseVersionId = claimed.body().get("job").get("payload").get("caseVersionId").asLong();
        long envId = claimed.body().get("job").get("payload").get("environmentId").asLong();

        var outcome = apiExecutor.execute(caseVersionId, envId);
        Map<String, Object> detail = objectMapper.readValue(
                objectMapper.writeValueAsString(outcome), Map.class);
        HttpResult reported = workerPost(WORKER_TOKENS.get("api"),
                "/api/v1/worker/v1/attempts/" + attemptId + "/result",
                Map.of("status", outcome.status(), "detail", detail));
        assertThat(reported.status()).isEqualTo(200);
    }

    private long triggerExecution(BrowserClient member, long caseId) {
        HttpResult triggered = post(member, "/api/v1/projects/" + projectId() + "/executions",
                Map.of("caseId", caseId, "environmentId", environmentId()));
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
        return detail.has("rows") ? detail : detail;
    }

    @Test
    @Order(1)
    void chainedRequestsFeedExtractedValuesIntoLaterSteps() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of("requests", List.of(
                Map.of("name", "登录",
                        "request", Map.of("method", "POST", "url", "{{baseUrl}}/login",
                                "headers", Map.of("Accept", "application/json")),
                        "extract", List.of(Map.of("type", "JSONPATH", "path", "$.token",
                                "variable", "token")),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.code",
                                "expected", "OK"))),
                Map.of("name", "我的资料",
                        "request", Map.of("method", "GET", "url", "{{baseUrl}}/me",
                                "headers", Map.of("X-Token", "{{token}}")),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.user",
                                "expected", "alice")))));

        long caseId = createReadyApiCase(member, admin, "登录后取资料", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob();

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("rows").size()).isEqualTo(1);
        JsonNode row = result.get("rows").get(0);
        assertThat(row.get("status").asText()).isEqualTo("PASSED");
        assertThat(row.get("steps").size()).isEqualTo(2);

        JsonNode loginStep = row.get("steps").get(0);
        assertThat(loginStep.get("extract").get(0).get("variable").asText()).isEqualTo("token");
        assertThat(loginStep.get("extract").get(0).get("value").asText()).isEqualTo(ISSUED_TOKEN);
        JsonNode profileStep = row.get("steps").get(1);
        assertThat(profileStep.get("request").get("headers").get("X-Token").asText())
                .isEqualTo(ISSUED_TOKEN);
        // The chain is verified by the controlled service itself.
        assertThat(profileStep.get("response").get("body").asText()).contains("\"user\":\"alice\"");
    }

    @Test
    @Order(2)
    void assertionFailureInsideChainKeepsRowResultsPerStep() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of("requests", List.of(
                Map.of("name", "登录",
                        "request", Map.of("method", "POST", "url", "{{baseUrl}}/login"),
                        "extract", List.of(Map.of("type", "JSONPATH", "path", "$.token",
                                "variable", "token")),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.code",
                                "expected", "OK"))),
                Map.of("name", "错误预期",
                        "request", Map.of("method", "GET", "url", "{{baseUrl}}/me",
                                "headers", Map.of("X-Token", "{{token}}")),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.user",
                                "expected", "bob"))),
                Map.of("name", "仍然执行",
                        "request", Map.of("method", "GET", "url", "{{baseUrl}}/greet?name=hi"),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.code",
                                "expected", "OK")))));

        long caseId = createReadyApiCase(member, admin, "串联中途断言失败", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob();

        assertThat(jobStatus(jobId)).isEqualTo("ASSERTION_FAILED");
        JsonNode row = latestResult(jobId).get("rows").get(0);
        assertThat(row.get("status").asText()).isEqualTo("ASSERTION_FAILED");
        // The failing step records expected vs actual; the chain continued.
        assertThat(row.get("steps").get(1).get("assertions").get(0).get("passed").asBoolean()).isFalse();
        assertThat(row.get("steps").get(1).get("assertions").get(0).get("actual").asText())
                .isEqualTo("alice");
        assertThat(row.get("steps").size()).isEqualTo(3);
        assertThat(row.get("steps").get(2).get("assertions").get(0).get("passed").asBoolean()).isTrue();
    }

    @Test
    @Order(3)
    void dataRowsRunPerRowAndKeepEveryRowResult() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "requests", List.of(Map.of("name", "问候",
                        "request", Map.of("method", "GET", "url", "{{baseUrl}}/greet?name={{name}}"),
                        "assertions", List.of(
                                Map.of("type", "JSONPATH", "path", "$.name", "expected", "{{name}}"),
                                Map.of("type", "JSONPATH", "path", "$.code", "expected", "OK")))),
                "dataRows", List.of(
                        Map.of("name", "alice"),
                        Map.of("name", "bob"),
                        Map.of("name", "carol")));

        long caseId = createReadyApiCase(member, admin, "按数据行问候", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob();

        assertThat(jobStatus(jobId)).isEqualTo("PASSED");
        JsonNode result = latestResult(jobId);
        assertThat(result.get("rows").size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            JsonNode row = result.get("rows").get(i);
            assertThat(row.get("status").asText()).isEqualTo("PASSED");
            assertThat(row.get("values").get("name").asText())
                    .isEqualTo(List.of("alice", "bob", "carol").get(i));
        }
        // The first row really greeted alice — substitution used row values.
        assertThat(result.get("rows").get(0).get("steps").get(0).get("response")
                .get("body").asText()).contains("alice");
    }

    @Test
    @Order(4)
    void dataRowsCarryingSecretValuesAreRejected() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        Map<String, Object> execution = Map.of(
                "requests", List.of(Map.of("name", "问候",
                        "request", Map.of("method", "GET", "url", "{{baseUrl}}/greet?name={{name}}"),
                        "assertions", List.of(Map.of("type", "JSONPATH", "path", "$.code",
                                "expected", "OK")))),
                "dataRows", List.of(
                        Map.of("name", "alice"),
                        // Someone pastes an environment secret into a data row.
                        Map.of("name", SECRET_TOKEN)));

        long caseId = createReadyApiCase(member, admin, "数据行夹带密钥", execution);
        long jobId = triggerExecution(member, caseId);
        runQueuedJob();

        assertThat(jobStatus(jobId)).isEqualTo("ERROR");
        JsonNode result = latestResult(jobId);
        JsonNode rejectedRow = result.get("rows").get(1);
        assertThat(rejectedRow.get("status").asText()).isEqualTo("ERROR");
        assertThat(rejectedRow.get("reason").asText()).contains("数据行不得包含密钥明文");
        // The offending value is not persisted anywhere in the artifacts.
        assertThat(result.toString()).doesNotContain(SECRET_TOKEN);
        // The clean row still produced its result.
        assertThat(result.get("rows").get(0).get("status").asText()).isEqualTo("PASSED");
    }

    @Test
    @Order(5)
    void openApiImportCreatesReviewableDraftsOnly() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long before = jdbc.queryForObject("SELECT count(*) FROM test_case", Long.class);
        long jobsBefore = jdbc.queryForObject("SELECT count(*) FROM execution_job", Long.class);

        String openApi = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "宠物店", "version": "1.0.0"},
                  "servers": [{"url": "https://petstore.example.com"}],
                  "paths": {
                    "/pets": {
                      "get": {"operationId": "listPets",
                              "responses": {"200": {"description": "列表"}}},
                      "post": {"operationId": "createPet",
                               "requestBody": {"content": {"application/json": {"schema": {}}}},
                               "responses": {"201": {"description": "创建"}}}
                    },
                    "/pets/{petId}": {
                      "get": {"operationId": "showPetById",
                              "responses": {"200": {"description": "详情"}}}
                    }
                  }
                }""";
        HttpResult imported = post(member, "/api/v1/projects/" + projectId() + "/testcases/import-openapi",
                Map.of("definition", openApi));
        assertThat(imported.status()).isEqualTo(201);
        assertThat(imported.body().get("created").asInt()).isEqualTo(3);

        // Drafts exist with the API type and import origin, never published.
        long after = jdbc.queryForObject("SELECT count(*) FROM test_case", Long.class);
        assertThat(after - before).isEqualTo(3);
        List<Long> importedIds = new ArrayList<>();
        imported.body().get("caseIds").forEach(id -> importedIds.add(id.asLong()));
        Integer importedVersions = jdbc.queryForObject(
                "SELECT count(*) FROM test_case_version WHERE case_id IN ("
                        + importedIds.stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse("-1") + ")",
                Integer.class);
        assertThat(importedVersions).as("导入不直接发布").isZero();
        Long jobsAfter = jdbc.queryForObject("SELECT count(*) FROM execution_job", Long.class);
        assertThat(jobsAfter).as("导入不触发执行").isEqualTo(jobsBefore);

        // The draft carries the reviewable API schema; path params become
        // explicit variables and the target comes from the environment.
        JsonNode detail = get(member, "/api/v1/projects/" + projectId() + "/testcases").body();
        assertThat(detail.size()).isEqualTo(7);
        long showId = 0;
        int importedDrafts = 0;
        for (JsonNode caseNode : detail) {
            if ("showPetById".equals(caseNode.get("title").asText())
                    || "listPets".equals(caseNode.get("title").asText())
                    || "createPet".equals(caseNode.get("title").asText())) {
                assertThat(caseNode.get("caseType").asText()).isEqualTo("API");
                assertThat(caseNode.get("status").asText()).isEqualTo("DRAFT");
                importedDrafts++;
            }
            if ("showPetById".equals(caseNode.get("title").asText())) {
                showId = caseNode.get("id").asLong();
            }
        }
        assertThat(importedDrafts).isEqualTo(3);
        HttpResult showDetail = get(member, "/api/v1/projects/" + projectId()
                + "/testcases/" + showId);
        JsonNode request = showDetail.body().get("execution").get("request");
        assertThat(request.get("method").asText()).isEqualTo("GET");
        assertThat(request.get("url").asText())
                .isEqualTo("{{baseUrl}}/pets/{{petId}}");

        // Triggering the imported draft is refused until it is published.
        HttpResult trigger = post(member, "/api/v1/projects/" + projectId() + "/executions",
                Map.of("caseId", showId, "environmentId", environmentId()));
        assertThat(trigger.status()).isEqualTo(400);

        Integer importedAudit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'OPENAPI_IMPORTED'",
                Integer.class);
        assertThat(importedAudit).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(6)
    void openApiImportValidation() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);

        // Malformed definition.
        HttpResult broken = post(member, "/api/v1/projects/" + projectId() + "/testcases/import-openapi",
                Map.of("definition", "not json at all"));
        assertThat(broken.status()).isEqualTo(400);

        // A definition without paths creates nothing.
        HttpResult empty = post(member, "/api/v1/projects/" + projectId() + "/testcases/import-openapi",
                Map.of("definition", "{\"openapi\":\"3.0.3\",\"info\":{},\"paths\":{}}"));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().get("message").asText()).contains("没有可导入的操作");
    }
}
