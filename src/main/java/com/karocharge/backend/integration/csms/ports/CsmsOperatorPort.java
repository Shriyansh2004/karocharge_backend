package com.karocharge.backend.integration.csms.ports;

import com.karocharge.backend.integration.csms.ports.model.CsmsChargingStationView;
import com.karocharge.backend.integration.csms.ports.model.CsmsOperatorLocationView;

import java.util.List;

/**
 * Operator-facing CSMS read operations (status, stations, locations).
 * Returns provider-neutral views that preserve the current operator dashboard behavior.
 */
public interface CsmsOperatorPort {
    boolean isHttpReachable();

    List<CsmsChargingStationView> fetchChargingStations();

    List<CsmsOperatorLocationView> fetchOperatorLocations();

    String baseUrl();

    String websocketUrl();
}

