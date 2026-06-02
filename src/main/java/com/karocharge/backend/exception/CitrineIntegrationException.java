package com.karocharge.backend.exception;

/**
 * Backwards-compatible exception type used by the current API error handler.
 * Internally, code should prefer {@link CsmsIntegrationException}.
 */
public class CitrineIntegrationException extends CsmsIntegrationException {
    public CitrineIntegrationException(String message) {
        super(message);
    }

    public CitrineIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
