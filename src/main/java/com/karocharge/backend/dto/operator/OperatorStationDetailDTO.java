package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class OperatorStationDetailDTO {
    Long id;
    String stationName;
    String address;
    String cityDistrict;
    String pincode;
    String state;
    String published;
    String loadCapacity;
    String accessibility;
    String latitude;
    String longitude;
    List<OperatorStationChargerDTO> chargers;
}
