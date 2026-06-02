package com.karocharge.backend.integration.csms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "csms")
public class CsmsProperties {
    /**
     * Active provider key (e.g. "default"). Switching CSMS is a config-only change.
     */
    private String provider = "default";

    /**
     * Provider-specific configs keyed by provider key.
     */
    private Map<String, ProviderProperties> providers = new HashMap<>();

    @Data
    public static class ProviderProperties {
        private String baseUrl;
        private String websocketUrl;
        private String hasuraUrl;
        private String hasuraAdminSecret;
        private Integer tenantId;
        private Integer defaultEvseId;
        private Integer timeoutSeconds;
        private Integer retryAttempts;
        private Integer retryBackoffMillis;

        /**
         * Endpoint paths are provider-defined and configured (no hardcoded API paths in code).
         */
        private Endpoints endpoints = new Endpoints();
    }

    @Data
    public static class Endpoints {
        private String changeAvailabilityPath;
        private String requestStartTransactionPath;
        private String requestStopTransactionPath;
    }
}

