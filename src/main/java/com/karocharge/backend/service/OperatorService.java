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
import com.karocharge.integration.citrine.CitrineHasuraClient;
import com.karocharge.integration.citrine.dto.CitrineChargingStationView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperatorService {

    private static final String OPERATOR_USER_ID = "karocharge-operator";
    private static final Set<String> ACTIVE_BOOKING_STATUSES = Set.of("BOOKED", "CHARGING", "PENDING");

    private final ChargerRepository chargerRepository;
    private final BookingRepository bookingRepository;
    private final ChargingControlService chargingControlService;
    private final CitrineClient citrineClient;
    private final CitrineHasuraClient citrineHasuraClient;
    private final CitrineConfig citrineConfig;

    public OperatorConnectionStatusDTO getConnectionStatus() {
        boolean citrineHttpReachable = citrineClient.isReachable();
        List<CitrineChargingStationView> citrineStations = citrineHasuraClient.fetchChargingStations();
        boolean hasuraReachable = !citrineStations.isEmpty();
        boolean connected = citrineHttpReachable || hasuraReachable;

        String citrineStatus = connected ? "CONNECTED" : "DISCONNECTED";
        String message = connected
                ? "KaroCharge backend is connected to Citrine OS"
                : "KaroCharge backend cannot reach Citrine OS (check HTTP :8080 and Hasura :8090)";

        return OperatorConnectionStatusDTO.builder()
                .backendStatus("UP")
                .citrineStatus(citrineStatus)
                .citrineBaseUrl(citrineConfig.getBaseUrl())
                .citrineWebsocketUrl(citrineConfig.getWebsocketUrl())
                .message(message)
                .checkedAtEpochMs(System.currentTimeMillis())
                .build();
    }

    public List<OperatorChargerDTO> listChargers() {
        Map<String, CitrineChargingStationView> citrineById = citrineHasuraClient.fetchChargingStations().stream()
                .collect(Collectors.toMap(CitrineChargingStationView::getId, Function.identity(), (a, b) -> a));

        Set<String> matchedCitrineIds = new HashSet<>();
        List<OperatorChargerDTO> result = new ArrayList<>();

        for (Charger charger : chargerRepository.findAll()) {
            String citrineId = resolveCitrineId(charger);
            CitrineChargingStationView citrine = citrineById.get(citrineId);
            if (citrine != null) {
                matchedCitrineIds.add(citrineId);
            }
            result.add(mergeDbAndCitrine(charger, citrine, citrineId));
        }

        for (CitrineChargingStationView citrine : citrineById.values()) {
            if (!matchedCitrineIds.contains(citrine.getId())) {
                result.add(fromCitrineOnly(citrine));
            }
        }

        return result;
    }

    @Transactional
    public OperatorActionResultDTO blockCharger(String citrineChargerId) {
        try {
            String sessionId = "operator-block-" + citrineChargerId + "-" + System.currentTimeMillis();
            chargingControlService.blockCharger(citrineChargerId, OPERATOR_USER_ID, sessionId, 30);
            findDbCharger(citrineChargerId).ifPresent(charger -> {
                charger.setStatus("BLOCKED");
                chargerRepository.save(charger);
            });
            return success("BLOCK", citrineChargerId, "Charger blocked via Citrine OS", null);
        } catch (Exception ex) {
            return failure("BLOCK", citrineChargerId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO unblockCharger(String citrineChargerId) {
        try {
            chargingControlService.unblockCharger(citrineChargerId);
            findDbCharger(citrineChargerId).ifPresent(charger -> {
                charger.setStatus("AVAILABLE");
                chargerRepository.save(charger);
            });
            return success("UNBLOCK", citrineChargerId, "Charger unblocked via Citrine OS", null);
        } catch (Exception ex) {
            return failure("UNBLOCK", citrineChargerId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO startCharging(String citrineChargerId) {
        try {
            ChargingControlService.StartChargingResult result =
                    chargingControlService.startCharging(citrineChargerId, OPERATOR_USER_ID);

            findDbCharger(citrineChargerId).ifPresent(charger -> {
                charger.setStatus("CHARGING");
                chargerRepository.save(charger);
                upsertChargingBooking(charger, result.transactionId());
            });

            return success("START", citrineChargerId, "Charging started via Citrine OS", result.transactionId());
        } catch (CitrineIntegrationException ex) {
            return failure("START", citrineChargerId, ex.getMessage());
        }
    }

    @Transactional
    public OperatorActionResultDTO stopCharging(String citrineChargerId) {
        String transactionId = resolveTransactionId(citrineChargerId);
        if (transactionId == null || transactionId.isBlank()) {
            return failure("STOP", citrineChargerId, "No active OCPP transaction found for this charger");
        }

        try {
            chargingControlService.stopCharging(citrineChargerId, transactionId);

            findDbCharger(citrineChargerId).ifPresent(charger -> {
                charger.setStatus("AVAILABLE");
                chargerRepository.save(charger);
                findActiveChargingBooking(charger.getId()).ifPresent(booking -> {
                    booking.setStatus("COMPLETED");
                    booking.setChargerStatus("AVAILABLE");
                    bookingRepository.save(booking);
                });
            });

            return success("STOP", citrineChargerId, "Charging stopped via Citrine OS", transactionId);
        } catch (CitrineIntegrationException ex) {
            return failure("STOP", citrineChargerId, ex.getMessage());
        }
    }

    private String resolveTransactionId(String citrineChargerId) {
        Optional<Booking> dbBooking = findDbCharger(citrineChargerId)
                .flatMap(charger -> findActiveChargingBooking(charger.getId()));
        if (dbBooking.map(Booking::getOcppTransactionId).filter(id -> !id.isBlank()).isPresent()) {
            return dbBooking.get().getOcppTransactionId();
        }

        return citrineHasuraClient.fetchChargingStations().stream()
                .filter(s -> citrineChargerId.equals(s.getId()))
                .map(CitrineChargingStationView::getActiveTransactionId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    private OperatorChargerDTO mergeDbAndCitrine(Charger charger, CitrineChargingStationView citrine, String citrineId) {
        Optional<Booking> activeBooking = findActiveBooking(charger.getId());
        String bookingStatus = activeBooking.map(Booking::getStatus).orElse(null);
        String transactionId = activeBooking.map(Booking::getOcppTransactionId).orElse(null);
        boolean citrineBlocked = "BLOCKED".equalsIgnoreCase(charger.getStatus())
                || "BOOKED".equalsIgnoreCase(charger.getStatus());

        String source = citrine != null ? "LINKED" : "KAROCHARGE";
        String status = deriveStatus(charger.getStatus(), citrine);
        String ocppTxn = transactionId != null ? transactionId
                : citrine != null ? citrine.getActiveTransactionId() : null;

        return OperatorChargerDTO.builder()
                .id(charger.getId())
                .citrineChargerId(citrineId)
                .source(source)
                .hostName(charger.getHostName())
                .location(charger.getLocation())
                .brand(charger.getBrand())
                .type(charger.getType())
                .status(status)
                .activeBookingStatus(bookingStatus)
                .ocppTransactionId(ocppTxn)
                .citrineBlocked(citrineBlocked)
                .citrineOnline(citrine != null ? citrine.getIsOnline() : null)
                .protocol(citrine != null ? citrine.getProtocol() : null)
                .connectorStatus(citrine != null ? citrine.getConnectorStatus() : null)
                .build();
    }

    private OperatorChargerDTO fromCitrineOnly(CitrineChargingStationView citrine) {
        String status = mapConnectorStatus(citrine.getConnectorStatus());
        if (citrine.getActiveTransactionId() != null && !citrine.getActiveTransactionId().isBlank()) {
            status = "CHARGING";
        }

        return OperatorChargerDTO.builder()
                .id(null)
                .citrineChargerId(citrine.getId())
                .source("CITRINE")
                .hostName(citrine.getChargePointVendor())
                .location("Citrine OS")
                .brand(citrine.getChargePointVendor())
                .type(citrine.getChargePointModel())
                .status(status)
                .activeBookingStatus(citrine.getActiveTransactionId() != null ? "CHARGING" : null)
                .ocppTransactionId(citrine.getActiveTransactionId())
                .citrineBlocked("Unavailable".equalsIgnoreCase(citrine.getConnectorStatus()))
                .citrineOnline(citrine.getIsOnline())
                .protocol(citrine.getProtocol())
                .connectorStatus(citrine.getConnectorStatus())
                .build();
    }

    private String deriveStatus(String dbStatus, CitrineChargingStationView citrine) {
        if (citrine == null) {
            return dbStatus;
        }
        if (citrine.getActiveTransactionId() != null && !citrine.getActiveTransactionId().isBlank()) {
            return "CHARGING";
        }
        if (Boolean.FALSE.equals(citrine.getIsOnline())) {
            return "OFFLINE";
        }
        return dbStatus != null ? dbStatus : mapConnectorStatus(citrine.getConnectorStatus());
    }

    private String mapConnectorStatus(String connectorStatus) {
        if (connectorStatus == null) {
            return "UNKNOWN";
        }
        return switch (connectorStatus.toLowerCase()) {
            case "available" -> "AVAILABLE";
            case "occupied", "charging" -> "CHARGING";
            case "unavailable", "faulted" -> "BLOCKED";
            default -> connectorStatus.toUpperCase();
        };
    }

    private Optional<Charger> findDbCharger(String citrineChargerId) {
        return chargerRepository.findAll().stream()
                .filter(c -> citrineChargerId.equals(resolveCitrineId(c)))
                .findFirst();
    }

    private String resolveCitrineId(Charger charger) {
        if (charger.getOcppStationId() != null && !charger.getOcppStationId().isBlank()) {
            return charger.getOcppStationId().trim();
        }
        return String.valueOf(charger.getId());
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
