package com.karocharge.backend.integration.csms.ports;

import com.karocharge.backend.integration.csms.ports.model.CsmsStartChargingResult;

public interface CsmsChargingPort {
    /**
     * Provider-configured default EVSE id (if applicable).
     * Business logic must not depend on any provider-specific concept beyond this neutral configuration knob.
     */
    Integer defaultEvseId();

    /**
     * Blocks a charger (e.g. makes it unavailable).
     */
    void blockCharger(String chargerId, Integer evseId);

    /**
     * Unblocks a charger (e.g. makes it available).
     */
    void unblockCharger(String chargerId, Integer evseId);

    /**
     * Starts charging remotely. Returns a provider transaction id (normalized as a string).
     */
    CsmsStartChargingResult startCharging(String chargerId, Integer remoteStartId, String idToken, Integer evseId);

    /**
     * Stops charging remotely for a given provider transaction id.
     */
    void stopCharging(String chargerId, String transactionId);
}

