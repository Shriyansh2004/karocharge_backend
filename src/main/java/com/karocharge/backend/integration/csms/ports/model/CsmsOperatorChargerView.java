package com.karocharge.backend.integration.csms.ports.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CsmsOperatorChargerView {
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

