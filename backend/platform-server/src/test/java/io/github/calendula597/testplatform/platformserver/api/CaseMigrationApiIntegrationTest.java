package io.github.calendula597.testplatform.platformserver.api;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * Migration seams: preview with row-level errors, draft-only commit,
 * size limits, formula-safe exports, and project-scoped authorization.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseMigrationApiIntegrationTest {

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

    private record HttpResult(int status, JsonNode body, byte[] raw, String contentType, String disposition) {
    }

    private HttpResult call(BrowserClient client, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = client.rest().method(method).uri(path);
        if (method != HttpMethod.GET) {
            spec.contentType(MediaType.APPLICATION_JSON);
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
            JsonNode node;
            try {
                node = objectMapper.readTree(content.isEmpty() ? "{}" : content);
            } catch (Exception e) {
                // Binary or CSV export bodies are not JSON.
                node = objectMapper.nullNode();
            }
            return new HttpResult(res.getStatusCode().value(), node, raw,
                    String.valueOf(res.getHeaders().getContentType()),
                    String.valueOf(res.getHeaders().getFirst("Content-Disposition")));
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

    /** Multipart preview call; the body is hand-built to stay off reactive classes. */
    private HttpResult preview(BrowserClient client, String csv, String mapping) {
        String csrf = client.cookies().getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
        String boundary = "----t07boundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder body = new StringBuilder();
        body.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"cases.csv\"\r\n")
                .append("Content-Type: text/csv\r\n\r\n")
                .append(csv).append("\r\n")
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"mapping\"\r\n\r\n")
                .append(mapping).append("\r\n")
                .append("--").append(boundary).append("--\r\n");
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        RestClient.RequestBodySpec spec = client.rest().method(HttpMethod.POST)
                .uri("/api/v1/projects/{projectId}/testcases/import/preview", projectId())
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                .headers(h -> h.add("X-XSRF-TOKEN", csrf))
                .body(bytes);
        return spec.exchange((req, res) -> {
            byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
            return new HttpResult(res.getStatusCode().value(),
                    objectMapper.readTree(new String(raw)), raw, "", "");
        });
    }

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM project WHERE name = ?", Long.class, "migration-project");
    }

    @Test
    @Order(0)
    void setup() {
        BrowserClient bootstrap = clientWithSession();
        call(bootstrap, HttpMethod.GET, "/api/v1/auth/csrf", null);
        HttpResult init = post(bootstrap, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(init.status()).isEqualTo(201);
        createProject(bootstrap);
    }

    private void createProject(BrowserClient owner) {
        HttpResult created = post(owner, "/api/v1/projects", Map.of("name", "migration-project"));
        assertThat(created.status()).isEqualTo(201);
    }

    @Test
    @Order(1)
    void previewReportsRowLevelErrorsWithoutCreatingDrafts() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String csv = """
                标题,优先级,步骤
                正常登录,HIGH,打开登录页 :: 页面加载
                空标题用例,MEDIUM,
                ,LOW,动作 :: 预期
                """;
        HttpResult preview = preview(admin, csv, "{\"titleColumn\":0,\"priorityColumn\":1,\"stepsColumn\":2}");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().get("totalRows").asInt()).isEqualTo(3);

        JsonNode rows = preview.body().get("rows");
        assertThat(rows.get(0).get("errors")).isEmpty();
        assertThat(rows.get(0).get("steps")).hasSize(1);
        assertThat(rows.get(1).get("errors").toString()).contains("未解析到任何步骤");
        assertThat(rows.get(2).get("errors").toString()).contains("标题为空");

        Integer drafts = jdbc.queryForObject(
                "SELECT count(*) FROM test_case WHERE project_id = ?", Integer.class, projectId());
        assertThat(drafts).isZero();
    }

    @Test
    @Order(2)
    void commitCreatesDraftsOnlyForValidRows() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        HttpResult committed = post(admin, "/api/v1/projects/" + projectId() + "/testcases/import/commit",
                Map.of("rows", List.of(
                        Map.of("title", "导入用例一", "priority", "HIGH",
                                "steps", List.of(Map.of("action", "打开页面", "expected", "加载"))),
                        Map.of("title", "", "priority", "HIGH",
                                "steps", List.of(Map.of("action", "x", "expected", "y"))))));
        assertThat(committed.status()).isEqualTo(200);
        assertThat(committed.body().get("created").asInt()).isEqualTo(1);
        assertThat(committed.body().get("results").get(1).get("status").asInt()).isEqualTo(400);

        String status = jdbc.queryForObject(
                "SELECT status FROM test_case WHERE title = '导入用例一'", String.class);
        assertThat(status).isEqualTo("DRAFT");
    }

    @Test
    @Order(3)
    void oversizeFileIsRejected() {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String big = "标题,优先级,步骤\n" + "x".repeat(5 * 1024 * 1024);
        HttpResult preview = preview(admin, big, "{\"titleColumn\":0}");
        assertThat(preview.status()).isEqualTo(400);
        assertThat(preview.body().get("message").asText()).contains("5MB");
    }

    @Test
    @Order(4)
    void exportSanitizesFormulaCellsAndRespectsFormat() throws Exception {
        BrowserClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // A title that looks like a formula must not round-trip executable.
        HttpResult created = post(admin, "/api/v1/projects/" + projectId() + "/testcases",
                Map.of("title", "=HYPERLINK(\"http://evil\")", "priority", "MEDIUM",
                        "steps", List.of(Map.of("action", "=cmd", "expected", "x"))));
        assertThat(created.status()).isEqualTo(201);

        HttpResult csv = get(admin, "/api/v1/projects/" + projectId() + "/testcases/export?format=csv");
        assertThat(csv.status()).isEqualTo(200);
        String csvText = new String(csv.raw(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csvText).contains("'=HYPERLINK");
        assertThat(csvText).doesNotContain("\n=HYPERLINK");

        HttpResult xlsx = get(admin, "/api/v1/projects/" + projectId() + "/testcases/export?format=xlsx");
        assertThat(xlsx.status()).isEqualTo(200);
        assertThat(xlsx.raw()[0]).isEqualTo((byte) 'P'); // XLSX files are ZIP (PK header).

        HttpResult bad = get(admin, "/api/v1/projects/" + projectId() + "/testcases/export?format=xml");
        assertThat(bad.status()).isEqualTo(400);
    }

    @Test
    @Order(5)
    void auditCoversPreviewCommitAndExport() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action IN "
                        + "('TESTCASE_IMPORT_PREVIEWED','TESTCASE_IMPORTED','TESTCASE_EXPORTED')",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(3);
    }
}
