package io.github.calendula597.testplatform.platformserver.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Probes the configured PostgreSQL datasource with a real query.
 */
@Component
public class DatabaseHealthChecker {

    private final JdbcTemplate jdbcTemplate;

    DatabaseHealthChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ComponentHealth check() {
        long start = System.nanoTime();
        try {
            String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return ComponentHealth.up(latencyMs, Map.of("version", version));
        } catch (Exception e) {
            // A health probe must report DOWN for any failure, never propagate it.
            return ComponentHealth.down(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
