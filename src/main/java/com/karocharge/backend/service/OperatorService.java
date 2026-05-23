package com.karocharge.backend.service;

import com.karocharge.backend.dto.operator.OperatorActionResultDTO;
import com.karocharge.backend.dto.operator.OperatorChargerDTO;
import com.karocharge.backend.dto.operator.OperatorConnectionStatusDTO;
import com.karocharge.backend.exception.CitrineIntegrationException;
import com.karocharge.backend.model.Booking;
import com.karocharge.backend.model.Charger;
import com.karocharge.backend.repository.BookingRepository;
import com.karocharge.backend.repository.ChargerRepository;
import com.karocharge.integration.citrine.CitrineClient;
import com.karocharge.integration.citrine.CitrineConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OperatorService {

    private static final String OPERATOR_USER_ID = "karocharge-operator";
    private static final Set<String> ACTIVE_BOOKING_STATUSES = Set.of("BOOKED", "CHARGING", "PENDING");

    private final ChargerRepository chargerRepository;
    private final BookingRepository bookingRepository;
    private final ChargingControlService chargingControlService;
    private final CitrineClient citrineClient;
    private final CitrineConfig citrineConfig;

    public OperatorConnectionStatusDTO getConnectionStatus() {
        boolean citrineReachable = citrineClient.isReachable();
        String citrineStatus = citrineReachable ? "CONNECTED" : "DISCONNECTED";

        return OperatorConnectionStatusDTO.builder()
                .backendStatus("UP")
                .citrineStatus(citrineStatus)
                .citrineBaseUrl(citrineConfig.getBaseUrl())
                .citrineWebsocketUrl(citrineConfig.getWebsocketUrl())
                .message(citrineReachable
                        ? "KaroCharge backend is connected to Citrine OS"
                        : "KaroCharge backend cannot reach Citrine OS")
                .checkedAtEpochMs(System.currentTimeMillis())
                .build();
    }

    public List<OperatorChargerDTO> listChargers() {
        return chargerRepository.findAll().stream()
                .map(this::toOperatorCharger)
                .toList();
    }

    @Transactional
    public OperatorActionResultDTO blockCharger(Long chargerId) {
        Charger charger = requireCharger(chargerId);
        String citrineId = toCitrineId(chargerId);

        try {
            String sessionId = "operator-block-" + chargerId + "-" + System.currentTimeMillis();
            chargingControlService.blockCharger(citrineId, OPERATOR_USER_ID, sessionId, 30);
            charger.setStatus("BLOCKED");
            chargerRepository.save(charger);
            return success("BLOCK", citrineId, "Charger blocked via Citrine OS", null);
        } catch (Exception ex) {
            return failure("BLOCK", citrineId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO unblockCharger(Long chargerId) {
        Charger charger = requireCharger(chargerId);
        String citrineId = toCitrineId(chargerId);

        try {
            chargingControlService.unblockCharger(citrineId);
            charger.setStatus("AVAILABLE");
            chargerRepository.save(charger);
            return success("UNBLOCK", citrineId, "Charger unblocked via Citrine OS", null);
        } catch (Exception ex) {
            return failure("UNBLOCK", citrineId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO startCharging(Long chargerId) {
        Charger charger = requireCharger(chargerId);
        String citrineId = toCitrineId(chargerId);

        try {
            ChargingControlService.StartChargingResult result =
                    chargingControlService.startCharging(citrineId, OPERATOR_USER_ID);

            charger.setStatus("CHARGING");
            chargerRepository.save(charger);

            upsertChargingBooking(charger, result.transactionId());

            return success("START", citrineId, "Charging started via Citrine OS", result.transactionId());
        } catch (CitrineIntegrationException ex) {
            return failure("START", citrineId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO stopCharging(Long chargerId) {
        Charger charger = requireCharger(chargerId);
        String citrineId = toCitrineId(chargerId);

        Optional<Booking> activeBooking = findActiveChargingBooking(chargerId);
        String transactionId = activeBooking
                .map(Booking::getOcppTransactionId)
                .orElse(null);

        if (transactionId == null || transactionId.isBlank()) {
            return failure("STOP", citrineId, "No active OCPP transaction found for this charger");
        }

        try {
            chargingControlService.stopCharging(citrineId, transactionId);
            charger.setStatus("AVAILABLE");
            chargerRepository.save(charger);

            activeBooking.ifPresent(booking -> {
                booking.setStatus("COMPLETED");
                booking.setChargerStatus("AVAILABLE");
                bookingRepository.save(booking);
            });

            return success("STOP", citrineId, "Charging stopped via Citrine OS", transactionId);
        } catch (CitrineIntegrationException ex) {
            return failure("STOP", citrineId, ex.getMessage());
        }
    }

    private void upsertChargingBooking(Charger charger, String transactionId) {
        List<Booking> bookings = bookingRepository.findByChargerId(charger.getId());
        Booking target = bookings.stream()
                .filter(b -> ACTIVE_BOOKING_STATUSES.contains(b.getStatus()))
                .findFirst()
                .orElseGet(() -> {
                    Booking booking = new Booking(charger, null, 30, "CHARGING");
                    booking.setUserName(OPERATOR_USER_ID);
                    return booking;
                });

        target.setStatus("CHARGING");
        target.setOcppTransactionId(transactionId);
        target.setChargerStatus("CHARGING");
        bookingRepository.save(target);
    }

    private Optional<Booking> findActiveChargingBooking(Long chargerId) {
        Booking charging = bookingRepository
                .findTopByChargerIdAndStatusOrderByStartTimeDesc(chargerId, "CHARGING");
        if (charging != null) {
            return Optional.of(charging);
        }
        return bookingRepository.findActiveSessions(chargerId).stream().findFirst();
    }

    private OperatorChargerDTO toOperatorCharger(Charger charger) {
        Optional<Booking> activeBooking = findActiveBooking(charger.getId());
        String bookingStatus = activeBooking.map(Booking::getStatus).orElse(null);
        String transactionId = activeBooking.map(Booking::getOcppTransactionId).orElse(null);
        boolean citrineBlocked = "BLOCKED".equalsIgnoreCase(charger.getStatus())
                || "BOOKED".equalsIgnoreCase(charger.getStatus());

        return OperatorChargerDTO.builder()
                .id(charger.getId())
                .citrineChargerId(toCitrineId(charger.getId()))
                .hostName(charger.getHostName())
                .location(charger.getLocation())
                .brand(charger.getBrand())
                .type(charger.getType())
                .status(charger.getStatus())
                .activeBookingStatus(bookingStatus)
                .ocppTransactionId(transactionId)
                .citrineBlocked(citrineBlocked)
                .build();
    }

    private Optional<Booking> findActiveBooking(Long chargerId) {
        Booking charging = bookingRepository
                .findTopByChargerIdAndStatusOrderByStartTimeDesc(chargerId, "CHARGING");
        if (charging != null) {
            return Optional.of(charging);
        }
        return bookingRepository.findByChargerId(chargerId).stream()
                .filter(b -> ACTIVE_BOOKING_STATUSES.contains(b.getStatus()))
                .findFirst();
    }

    private Charger requireCharger(Long chargerId) {
        return chargerRepository.findById(chargerId)
                .orElseThrow(() -> new IllegalArgumentException("Charger not found: " + chargerId));
    }

    private String toCitrineId(Long chargerId) {
        return String.valueOf(chargerId);
    }

    private OperatorActionResultDTO success(String action, String chargerId, String message, String transactionId) {
        return OperatorActionResultDTO.builder()
                .action(action)
                .chargerId(chargerId)
                .success(true)
                .message(message)
                .transactionId(transactionId)
                .build();
    }

    private OperatorActionResultDTO failure(String action, String chargerId, String message) {
        return OperatorActionResultDTO.builder()
                .action(action)
                .chargerId(chargerId)
                .success(false)
                .message(message)
                .build();
    }
}
