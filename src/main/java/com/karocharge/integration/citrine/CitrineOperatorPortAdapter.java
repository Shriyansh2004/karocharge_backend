package com.karocharge.integration.citrine;

import com.karocharge.backend.integration.csms.ports.CsmsOperatorPort;
import com.karocharge.backend.integration.csms.ports.model.CsmsChargingStationView;
import com.karocharge.backend.integration.csms.ports.model.CsmsOperatorChargerView;
import com.karocharge.backend.integration.csms.ports.model.CsmsOperatorLocationView;
import com.karocharge.integration.citrine.dto.CitrineChargingStationView;
import com.karocharge.integration.citrine.dto.CitrineOperatorChargerView;
import com.karocharge.integration.citrine.dto.CitrineOperatorLocationView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CitrineOperatorPortAdapter implements CsmsOperatorPort {

    private final CitrineClient citrineClient;
    private final CitrineHasuraClient citrineHasuraClient;
    private final CitrineConfig citrineConfig;

    @Override
    public boolean isHttpReachable() {
        return citrineClient.isReachable();
    }

    @Override
    public List<CsmsChargingStationView> fetchChargingStations() {
        List<CitrineChargingStationView> stations = citrineHasuraClient.fetchChargingStations();
        if (stations == null || stations.isEmpty()) {
            return Collections.emptyList();
        }
        return stations.stream()
                .map(this::mapStation)
                .toList();
    }

    @Override
    public List<CsmsOperatorLocationView> fetchOperatorLocations() {
        List<CitrineOperatorLocationView> locations = citrineHasuraClient.fetchOperatorLocations();
        if (locations == null || locations.isEmpty()) {
            return Collections.emptyList();
        }
        return locations.stream()
                .map(this::mapLocation)
                .toList();
    }

    @Override
    public String baseUrl() {
        return citrineConfig.getBaseUrl();
    }

    @Override
    public String websocketUrl() {
        return citrineConfig.getWebsocketUrl();
    }

    private CsmsChargingStationView mapStation(CitrineChargingStationView s) {
        return CsmsChargingStationView.builder()
                .id(s.getId())
                .isOnline(s.getIsOnline())
                .protocol(s.getProtocol())
                .chargePointVendor(s.getChargePointVendor())
                .chargePointModel(s.getChargePointModel())
                .connectorStatus(s.getConnectorStatus())
                .activeTransactionId(s.getActiveTransactionId())
                .build();
    }

    private CsmsOperatorLocationView mapLocation(CitrineOperatorLocationView l) {
        return CsmsOperatorLocationView.builder()
                .id(l.getId())
                .name(l.getName())
                .address(l.getAddress())
                .city(l.getCity())
                .postalCode(l.getPostalCode())
                .state(l.getState())
                .country(l.getCountry())
                .publishUpstream(l.getPublishUpstream())
                .parkingType(l.getParkingType())
                .facilities(l.getFacilities())
                .latitude(l.getLatitude())
                .longitude(l.getLongitude())
                .chargers(l.getChargers() == null ? List.of() : l.getChargers().stream().map(this::mapOperatorCharger).toList())
                .build();
    }

    private CsmsOperatorChargerView mapOperatorCharger(CitrineOperatorChargerView c) {
        return CsmsOperatorChargerView.builder()
                .id(c.getId())
                .ocppConnectionName(c.getOcppConnectionName())
                .isOnline(c.getIsOnline())
                .protocol(c.getProtocol())
                .chargePointVendor(c.getChargePointVendor())
                .chargePointModel(c.getChargePointModel())
                .firmwareVersion(c.getFirmwareVersion())
                .chargePointSerialNumber(c.getChargePointSerialNumber())
                .capabilities(c.getCapabilities())
                .physicalReference(c.getPhysicalReference())
                .connectorCount(c.getConnectorCount())
                .connectorsSupported(c.getConnectorsSupported())
                .maxPowerWatts(c.getMaxPowerWatts())
                .connectorStatus(c.getConnectorStatus())
                .smartCharger(c.isSmartCharger())
                .build();
    }
}

