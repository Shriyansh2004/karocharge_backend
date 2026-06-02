package com.karocharge.integration.citrine;

import com.karocharge.backend.exception.CitrineIntegrationException;
import com.karocharge.backend.integration.csms.ports.CsmsChargingPort;
import com.karocharge.backend.integration.csms.ports.model.CsmsStartChargingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class CitrineChargingPortAdapter implements CsmsChargingPort {

    private static final Pattern TXN_PATTERN = Pattern.compile("\"transactionId\"\\s*:\\s*\"([^\"]+)\"");

    private final CitrineClient citrineClient;
    private final CitrineConfig citrineConfig;

    @Override
    public Integer defaultEvseId() {
        return citrineConfig.getDefaultEvseId();
    }

    @Override
    public void blockCharger(String chargerId, Integer evseId) {
        try {
            String response = citrineClient.changeAvailability(chargerId, "Inoperative", evseId);
            ensureChangeAvailabilityAccepted(response, chargerId, "Inoperative");
        } catch (Exception ex) {
            throw new CitrineIntegrationException("Failed to block charger " + chargerId, ex);
        }
    }

    @Override
    public void unblockCharger(String chargerId, Integer evseId) {
        try {
            String response = citrineClient.changeAvailability(chargerId, "Operative", evseId);
            ensureChangeAvailabilityAccepted(response, chargerId, "Operative");
        } catch (Exception ex) {
            throw new CitrineIntegrationException("Failed to unblock charger " + chargerId, ex);
        }
    }

    @Override
    public CsmsStartChargingResult startCharging(String chargerId, Integer remoteStartId, String idToken, Integer evseId) {
        try {
            String response = citrineClient.requestStartTransaction(chargerId, remoteStartId, idToken, evseId);
            String transactionId = extractTransactionId(response);
            return new CsmsStartChargingResult(transactionId, response);
        } catch (Exception ex) {
            throw new CitrineIntegrationException("Failed to start charging for charger " + chargerId, ex);
        }
    }

    @Override
    public void stopCharging(String chargerId, String transactionId) {
        try {
            citrineClient.requestStopTransaction(chargerId, transactionId);
        } catch (Exception ex) {
            throw new CitrineIntegrationException("Failed to stop charging for transaction " + transactionId, ex);
        }
    }

    private void ensureChangeAvailabilityAccepted(String response, String chargerId, String operationalStatus) {
        if (response == null || response.isBlank()) {
            log.info("event=AVAILABILITY_CONFIRMATION_DEFAULT_SUCCESS chargerId={} operationalStatus={} message=Empty response treated as accepted",
                    chargerId, operationalStatus);
            return;
        }
        if (response.contains("\"success\":false")
                || response.contains("\"status\":\"Rejected\"")
                || response.contains("\"status\":\"Faulted\"")
                || response.contains("\"status\":\"Unavailable\"")
                || response.contains("\"status\":\"Occupied\"")) {
            throw new IllegalStateException("ChangeAvailability(" + operationalStatus + ") not accepted for charger "
                    + chargerId + ": " + response);
        }
    }

    private String extractTransactionId(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        Matcher matcher = TXN_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        log.warn("event=TRANSACTION_ID_PARSE_FAILED response={}", response);
        return null;
    }
}

