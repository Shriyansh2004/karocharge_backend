package com.karocharge.integration.citrine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CitrineClient {

    private static final String CHANGE_AVAILABILITY_PATH = "/ocpp/2.0.1/configuration/changeAvailability";
    private static final String REQUEST_START_TRANSACTION_PATH = "/ocpp/2.0.1/evdriver/requestStartTransaction";
    private static final String REQUEST_STOP_TRANSACTION_PATH = "/ocpp/2.0.1/evdriver/requestStopTransaction";

    @Qualifier("citrineIntegrationRestTemplate")
    private final RestTemplate restTemplate;
    private final CitrineConfig config;

    public String changeAvailability(String chargerId, String operationalStatus, Integer evseId) {
        Map<String, Object> request = new HashMap<>();
        request.put("operationalStatus", operationalStatus);
        if (evseId != null) {
            request.put("evse", Map.of("id", evseId));
        }
        return post(chargerId, CHANGE_AVAILABILITY_PATH, request, "ChangeAvailability");
    }

    public String requestStartTransaction(String chargerId, Integer remoteStartId, String idToken, Integer evseId) {
        Map<String, Object> request = new HashMap<>();
        request.put("remoteStartId", remoteStartId);
        request.put("idToken", Map.of("idToken", idToken, "type", "Central"));
        if (evseId != null) {
            request.put("evseId", evseId);
        }
        return post(chargerId, REQUEST_START_TRANSACTION_PATH, request, "RequestStartTransaction");
    }

    public String requestStopTransaction(String chargerId, String transactionId) {
        Map<String, Object> request = new HashMap<>();
        request.put("transactionId", transactionId);
        return post(chargerId, REQUEST_STOP_TRANSACTION_PATH, request, "RequestStopTransaction");
    }

    private String post(String chargerId, String path, Object request, String action) {
        log.info("event=CITRINE_REQUEST action={} chargerId={} path={} payload={}", action, chargerId, path, request);

        String url = buildUrl(path, chargerId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(request, headers);

        int attempts = Math.max(1, config.getRetryAttempts() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String response = restTemplate.postForObject(url, entity, String.class);
                log.info("event=CITRINE_RESPONSE action={} chargerId={} response={}", action, chargerId, response);
                return response;
            } catch (HttpServerErrorException | ResourceAccessException ex) {
                if (attempt == attempts) {
                    log.error("event=CITRINE_FAILURE action={} chargerId={} message={}", action, chargerId, ex.getMessage(), ex);
                    break;
                }
                sleepBackoff();
            } catch (RestClientException ex) {
                log.error("event=CITRINE_FAILURE action={} chargerId={} message={}", action, chargerId, ex.getMessage(), ex);
                throw new IllegalStateException("Citrine call failed for action " + action + ": " + ex.getMessage(), ex);
            }
        }

        log.error("event=CITRINE_FALLBACK action={} chargerId={} message=Request failed after retries", action, chargerId);
        throw new IllegalStateException("Citrine call failed for action " + action + " after retries");
    }

    private String buildUrl(String path, String chargerId) {
        String baseUrl = config.getBaseUrl().replaceAll("/$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + normalizedPath + "?identifier=" + chargerId + "&tenantId=" + config.getTenantId();
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(Math.max(0, config.getRetryBackoffMillis()));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
