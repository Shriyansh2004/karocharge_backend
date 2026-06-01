package com.karocharge.integration.citrine.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CitrineOperatorLocationView {
    Long id;
    String name;
    String address;
    String city;
    String postalCode;
    String state;
    String country;
    Boolean publishUpstream;
    String parkingType;
    List<String> facilities;
    Double latitude;
    Double longitude;
    List<CitrineOperatorChargerView> chargers;
}
