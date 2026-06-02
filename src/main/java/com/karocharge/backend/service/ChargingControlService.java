package com.karocharge.backend.service;

import com.karocharge.backend.exception.CitrineIntegrationException;
import com.karocharge.backend.integration.csms.CsmsProviderSelector;
import com.karocharge.backend.integration.csms.ports.CsmsChargingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChargingControlService {

    private static final Map<String, BlockReservationContext> ACTIVE_RESERVATIONS = new ConcurrentHashMap<>();

    private final CsmsProviderSelector csmsProviderSelector;

    public void blockCharger(String chargerId) {
        blockCharger(chargerId, "anonymous-user", "session-" + chargerId + "-" + System.currentTimeMillis(), 30);
    }

    public void blockCharger(String chargerId, String userId, String sessionId, Integer durationMinutes) {
        executeBlock(chargerId, userId, sessionId);
    }

    public void unblockCharger(String chargerId) {
        String normalizedChargerId = normalizeChargerId(chargerId);
        BlockReservationContext context = ACTIVE_RESERVATIONS.remove(normalizedChargerId);
        if (context == null) {
            log.warn("event=UNBLOCK_SKIPPED chargerId={} message=No active reservation found", chargerId);
            return;
        }
        executeUnblock(chargerId);
    }

    public StartChargingResult startCharging(String chargerId, String userId) {
        validateStartAuthorization(chargerId, userId);
        Integer remoteStartId = Math.abs((chargerId + userId).hashCode());

        try {
            CsmsChargingPort charging = csmsProviderSelector.current().charging();
            var response = charging.startCharging(
                    chargerId,
                    remoteStartId,
                    userId,
                    charging.defaultEvseId()
            );
            String transactionId = response.transactionId();
            if (transactionId == null || transactionId.isBlank()) {
                transactionId = generateFallbackTransactionId(chargerId);
            }
            log.info("event=CHARGING_CONTROL_SUCCESS action=START chargerId={} userId={} transactionId={}",
                    chargerId, userId, transactionId);
            return new StartChargingResult(transactionId, LocalDateTime.now(), response.rawResponse());
        } catch (Exception ex) {
            log.error("event=CHARGING_CONTROL_FAILED action=START chargerId={} userId={} message={}",
                    chargerId, userId, ex.getMessage(), ex);
            throw new CitrineIntegrationException("Failed to start charging for charger " + chargerId, ex);
        }
    }

    public void stopCharging(String chargerId, String transactionId) {
        try {
            csmsProviderSelector.current().charging().stopCharging(chargerId, transactionId);
            log.info("event=CHARGING_CONTROL_SUCCESS action=STOP chargerId={} transactionId={}", chargerId, transactionId);
        } catch (Exception ex) {
            log.error("event=CHARGING_CONTROL_FAILED action=STOP chargerId={} transactionId={} message={}",
                    chargerId, transactionId, ex.getMessage(), ex);
            throw new CitrineIntegrationException("Failed to stop charging for transaction " + transactionId, ex);
        }
    }

    private void executeBlock(String chargerId, String userId, String sessionId) {
        try {
            CsmsChargingPort charging = csmsProviderSelector.current().charging();
            charging.blockCharger(chargerId, charging.defaultEvseId());
            String normalizedChargerId = normalizeChargerId(chargerId);
            ACTIVE_RESERVATIONS.put(normalizedChargerId, new BlockReservationContext(userId, sessionId));
            log.info("event=BLOCK_STATE_SAVED chargerId={} normalizedChargerId={}", chargerId, normalizedChargerId);
            log.info("event=CHARGING_CONTROL_SUCCESS action=BLOCK chargerId={}", chargerId);
        } catch (Exception ex) {
            log.error("event=CHARGING_CONTROL_FAILED action=BLOCK chargerId={} message={}",
                    chargerId, ex.getMessage(), ex);
            throw new CitrineIntegrationException("Failed to block charger " + chargerId, ex);
        }
    }

    private void executeUnblock(String chargerId) {
        try {
            CsmsChargingPort charging = csmsProviderSelector.current().charging();
            charging.unblockCharger(chargerId, charging.defaultEvseId());
            log.info("event=CHARGING_CONTROL_SUCCESS action=UNBLOCK chargerId={}", chargerId);
        } catch (Exception ex) {
            log.error("event=CHARGING_CONTROL_FAILED action=UNBLOCK chargerId={} message={}",
                    chargerId, ex.getMessage(), ex);
            throw new CitrineIntegrationException("Failed to unblock charger " + chargerId, ex);
        }
    }

    private void validateStartAuthorization(String chargerId, String userId) {
        String normalizedChargerId = normalizeChargerId(chargerId);
        BlockReservationContext context = ACTIVE_RESERVATIONS.get(normalizedChargerId);
        if (context == null) {
            log.info("event=BLOCK_STATE_NOT_FOUND chargerId={} normalizedChargerId={} message=No active reservation in memory",
                    chargerId, normalizedChargerId);
            return;
        }
        log.warn("event=BLOCK_STATE_ACTIVE chargerId={} normalizedChargerId={} message=Start blocked until unblock",
                chargerId, normalizedChargerId);
        throw new CitrineIntegrationException("Charger " + chargerId + " is blocked/reserved and cannot start until unblocked");
    }

    private String normalizeChargerId(String chargerId) {
        return chargerId == null ? "" : chargerId.trim().toUpperCase();
    }

    private String generateFallbackTransactionId(String chargerId) {
        String transactionId = "TXN-" + chargerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.warn("event=TRANSACTION_ID_FALLBACK chargerId={} transactionId={}", chargerId, transactionId);
        return transactionId;
    }

    public record StartChargingResult(String transactionId, LocalDateTime heartbeatAt, String rawResponse) {
    }

    private record BlockReservationContext(String userId, String sessionId) {
    }
}
