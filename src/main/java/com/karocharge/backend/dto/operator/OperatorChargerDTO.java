package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatorChargerDTO {
    Long id;
    String citrineChargerId;
    String hostName;
    String location;
    String brand;
    String type;
    String status;
    String activeBookingStatus;
    String ocppTransactionId;
    boolean citrineBlocked;
}
