package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatorStationChargerDTO {
    String chargerName;
    String chargerId;
    String physicalReference;
    String serialNumber;
    String smartCharger;
    String status;
    int connectors;
    String power;
    String vendor;
    String model;
    String firmwareVersion;
    String ocpp;
    String chargingUrl;
    String coordinates;
    String createdOn;
    String statusLastUpdated;
}
