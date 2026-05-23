package com.karocharge.integration.citrine.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CitrineChargingStationView {
    String id;
    Boolean isOnline;
    String protocol;
    String chargePointVendor;
    String chargePointModel;
    String connectorStatus;
    String activeTransactionId;
}
