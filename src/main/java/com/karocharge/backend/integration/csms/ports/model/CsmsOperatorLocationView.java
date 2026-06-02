package com.karocharge.backend.integration.csms.ports.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CsmsOperatorLocationView {
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
    List<CsmsOperatorChargerView> chargers;
}

