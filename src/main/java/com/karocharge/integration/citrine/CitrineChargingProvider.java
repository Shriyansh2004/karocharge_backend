package com.karocharge.integration.citrine;

import com.karocharge.backend.integration.ChargingProviderGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "charging.provider", name = "type", havingValue = "citrine", matchIfMissing = true)
public class CitrineChargingProvider implements ChargingProviderGateway {

    private final CitrineClient citrineClient;

    @Override
    public String blockCharger(String chargerId, Integer evseId) {
        return citrineClient.changeAvailability(chargerId, "Inoperative", evseId);
    }

    @Override
    public String unblockCharger(String chargerId, Integer evseId) {
        return citrineClient.changeAvailability(chargerId, "Operative", evseId);
    }

    @Override
    public String requestStartTransaction(String chargerId, Integer remoteStartId, String idToken, Integer evseId) {
        return citrineClient.requestStartTransaction(chargerId, remoteStartId, idToken, evseId);
    }

    @Override
    public String requestStopTransaction(String chargerId, String transactionId) {
        return citrineClient.requestStopTransaction(chargerId, transactionId);
    }
}
