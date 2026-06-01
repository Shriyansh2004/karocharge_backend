package com.karocharge.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "operator")
public class OperatorProperties {
    /**
     * When false, the operator dashboard only shows chargers linked to Citrine OS
     * (live stations from Hasura and DB rows with a matching Citrine station).
     */
    private boolean includeUnlinkedDbChargers = false;
}
