package io.github.calendula597.testplatform.platformserver.project;

/**
 * Thrown for project resources the caller may not know exist. Mapped to 404
 * so existence is not leaked to non-members.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
