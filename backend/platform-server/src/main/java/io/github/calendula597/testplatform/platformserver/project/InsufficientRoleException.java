package io.github.calendula597.testplatform.platformserver.project;

/**
 * Thrown when a member is authenticated but lacks the required project role.
 * Mapped to 403.
 */
public class InsufficientRoleException extends RuntimeException {

    public InsufficientRoleException(String message) {
        super(message);
    }
}
