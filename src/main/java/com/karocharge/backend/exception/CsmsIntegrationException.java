package com.karocharge.backend.exception;

/**
 * Provider-agnostic integration exception. Provider adapters may wrap transport/protocol errors into this type.
 * API behavior is preserved via existing exception handlers.
 */
public class CsmsIntegrationException extends RuntimeException {
    public CsmsIntegrationException(String message) {
        super(message);
    }

    public CsmsIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}

