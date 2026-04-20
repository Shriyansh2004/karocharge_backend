package com.karocharge.backend.exception;

public class CitrineIntegrationException extends RuntimeException {
    public CitrineIntegrationException(String message) {
        super(message);
    }

    public CitrineIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
