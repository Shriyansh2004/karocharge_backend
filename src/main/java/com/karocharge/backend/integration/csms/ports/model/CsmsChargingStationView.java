package com.karocharge.backend.integration.csms.ports.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CsmsChargingStationView {
    String id;
    Boolean isOnline;
    String protocol;
    String chargePointVendor;
    String chargePointModel;
    String connectorStatus;
    String activeTransactionId;
}

