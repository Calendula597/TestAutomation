package io.github.calendula597.testplatform.platformserver.health;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Health of one component. On failure {@code error} carries the cause and
 * {@code latencyMs}/{@code detail} stay absent, so callers cannot mistake a
 * broken dependency for a healthy one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentHealth(String status, Long latencyMs, Map<String, String> detail, String error) {

    public static ComponentHealth up(long latencyMs, Map<String, String> detail) {
        return new ComponentHealth("UP", latencyMs, detail, null);
    }

    public static ComponentHealth down(String error) {
        return new ComponentHealth("DOWN", null, null, error);
    }
}
