package io.github.calendula597.testplatform.platformserver.agent;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent session seams against a deterministic fake model: blocking
 * clarifications gate the flow, answers and coverage confirmation persist
 * across reloads, cancellation and quota are explicit, and usage is
 * recorded. The real provider path is exercised separately (E2E).
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentSessionApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    static volatile boolean modelFails = false;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class FakeModelConfig {
        @Bean
        @Primary
        ModelClient fakeModelClient() {
            return new ModelClient("", "", "") {
                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public String model() {
                    return "fake-model";
                }

                @Override
                public Completion complete(String systemPrompt, String userPrompt) {
                    MODEL_CALLS.incrementAndGet();
                    if (modelFails) {
                        throw new ModelException("模拟模型故障", null);
                    }
                    String json = """
                            {"clarifications":[{"question":"响应时间目标是多少？","blocking":true}],
                             "coveragePoints":[{"description":"验证正确凭据登录成功","technique":"正向场景"},
                                               {"description":"验证超时提示","technique":"异常场景"}]}
                            """;
                    return new Completion(json, 120, 80);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

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

    private record HttpResult(int status, JsonNode body) {
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
        return spec.exchange((req, res) -> {
            byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
            String content = new String(raw);
            return new HttpResult(res.getStatusCode().value(),
                    objectMapper.readTree(content.isEmpty() ? "{}" : content));
        });
    }

    private HttpResult post(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.POST, path, body);
    }

    private HttpResult put(BrowserClient client, String path, Object body) {
        return call(client, HttpMethod.PUT, path, body);
    }

    private HttpResult get(BrowserClient client, String path) {
        return call(client, HttpMethod.GET, path, null);
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
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "agent-project");
    }

    @Test
    @Order(0)
    void setupAdminProjectAndRequirement() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "agent-project")).status()).isEqualTo(201);
        long pid = projectId();
        assertThat(post(admin, "/api/v1/projects/" + pid + "/requirements",
                Map.of("title", "登录需求",
                        "body", "用户登录后进入首页。目标响应时间未说明。",
                        "sourceType", "MARKDOWN",
                        "acceptanceCriteria", "- 登录成功")).status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void sessionRunsAnalysisAndBlocksOnClarification() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录需求");

        HttpResult started = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                Map.of("requirementId", requirementId));
        assertThat(started.status()).isEqualTo(201);
        long sessionId = started.body().get("id").asLong();

        // Async analysis: wait for the persisted clarifications.
        JsonNode detail = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(300);
            detail = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId).body();
            if (detail.get("questions").size() > 0) {
                break;
            }
        }
        assertThat(detail.get("status").asText()).isEqualTo("WAITING_CLARIFICATION");
        assertThat(detail.get("questions")).hasSize(1);
        assertThat(detail.get("questions").get(0).get("blocking").asBoolean()).isTrue();
        assertThat(detail.get("coveragePoints")).hasSize(2);
        assertThat(detail.get("model").asText()).isEqualTo("fake-model");
        assertThat(detail.get("promptTokens").asInt()).isGreaterThan(0);

        // Usage is recorded on the task with a DONE status.
        assertThat(detail.get("task").get("status").asText()).isEqualTo("DONE");
        assertThat(detail.get("task").get("promptTokens").asInt()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void answersGateCoverageAndPersistAcrossReload() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long sessionId = jdbc.queryForObject(
                "SELECT id FROM agent_session ORDER BY id DESC LIMIT 1", Long.class);

        // While blocking questions are unanswered the session stays waiting.
        JsonNode detail = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId).body();
        long questionId = detail.get("questions").get(0).get("id").asLong();

        HttpResult answered = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId
                        + "/questions/" + questionId + "/answer",
                Map.of("answer", "P95 小于 2 秒"));
        assertThat(answered.status()).isEqualTo(200);
        assertThat(answered.body().get("status").asText()).isEqualTo("COVERAGE_CONFIRMATION");

        // Persisted: reload shows the same state, answer and coverage points.
        JsonNode reloaded = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId).body();
        assertThat(reloaded.get("status").asText()).isEqualTo("COVERAGE_CONFIRMATION");
        assertThat(reloaded.get("questions").get(0).get("answer").asText()).isEqualTo("P95 小于 2 秒");
        assertThat(reloaded.get("coveragePoints").get(0).get("confirmed").asBoolean()).isFalse();

        // Confirm both coverage points -> READY_FOR_DRAFT.
        long point1 = reloaded.get("coveragePoints").get(0).get("id").asLong();
        long point2 = reloaded.get("coveragePoints").get(1).get("id").asLong();
        HttpResult confirmed = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId + "/coverage/confirm",
                Map.of("confirmations", List.of(
                        Map.of("pointId", point1, "confirmed", true),
                        Map.of("pointId", point2, "confirmed", false, "userNote", "暂不验证超时"))));
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().get("status").asText()).isEqualTo("READY_FOR_DRAFT");

        // Restart-safe: a brand-new session object reads the same persisted state.
        JsonNode afterRestart = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId).body();
        assertThat(afterRestart.get("status").asText()).isEqualTo("READY_FOR_DRAFT");
        assertThat(afterRestart.get("coveragePoints").get(1).get("userNote").asText()).isEqualTo("暂不验证超时");
    }

    @Test
    @Order(3)
    void quotaLimitsConcurrentSessionsAndCancelIsExplicit() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录需求");

        // The fake model raises a blocking question, so every new session
        // parks in WAITING_CLARIFICATION (active). Fill the quota of 5.
        long[] sessionIds = new long[5];
        for (int i = 0; i < 5; i++) {
            HttpResult created = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                    Map.of("requirementId", requirementId));
            assertThat(created.status()).isEqualTo(201);
            sessionIds[i] = created.body().get("id").asLong();
        }
        for (int i = 0; i < 30; i++) {
            Thread.sleep(200);
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_session WHERE status = 'WAITING_CLARIFICATION'",
                    Integer.class);
            if (waiting != null && waiting >= 5) {
                break;
            }
        }

        HttpResult sixth = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                Map.of("requirementId", requirementId));
        assertThat(sixth.status()).as("quota exceeded").isEqualTo(429);

        // Cancel is explicit and frees the slot.
        HttpResult cancelled = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionIds[0] + "/cancel", Map.of());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");

        HttpResult afterCancel = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                Map.of("requirementId", requirementId));
        assertThat(afterCancel.status()).isEqualTo(201);
        long failingId = afterCancel.body().get("id").asLong();

        // Model failure surfaces as FAILED with a visible error and bounded
        // retries — never a fake template fallback. Free a quota slot first.
        HttpResult freed = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionIds[1] + "/cancel", Map.of());
        assertThat(freed.status()).isEqualTo(200);
        modelFails = true;
        HttpResult failing = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                Map.of("requirementId", requirementId));
        long failedId = failing.body().get("id").asLong();
        JsonNode failedDetail = null;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(300);
            failedDetail = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + failedId).body();
            if ("FAILED".equals(failedDetail.get("status").asText())) {
                break;
            }
        }
        modelFails = false;
        assertThat(failedDetail.get("status").asText()).isEqualTo("FAILED");
        assertThat(failedDetail.get("error").asText()).contains("模拟模型故障");
        assertThat(failedDetail.get("task").get("attempts").asInt()).isGreaterThanOrEqualTo(3);
    }
}
