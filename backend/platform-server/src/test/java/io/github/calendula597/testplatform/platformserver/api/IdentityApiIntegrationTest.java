package io.github.calendula597.testplatform.platformserver.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T02 acceptance seams over real HTTP with a real PostgreSQL: one-time
 * initialization, HttpOnly session cookies, CSRF enforcement, session
 * invalidation on user disable, and credential-free audit records.
 *
 * Test credentials are generated at runtime — no secrets in source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityApiIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String MEMBER_USERNAME = "zhang.san";
    private static final String MEMBER_PASSWORD = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

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

    private RestClient clientWithoutSession() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** A session-aware client keeping its own cookies, as a browser would. */
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

    private HttpResult call(RestClient client, HttpMethod method, String path, Object body,
                            Map<String, String> extraHeaders) {
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        if (method != HttpMethod.GET) {
            spec.contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
        }
        return spec.headers(headers -> extraHeaders.forEach(headers::add))
                .exchange((req, res) -> {
                    byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
                    String content = new String(raw);
                    return new HttpResult(res.getStatusCode().value(),
                            objectMapper.readTree(content.isEmpty() ? "{}" : content));
                });
    }

    private HttpResult postJson(RestClient client, String path, Object body, Map<String, String> extraHeaders) {
        return call(client, HttpMethod.POST, path, body, extraHeaders);
    }

    private HttpResult getJson(RestClient client, String path) {
        return call(client, HttpMethod.GET, path, null, Map.of());
    }

    /**
     * Fetches the CSRF cookie like the SPA does: the header must carry the
     * raw XSRF-TOKEN cookie value, never the encoded token from a body.
     */
    private String csrfTokenOf(BrowserClient browser) {
        HttpResult result = getJson(browser.rest(), "/api/v1/auth/csrf");
        assertThat(result.status()).as("csrf endpoint status").isEqualTo(200);
        return browser.cookies().getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .map(java.net.HttpCookie::getValue)
                .orElseThrow(() -> new AssertionError(
                        "XSRF-TOKEN cookie missing; store=" + browser.cookies().getCookieStore().getCookies()));
    }

    @Test
    @Order(1)
    void firstInitializeCreatesAdminOnceAndValidatesInput() {
        // Same cookie-jar client for CSRF fetch and the POST — the repository
        // validates the header token against the XSRF-TOKEN cookie.
        BrowserClient browser = clientWithSession();
        RestClient client = browser.rest();
        String csrf = csrfTokenOf(browser);

        // Weak password is rejected before any account exists.
        HttpResult weak = postJson(client, "/api/v1/system/initialize",
                Map.of("username", ADMIN_USERNAME, "password", "short"), Map.of("X-XSRF-TOKEN", csrf));
        assertThat(weak.status()).isEqualTo(400);

        // Initializing must log the administrator in: the response sets an
        // HttpOnly session cookie and carries the created account.
        record CreatedResult(HttpHeaders headers, JsonNode body) {
        }
        CreatedResult created = client.post().uri("/api/v1/system/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.add("X-XSRF-TOKEN", csrf))
                .body(Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD))
                .exchange((req, res) -> {
                    byte[] raw = res.getBody() == null ? new byte[0] : res.getBody().readAllBytes();
                    return new CreatedResult(res.getHeaders(), objectMapper.readTree(new String(raw)));
                });
        assertThat(created.body().get("username").asText()).isEqualTo(ADMIN_USERNAME);
        assertThat(created.body().get("role").asText()).isEqualTo("ADMIN");
        assertThat(created.body().get("status").asText()).isEqualTo("ACTIVE");
        String createdCookies = String.join("", created.headers().get(HttpHeaders.SET_COOKIE));
        assertThat(createdCookies).contains("HttpOnly");

        // The new session really authenticates: /me answers from the same jar.
        assertThat(getJson(client, "/api/v1/auth/me").body().get("username").asText())
                .isEqualTo(ADMIN_USERNAME);

        // Second initialization must be rejected — one-time only.
        HttpResult again = postJson(client, "/api/v1/system/initialize",
                Map.of("username", "intruder", "password", "whatever123"), Map.of("X-XSRF-TOKEN", csrf));
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    @Order(2)
    void loginIssuesHttpOnlySessionAndRejectsMissingCsrfOrWrongPassword() {
        BrowserClient browser = clientWithSession();
        RestClient client = browser.rest();
        String csrf = csrfTokenOf(browser);

        HttpHeaders headers = client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.add("X-XSRF-TOKEN", csrf))
                .body(Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD))
                .exchange((req, res) -> res.getHeaders());

        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        String sessionCookie = setCookies.stream()
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .findFirst()
                .orElse("");
        assertThat(sessionCookie).contains("HttpOnly");

        // Missing CSRF token -> rejected by the CSRF filter.
        HttpResult noCsrf = postJson(client, "/api/v1/auth/login",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), Map.of());
        assertThat(noCsrf.status()).isEqualTo(403);

        // Wrong password -> identical 401 message, no user enumeration.
        HttpResult wrong = postJson(client, "/api/v1/auth/login",
                Map.of("username", ADMIN_USERNAME, "password", "not-the-password"), Map.of("X-XSRF-TOKEN", csrf));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().get("message").asText()).isEqualTo("用户名或密码不正确，或账号已被禁用");
    }

    @Test
    @Order(3)
    void meLogoutAndDisabledUserSessionsInvalidate() {
        BrowserClient adminBrowser = clientWithSession();
        RestClient adminClient = adminBrowser.rest();
        String adminCsrf = csrfTokenOf(adminBrowser);
        postJson(adminClient, "/api/v1/auth/login",
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), Map.of("X-XSRF-TOKEN", adminCsrf));

        // Admin creates a member; member logs in and holds a live session.
        BrowserClient memberBrowser = clientWithSession();
        RestClient memberClient = memberBrowser.rest();
        postJson(adminClient, "/api/v1/system/users",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD, "role", "MEMBER"),
                Map.of("X-XSRF-TOKEN", adminCsrf));
        String memberCsrf = csrfTokenOf(memberBrowser);
        postJson(memberClient, "/api/v1/auth/login",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD), Map.of("X-XSRF-TOKEN", memberCsrf));
        assertThat(getJson(memberClient, "/api/v1/auth/me").body().get("username").asText())
                .isEqualTo(MEMBER_USERNAME);

        // Disabling the member kills the member's live session.
        Long memberId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, MEMBER_USERNAME);
        HttpResult updated = call(adminClient, HttpMethod.PUT, "/api/v1/system/users/" + memberId + "/status",
                Map.of("status", "DISABLED"), Map.of("X-XSRF-TOKEN", adminCsrf));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("status").asText()).isEqualTo("DISABLED");

        assertThat(getJson(memberClient, "/api/v1/auth/me").status()).isEqualTo(401);

        // The disabled account cannot log in again.
        BrowserClient memberRetry = clientWithSession();
        HttpResult rejected = postJson(memberRetry.rest(), "/api/v1/auth/login",
                Map.of("username", MEMBER_USERNAME, "password", MEMBER_PASSWORD),
                Map.of("X-XSRF-TOKEN", csrfTokenOf(memberRetry)));
        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.body().get("message").asText()).isEqualTo("用户名或密码不正确，或账号已被禁用");

        // Admin logout ends the admin session too.
        adminClient.post().uri("/api/v1/auth/logout")
                .headers(h -> h.add("X-XSRF-TOKEN", adminCsrf))
                .exchange((req, res) -> null);
        assertThat(getJson(adminClient, "/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void auditRecordsNeverContainCredentials() {
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT action, actor_username, target_username, detail FROM audit_event ORDER BY id");

        assertThat(events).extracting(e -> e.get("action"))
                .contains("SYSTEM_INITIALIZE", "LOGIN", "LOGIN_FAILED", "USER_CREATED",
                        "USER_DISABLED", "LOGOUT");

        String joined = events.stream()
                .map(e -> String.valueOf(e.get("detail")))
                .reduce("", (a, b) -> a + b);
        assertThat(joined).doesNotContain(ADMIN_PASSWORD, MEMBER_PASSWORD, "not-the-password");
        assertThat(joined).doesNotContain("password");
    }
}
