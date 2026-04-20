package com.karocharge.backend.integration;

public interface ChargingProviderGateway {
    String blockCharger(String chargerId, Integer evseId);

    String unblockCharger(String chargerId, Integer evseId);

    String requestStartTransaction(String chargerId, Integer remoteStartId, String idToken, Integer evseId);

    String requestStopTransaction(String chargerId, String transactionId);
}
