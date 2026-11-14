package io.github.calendula597.testplatform.platformserver.health;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Overall platform health as rendered by the frontend health page.
 * {@code status} is "UP" only when every listed component is really up.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(String status, Instant checkedAt, Map<String, ComponentHealth> components) {
}
