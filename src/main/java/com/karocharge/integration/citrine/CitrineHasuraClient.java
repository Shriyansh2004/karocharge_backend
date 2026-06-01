package com.karocharge.integration.citrine;

import com.karocharge.integration.citrine.dto.CitrineChargingStationView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CitrineHasuraClient {

    private static final String CHARGING_STATIONS_QUERY = """
            query OperatorChargingStations {
              ChargingStations {
                id
                isOnline
                protocol
                chargePointVendor
                chargePointModel
                transactions: Transactions(where: { isActive: { _eq: true } }) {
                  transactionId
                }
                LatestStatusNotifications(limit: 1, order_by: { updatedAt: desc }) {
                  StatusNotification {
                    connectorStatus
                  }
                }
              }
            }
            """;

    @Qualifier("citrineIntegrationRestTemplate")
    private final RestTemplate restTemplate;
    private final CitrineConfig config;

    public List<CitrineChargingStationView> fetchChargingStations() {
        if (config.getHasuraUrl() == null || config.getHasuraUrl().isBlank()) {
            return Collections.emptyList();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getHasuraAdminSecret() != null && !config.getHasuraAdminSecret().isBlank()) {
                headers.set("x-hasura-admin-secret", config.getHasuraAdminSecret());
            }

            Map<String, Object> body = Map.of("query", CHARGING_STATIONS_QUERY);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    config.getHasuraUrl(),
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            return parseStations(response.getBody());
        } catch (RestClientException ex) {
            log.warn("event=CITRINE_HASURA_FETCH_FAILED message={}", ex.getMessage());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("event=CITRINE_HASURA_PARSE_FAILED message={}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<CitrineChargingStationView> parseStations(Map<String, Object> root) {
        if (root == null) {
            return Collections.emptyList();
        }
        if (root.containsKey("errors")) {
            log.warn("event=CITRINE_HASURA_GRAPHQL_ERROR body={}", root.get("errors"));
            return Collections.emptyList();
        }

        Object data = root.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return Collections.emptyList();
        }

        Object stations = dataMap.get("ChargingStations");
        if (!(stations instanceof List<?> stationList)) {
            return Collections.emptyList();
        }

        List<CitrineChargingStationView> result = new ArrayList<>();
        for (Object item : stationList) {
            if (!(item instanceof Map<?, ?> station)) {
                continue;
            }

            String connectorStatus = extractConnectorStatus(station);
            String activeTransactionId = extractActiveTransactionId(station);

            result.add(CitrineChargingStationView.builder()
                    .id(stringValue(station.get("id")))
                    .isOnline(booleanValue(station.get("isOnline")))
                    .protocol(stringValue(station.get("protocol")))
                    .chargePointVendor(stringValue(station.get("chargePointVendor")))
                    .chargePointModel(stringValue(station.get("chargePointModel")))
                    .connectorStatus(connectorStatus)
                    .activeTransactionId(activeTransactionId)
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractConnectorStatus(Map<?, ?> station) {
        Object latest = station.get("LatestStatusNotifications");
        if (!(latest instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> latestMap)) {
            return null;
        }
        Object notification = latestMap.get("StatusNotification");
        if (!(notification instanceof Map<?, ?> statusMap)) {
            return null;
        }
        return stringValue(statusMap.get("connectorStatus"));
    }

    @SuppressWarnings("unchecked")
    private String extractActiveTransactionId(Map<?, ?> station) {
        Object transactions = station.get("transactions");
        if (!(transactions instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> txnMap)) {
            return null;
        }
        return stringValue(txnMap.get("transactionId"));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return null;
    }
}
