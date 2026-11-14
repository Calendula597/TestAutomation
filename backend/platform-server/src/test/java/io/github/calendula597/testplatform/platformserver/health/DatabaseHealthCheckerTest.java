package io.github.calendula597.testplatform.platformserver.health;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checker must report DOWN with an explicit error when the database is
 * unreachable — never a fabricated success (PRD #1: no simulated results).
 */
class DatabaseHealthCheckerTest {

    @Test
    void reportsDownWhenDatabaseIsUnreachable() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/test_platform", "testplatform", "unused");
        DatabaseHealthChecker checker = new DatabaseHealthChecker(new JdbcTemplate(dataSource));

        ComponentHealth health = checker.check();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.error()).isNotBlank();
        assertThat(health.latencyMs()).isNull();
        assertThat(health.detail()).isNull();
    }
}
