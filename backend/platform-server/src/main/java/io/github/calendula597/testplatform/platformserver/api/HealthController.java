package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.health.ComponentHealth;
import io.github.calendula597.testplatform.platformserver.health.DatabaseHealthChecker;
import io.github.calendula597.testplatform.platformserver.health.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Public health endpoint consumed by the Vue health page (T01).
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final DatabaseHealthChecker databaseHealthChecker;

    HealthController(DatabaseHealthChecker databaseHealthChecker) {
        this.databaseHealthChecker = databaseHealthChecker;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        ComponentHealth database = databaseHealthChecker.check();
        String overall = "UP".equals(database.status()) ? "UP" : "DOWN";
        return new HealthResponse(overall, Instant.now(), Map.of("database", database));
    }
}
