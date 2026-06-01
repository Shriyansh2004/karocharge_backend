package com.karocharge.integration.citrine;

import com.karocharge.integration.citrine.dto.CitrineChargingStationView;
import com.karocharge.integration.citrine.dto.CitrineOperatorChargerView;
import com.karocharge.integration.citrine.dto.CitrineOperatorLocationView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final String OPERATOR_LOCATIONS_QUERY = """
            query OperatorLocations {
              Locations(order_by: { name: asc }) {
                id
                name
                address
                city
                postalCode
                state
                country
                publishUpstream
                parkingType
                facilities
                coordinates
                ChargingStations {
                  id
                  isOnline
                  protocol
                  chargePointVendor
                  chargePointModel
                  firmwareVersion
                  chargePointSerialNumber
                  capabilities
                  createdAt
                  updatedAt
                  Evses {
                    physicalReference
                    Connectors {
                      connectorId
                      type
                      maximumPowerWatts
                      status
                    }
                  }
                  Connectors {
                    connectorId
                    type
                    maximumPowerWatts
                    status
                  }
                  LatestStatusNotifications(limit: 1, order_by: { updatedAt: desc }) {
                    updatedAt
                    StatusNotification {
                      connectorStatus
                    }
                  }
                }
              }
            }
            """;

    @Qualifier("citrineIntegrationRestTemplate")
    private final RestTemplate restTemplate;
    private final CitrineConfig config;

    public List<CitrineOperatorLocationView> fetchOperatorLocations() {
        if (config.getHasuraUrl() == null || config.getHasuraUrl().isBlank()) {
            return Collections.emptyList();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getHasuraAdminSecret() != null && !config.getHasuraAdminSecret().isBlank()) {
                headers.set("x-hasura-admin-secret", config.getHasuraAdminSecret());
            }

            Map<String, Object> body = Map.of("query", OPERATOR_LOCATIONS_QUERY);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    config.getHasuraUrl(),
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            return parseLocations(response.getBody());
        } catch (RestClientException ex) {
            log.warn("event=CITRINE_HASURA_LOCATIONS_FETCH_FAILED message={}", ex.getMessage());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("event=CITRINE_HASURA_LOCATIONS_PARSE_FAILED message={}", ex.getMessage());
            return Collections.emptyList();
        }
    }

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
    private List<CitrineOperatorLocationView> parseLocations(Map<String, Object> root) {
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

        Object locations = dataMap.get("Locations");
        if (!(locations instanceof List<?> locationList)) {
            return Collections.emptyList();
        }

        List<CitrineOperatorLocationView> result = new ArrayList<>();
        for (Object item : locationList) {
            if (!(item instanceof Map<?, ?> location)) {
                continue;
            }
            result.add(parseLocation(location));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private CitrineOperatorLocationView parseLocation(Map<?, ?> location) {
        Double latitude = null;
        Double longitude = null;
        Object coordinates = location.get("coordinates");
        if (coordinates instanceof Map<?, ?> coordMap) {
            Object coords = coordMap.get("coordinates");
            if (coords instanceof List<?> list && list.size() >= 2) {
                longitude = doubleValue(list.get(0));
                latitude = doubleValue(list.get(1));
            }
        }

        List<CitrineOperatorChargerView> chargers = new ArrayList<>();
        Object chargingStations = location.get("ChargingStations");
        if (chargingStations instanceof List<?> stationList) {
            for (Object stationItem : stationList) {
                if (stationItem instanceof Map<?, ?> station) {
                    chargers.add(parseOperatorCharger(station));
                }
            }
        }

        return CitrineOperatorLocationView.builder()
                .id(longValue(location.get("id")))
                .name(stringValue(location.get("name")))
                .address(stringValue(location.get("address")))
                .city(stringValue(location.get("city")))
                .postalCode(stringValue(location.get("postalCode")))
                .state(stringValue(location.get("state")))
                .country(stringValue(location.get("country")))
                .publishUpstream(booleanValue(location.get("publishUpstream")))
                .parkingType(stringValue(location.get("parkingType")))
                .facilities(stringListValue(location.get("facilities")))
                .latitude(latitude)
                .longitude(longitude)
                .chargers(chargers)
                .build();
    }

    @SuppressWarnings("unchecked")
    private CitrineOperatorChargerView parseOperatorCharger(Map<?, ?> station) {
        Set<String> connectorTypes = new LinkedHashSet<>();
        int connectorCount = 0;
        int[] maxPowerHolder = new int[] { Integer.MIN_VALUE };
        String physicalReference = null;

        Object evses = station.get("Evses");
        if (evses instanceof List<?> evseList) {
            for (Object evseItem : evseList) {
                if (!(evseItem instanceof Map<?, ?> evse)) {
                    continue;
                }
                if (physicalReference == null) {
                    physicalReference = stringValue(evse.get("physicalReference"));
                }
                connectorCount += countConnectors(evse.get("Connectors"), connectorTypes, maxPowerHolder);
            }
        }

        Object stationConnectors = station.get("Connectors");
        if (stationConnectors instanceof List<?> list && !list.isEmpty()) {
            connectorCount += countConnectors(stationConnectors, connectorTypes, maxPowerHolder);
        }

        Integer maxPower = maxPowerHolder[0] == Integer.MIN_VALUE ? null : maxPowerHolder[0];

        List<String> capabilities = stringListValue(station.get("capabilities"));
        boolean smartCharger = capabilities.stream()
                .anyMatch(cap -> cap != null && cap.toLowerCase().contains("smart"));

        String stationId = stringValue(station.get("id"));

        return CitrineOperatorChargerView.builder()
                .id(stationId)
                .ocppConnectionName(stationId)
                .isOnline(booleanValue(station.get("isOnline")))
                .protocol(stringValue(station.get("protocol")))
                .chargePointVendor(stringValue(station.get("chargePointVendor")))
                .chargePointModel(stringValue(station.get("chargePointModel")))
                .firmwareVersion(stringValue(station.get("firmwareVersion")))
                .chargePointSerialNumber(stringValue(station.get("chargePointSerialNumber")))
                .capabilities(capabilities)
                .physicalReference(physicalReference)
                .connectorCount(connectorCount)
                .connectorsSupported(connectorTypes.isEmpty() ? null : String.join(", ", connectorTypes))
                .maxPowerWatts(maxPower)
                .connectorStatus(extractConnectorStatus(station))
                .smartCharger(smartCharger)
                .build();
    }

    @SuppressWarnings("unchecked")
    private int countConnectors(Object connectorsObj, Set<String> types, int[] maxPowerHolder) {
        if (!(connectorsObj instanceof List<?> list)) {
            return 0;
        }
        int count = 0;
        for (Object connectorItem : list) {
            if (!(connectorItem instanceof Map<?, ?> connector)) {
                continue;
            }
            count++;
            String type = stringValue(connector.get("type"));
            if (type != null && !type.isBlank()) {
                types.add(type);
            }
            Integer watts = intValue(connector.get("maximumPowerWatts"));
            if (watts != null) {
                maxPowerHolder[0] = Math.max(maxPowerHolder[0], watts);
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(this::stringValue)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toList());
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
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
