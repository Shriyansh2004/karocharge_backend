package com.karocharge.integration.citrine.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CitrineOperatorChargerView {
    String id;
    String ocppConnectionName;
    Boolean isOnline;
    String protocol;
    String chargePointVendor;
    String chargePointModel;
    String firmwareVersion;
    String chargePointSerialNumber;
    List<String> capabilities;
    String physicalReference;
    int connectorCount;
    String connectorsSupported;
    Integer maxPowerWatts;
    String connectorStatus;
    boolean smartCharger;
}
