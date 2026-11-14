package io.github.calendula597.testplatform.platformserver.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired JDBC sessions; without this the SPRING_SESSION table
 * grows without bound.
 */
@Component
public class ExpiredSessionCleaner {

    private final JdbcTemplate jdbcTemplate;

    public ExpiredSessionCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void removeExpiredSessions() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < ?", System.currentTimeMillis());
    }
}
