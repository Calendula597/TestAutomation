package io.github.calendula597.testplatform.platformserver.automation;

public class CiException extends RuntimeException {
    private final int status;

    public CiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
