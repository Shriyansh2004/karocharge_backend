package com.karocharge.integration.citrine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Data
@Configuration
@ConfigurationProperties(prefix = "citrine")
public class CitrineConfig {

    private String baseUrl = "http://localhost:8080";
    private String websocketUrl = "ws://localhost:8081";
    private Integer tenantId = 1;
    private Integer defaultEvseId = 1;
    private Integer timeoutSeconds = 15;
    private Integer retryAttempts = 2;
    private Integer retryBackoffMillis = 300;

    @Bean(name = "citrineIntegrationRestTemplate")
    public RestTemplate citrineIntegrationRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutSeconds * 1_000);
        return new RestTemplate(factory);
    }
}
