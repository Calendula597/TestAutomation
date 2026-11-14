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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knowledge seams: versioned documents with chunking, scoped hybrid search
 * (no cross-project leakage), and an explicit embedding-not-configured
 * state instead of a silent fallback.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "member.a";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String OUTSIDER_USERNAME = "member.b";
    private static final String OUTSIDER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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

    @Test
    @Order(0)
    void setupUsersProjectsAndGlobalKnowledge() {
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
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "kb-project-a")).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/projects", Map.of("name", "kb-project-b")).status()).isEqualTo(201);

        long memberA = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, MEMBER_USERNAME);
        long projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "kb-project-a");
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectA, memberA);
    }

    @Test
    @Order(1)
    void adminCreatesVersionedGlobalKnowledgeWithChunks() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String body = """
                # 登录测试规范

                所有登录用例必须覆盖正确凭据与错误凭据两种路径。

                # 超时规范

                页面操作默认超时为 10 秒。
                """;
        HttpResult created = post(admin, "/api/v1/knowledge/global",
                Map.of("title", "测试规范", "body", body));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("currentVersion").asInt()).isEqualTo(1);

        Integer chunks = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk", Integer.class);
        assertThat(chunks).isGreaterThanOrEqualTo(2); // two markdown headings

        // The embedding provider is not configured in tests: visible state.
        String indexError = jdbc.queryForObject(
                "SELECT index_error FROM knowledge_chunk LIMIT 1", String.class);
        assertThat(indexError).contains("嵌入服务未配置");
    }

    @Test
    @Order(2)
    void projectKnowledgeIsScopedToMembers() {
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "kb-project-a");

        HttpResult created = post(member, "/api/v1/projects/" + projectA + "/knowledge",
                Map.of("title", "项目A专用口径",
                        "body", "项目A的结算口径：只统计工作日。"));
        assertThat(created.status()).isEqualTo(201);

        // Outsider cannot see project A's documents at all.
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        HttpResult denied = get(outsider, "/api/v1/projects/" + projectA + "/knowledge");
        assertThat(denied.status()).isEqualTo(404);

        // MEMBER cannot touch the global admin channel.
        HttpResult globalDenied = post(member, "/api/v1/knowledge/global",
                Map.of("title", "x", "body", "y"));
        assertThat(globalDenied.status()).isEqualTo(403);
    }

    @Test
    @Order(3)
    void updateAppendsVersionAndKeepsHistory() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long documentId = jdbc.queryForObject(
                "SELECT id FROM knowledge_document WHERE title = ?", Long.class, "测试规范");

        HttpResult updated = put(admin, "/api/v1/knowledge/global/" + documentId,
                Map.of("title", "测试规范", "body", "# 登录测试规范\n\n新增：登录必须记录审计日志。"));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("currentVersion").asInt()).isEqualTo(2);

        JsonNode versions = get(admin, "/api/v1/knowledge/global/" + documentId + "/versions").body();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("body").asText()).contains("两种路径");

        Integer v1Chunks = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk kc JOIN knowledge_version kv ON kv.id = kc.version_id "
                        + "WHERE kv.document_id = ? AND kv.version = 1", Integer.class, documentId);
        assertThat(v1Chunks).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(4)
    void searchAppliesScopeBeforeRetrievalWithoutLeakage() {
        // member.a sees global + project A — never project B (which has no docs).
        BrowserClient member = login(MEMBER_USERNAME, MEMBER_PASSWORD);
        long projectA = jdbc.queryForObject("SELECT id FROM project WHERE name = ?",
                Long.class, "kb-project-a");

        JsonNode scoped = get(member, "/api/v1/knowledge/search?q=登录&projectId=" + projectA).body();
        assertThat(scoped.get("embeddingStatus").asText()).isEqualTo("NOT_CONFIGURED");
        JsonNode results = scoped.get("results");
        assertThat(results).isNotEmpty();
        for (JsonNode result : results) {
            assertThat(result.get("scope").asText()).isIn("GLOBAL", "PROJECT");
            if ("PROJECT".equals(result.get("scope").asText())) {
                assertThat(result.get("projectId").asLong()).isEqualTo(projectA);
            }
            assertThat(result.get("version").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(result.get("content").asText()).isNotBlank();
        }
        // Keyword hit on the project-specific document proves real retrieval.
        JsonNode projectHit = get(member, "/api/v1/knowledge/search?q=结算口径&projectId=" + projectA).body();
        assertThat(projectHit.get("results").toString()).contains("结算口径");

        // Outsider searching without a project sees ONLY global knowledge.
        BrowserClient outsider = login(OUTSIDER_USERNAME, OUTSIDER_PASSWORD);
        JsonNode globalOnly = get(outsider, "/api/v1/knowledge/search?q=登录").body();
        for (JsonNode result : globalOnly.get("results")) {
            assertThat(result.get("scope").asText()).isEqualTo("GLOBAL");
        }

        // Outsider cannot search inside project A (scope enforcement first).
        HttpResult denied = get(outsider, "/api/v1/knowledge/search?q=结算&projectId=" + projectA);
        assertThat(denied.status()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void knowledgeAuditTrailExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action LIKE 'KNOWLEDGE_%'", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(3);
    }
}
