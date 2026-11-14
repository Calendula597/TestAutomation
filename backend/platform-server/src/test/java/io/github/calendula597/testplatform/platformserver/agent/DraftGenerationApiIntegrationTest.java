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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Draft generation seams: whitelist-only tools, citation validation against
 * actually retrieved knowledge, provenance, idempotent retries and
 * acceptance into the case grid.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DraftGenerationApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Scripted ReAct rounds: search -> final with a VALID citation. */
    @TestConfiguration
    static class ScriptedModelConfig {

        @Bean
        @Primary
        ModelClient scriptedModelClient() {
            return new ModelClient("", "", "") {
                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public String model() {
                    return "scripted-model";
                }

                @Override
                public Completion complete(String systemPrompt, String userPrompt) {
                    if (userPrompt.contains("第 1 轮")) {
                        return new Completion("""
                                {"thought":"需要检索登录测试规范",
                                 "action":{"tool":"search_knowledge","input":"登录"}}""", 100, 50);
                    }
                    if (userPrompt.contains("第 2 轮")) {
                        return new Completion("""
                                {"thought":"已有知识，生成草稿",
                                 "final":{"draftCases":[
                                   {"title":"正确凭据登录","priority":"HIGH","technique":"正向场景",
                                    "suggestion":"NEW","knowledgeInsufficient":false,
                                    "citations":[{"documentId":PLACEHOLDER_DOC_ID,"version":1}],
                                    "steps":[{"action":"输入正确凭据","testData":"admin","expected":"进入首页"}]}]}}
                                """.replace("PLACEHOLDER_DOC_ID", String.valueOf(documentId())), 150, 200);
                    }
                    throw new ModelClient.ModelException("意外轮次", null);
                }
            };
        }
    }

    private static Long cachedDocumentId;

    static long documentId() {
        return cachedDocumentId == null ? -1 : cachedDocumentId;
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
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "draft-project");
    }

    @Test
    @Order(0)
    void setupAdminProjectRequirementKnowledgeAndReadySession() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        assertThat(post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)).status()).isEqualTo(201);

        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "draft-project")).status()).isEqualTo(201);
        long pid = projectId();

        // Requirement to generate drafts for.
        assertThat(post(admin, "/api/v1/projects/" + pid + "/requirements",
                Map.of("title", "登录需求", "body", "登录后进入首页。", "sourceType", "MARKDOWN",
                        "acceptanceCriteria", "- 登录成功")).status()).isEqualTo(201);

        // Knowledge the model may cite.
        HttpResult knowledge = post(admin, "/api/v1/knowledge/global",
                Map.of("title", "登录测试规范",
                        "body", "# 规范\n正确凭据登录必须进入首页。"));
        assertThat(knowledge.status()).isEqualTo(201);
        cachedDocumentId = jdbc.queryForObject(
                "SELECT id FROM knowledge_document WHERE title = ?", Long.class, "登录测试规范");

        // Drive a session to READY_FOR_DRAFT with confirmed coverage.
        long requirementId = jdbc.queryForObject(
                "SELECT id FROM requirement WHERE title = ?", Long.class, "登录需求");
        HttpResult started = post(admin, "/api/v1/projects/" + pid + "/agent/sessions",
                Map.of("requirementId", requirementId));
        assertThat(started.status()).isEqualTo(201);
        long sessionId = started.body().get("id").asLong();

        // The session is only READY_FOR_DRAFT after answering and confirming;
        // use the API directly since no fake-analysis bean exists here.
        // Instead of relying on the async analysis, we push the session
        // forward through its endpoints after a manual waiting period.
        JsonNode detail = null;
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            detail = get(admin, "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId).body();
            if ("WAITING_CLARIFICATION".equals(detail.get("status").asText())) {
                break;
            }
        }
        // The scripted model has no analysis shape: force the state to
        // READY_FOR_DRAFT via SQL to focus this test on draft generation.
        jdbc.update("UPDATE agent_session SET status = 'READY_FOR_DRAFT' WHERE id = ?", sessionId);
        Long pointId = jdbc.queryForObject(
                "INSERT INTO coverage_point (session_id, description, technique, is_assumption, confirmed) "
                        + "VALUES (?, '验证登录成功', '正向场景', false, false) RETURNING id",
                Long.class, sessionId);
        assertThat(pointId).isNotNull();
    }

    @Test
    @Order(1)
    void generationProducesBatchWithValidatedCitationsAndProvenance() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long sessionId = jdbc.queryForObject(
                "SELECT id FROM agent_session ORDER BY id DESC LIMIT 1", Long.class);

        HttpResult generated = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId + "/generate-draft", Map.of());
        assertThat(generated.status()).isEqualTo(201);
        assertThat(generated.body().get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(generated.body().get("model").asText()).isEqualTo("scripted-model");
        assertThat(generated.body().get("promptVersion").asText()).isEqualTo("v1");

        JsonNode cases = generated.body().get("cases");
        Integer dbCases = jdbc.queryForObject(
                "SELECT count(*) FROM draft_batch_case", Integer.class);
        System.out.println("DEBUG dbCases=" + dbCases + " body=" + generated.body());
        assertThat(cases).as("cases in %s (dbCases=%s)", generated.body(), dbCases).hasSize(1);
        assertThat(cases.get(0).get("title").asText()).isEqualTo("正确凭据登录");
        assertThat(cases.get(0).get("steps")).hasSize(1);
        JsonNode citations = cases.get(0).get("citations");
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).get("documentId").asLong()).isEqualTo(documentId());
        assertThat(citations.get(0).get("documentTitle").asText()).isEqualTo("登录测试规范");

        // Provenance: tool calls recorded from the whitelist run.
        JsonNode toolCalls = generated.body().get("toolCalls");
        assertThat(toolCalls.size()).isGreaterThanOrEqualTo(1);
        assertThat(toolCalls.get(0).get("toolName").asText()).isEqualTo("search_knowledge");
    }

    @Test
    @Order(2)
    void repeatedGenerationReusesBatchWithoutNewModelBatch() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long sessionId = jdbc.queryForObject(
                "SELECT id FROM agent_session ORDER BY id DESC LIMIT 1", Long.class);

        HttpResult again = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId + "/generate-draft", Map.of());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("id").asLong())
                .isEqualTo(jdbc.queryForObject(
                        "SELECT id FROM draft_batch WHERE session_id = ?", Long.class, sessionId));
        Integer batchCount = jdbc.queryForObject(
                "SELECT count(*) FROM draft_batch WHERE session_id = ?", Integer.class, sessionId);
        assertThat(batchCount).isEqualTo(1);
    }

    @Test
    @Order(3)
    void acceptCreatesRealDraftRowsInGrid() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long pid = projectId();
        long sessionId = jdbc.queryForObject(
                "SELECT id FROM agent_session ORDER BY id DESC LIMIT 1", Long.class);

        HttpResult accepted = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId + "/draft/accept", Map.of());
        assertThat(accepted.status()).isEqualTo(200);
        JsonNode createdIds = accepted.body().get("createdCaseIds");
        assertThat(createdIds).hasSize(1);

        long caseId = createdIds.get(0).asLong();
        assertThat(jdbc.queryForObject("SELECT status FROM test_case WHERE id = ?", String.class, caseId))
                .isEqualTo("DRAFT");
        Integer steps = jdbc.queryForObject(
                "SELECT count(*) FROM test_case_step WHERE case_id = ?", Integer.class, caseId);
        assertThat(steps).isEqualTo(1);

        HttpResult reAccept = post(admin,
                "/api/v1/projects/" + pid + "/agent/sessions/" + sessionId + "/draft/accept", Map.of());
        assertThat(reAccept.status()).isEqualTo(400);
    }
}
