package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatorStationDTO {
    Long id;
    String stationName;
    int chargers;
    int connectors;
    String connectorsSupported;
    String cityDistrict;
    String pincode;
    String state;
    String published;
    String loadCapacity;
    String accessibility;
}
