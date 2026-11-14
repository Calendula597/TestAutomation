package io.github.calendula597.testplatform.platformserver.api;

/**
 * Shared identity API payloads. Never include password hashes or session
 * identifiers.
 */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record InitializeRequest(String username, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record StatusRequest(UserStatusDto status) {
    }

    public enum UserStatusDto {
        ACTIVE, DISABLED
    }

    public record UserView(Long id, String username, String role, String status, String displayName) {
    }

    public record MeResponse(String username, String role) {
    }

    public record SystemStatus(boolean initialized) {
    }

    /** Uniform error body: HTTP status mirrors {@code status}. */
    public record ErrorResponse(int status, String message) {
    }
}
