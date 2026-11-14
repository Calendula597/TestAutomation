package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.health.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T01 acceptance seam: the public HTTP API reports the real database state.
 * Runs against a real PostgreSQL (pgvector image), never an embedded fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthApiIntegrationTest {

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

    private RestClient client;

    @org.junit.jupiter.api.BeforeEach
    void setUpClient() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void healthEndpointReportsRealDatabaseState() {
        HealthResponse health = client.get().uri("/api/v1/health").retrieve().body(HealthResponse.class);

        assertThat(health).isNotNull();
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.checkedAt()).isNotNull();

        var database = health.components().get("database");
        assertThat(database).isNotNull();
        assertThat(database.status()).isEqualTo("UP");
        assertThat(database.latencyMs()).isNotNull();
        assertThat(database.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(database.detail().get("version")).contains("PostgreSQL");
    }

    @Test
    void flywayMigrationEnabledPgvectorExtension() {
        Integer extensions = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(extensions).isEqualTo(1);
    }
}
