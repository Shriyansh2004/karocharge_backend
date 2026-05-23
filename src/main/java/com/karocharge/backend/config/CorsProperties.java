package com.karocharge.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "karocharge.cors")
public class CorsProperties {
    private String allowedOrigins = "http://localhost:3000,http://localhost:5173";
}
