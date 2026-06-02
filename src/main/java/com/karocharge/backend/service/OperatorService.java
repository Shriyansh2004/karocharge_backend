package com.karocharge.backend.service;

import com.karocharge.backend.config.OperatorProperties;
import com.karocharge.backend.dto.operator.OperatorActionResultDTO;
import com.karocharge.backend.dto.operator.OperatorChargerDTO;
import com.karocharge.backend.dto.operator.OperatorConnectionStatusDTO;
import com.karocharge.backend.dto.operator.OperatorStationChargerDTO;
import com.karocharge.backend.dto.operator.OperatorStationDTO;
import com.karocharge.backend.dto.operator.OperatorStationDetailDTO;
import com.karocharge.backend.exception.CitrineIntegrationException;
import com.karocharge.backend.integration.csms.CsmsProviderSelector;
import com.karocharge.backend.integration.csms.ports.CsmsOperatorPort;
import com.karocharge.backend.integration.csms.ports.model.CsmsChargingStationView;
import com.karocharge.backend.integration.csms.ports.model.CsmsOperatorChargerView;
import com.karocharge.backend.integration.csms.ports.model.CsmsOperatorLocationView;
import com.karocharge.backend.model.Booking;
import com.karocharge.backend.model.Charger;
import com.karocharge.backend.repository.BookingRepository;
import com.karocharge.backend.repository.ChargerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final CsmsProviderSelector csmsProviderSelector;
    private final OperatorProperties operatorProperties;

    public OperatorConnectionStatusDTO getConnectionStatus() {
        CsmsOperatorPort csms = csmsProviderSelector.current().operator();
        boolean citrineHttpReachable = csms.isHttpReachable();
        List<CsmsChargingStationView> citrineStations = csms.fetchChargingStations();
        boolean hasuraReachable = !citrineStations.isEmpty();
        boolean connected = citrineHttpReachable || hasuraReachable;

        String citrineStatus = connected ? "CONNECTED" : "DISCONNECTED";
        String message = connected
                ? "KaroCharge backend is connected to Citrine OS"
                : "KaroCharge backend cannot reach Citrine OS (check HTTP :8080 and Hasura :8090)";

        return OperatorConnectionStatusDTO.builder()
                .backendStatus("UP")
                .citrineStatus(citrineStatus)
                .citrineBaseUrl(csms.baseUrl())
                .citrineWebsocketUrl(csms.websocketUrl())
                .message(message)
                .checkedAtEpochMs(System.currentTimeMillis())
                .build();
    }

    public List<OperatorChargerDTO> listChargers() {
        List<CsmsChargingStationView> stations = csmsProviderSelector.current().operator().fetchChargingStations();
        Map<String, CsmsChargingStationView> citrineById = stations.stream()
                .collect(Collectors.toMap(CsmsChargingStationView::getId, Function.identity(), (a, b) -> a));

        Set<String> matchedCitrineIds = new HashSet<>();
        List<OperatorChargerDTO> result = new ArrayList<>();

        for (Charger charger : chargerRepository.findAll()) {
            String citrineId = resolveCitrineId(charger);
            CsmsChargingStationView citrine = citrineById.get(citrineId);
            if (citrine != null) {
                matchedCitrineIds.add(citrineId);
                result.add(mergeDbAndCitrine(charger, citrine, citrineId));
            } else if (operatorProperties.isIncludeUnlinkedDbChargers()) {
                result.add(mergeDbAndCitrine(charger, null, citrineId));
            }
        }

        for (CsmsChargingStationView citrine : citrineById.values()) {
            if (!matchedCitrineIds.contains(citrine.getId())) {
                result.add(fromCitrineOnly(citrine));
            }
        }

        return result;
    }

    public List<OperatorStationDTO> listStations() {
        List<CsmsOperatorLocationView> locations = csmsProviderSelector.current().operator().fetchOperatorLocations();
        if (!locations.isEmpty()) {
            List<OperatorStationDTO> stations = locations.stream()
                    .map(this::toStationSummary)
                    .collect(Collectors.toCollection(ArrayList::new));

            Set<String> assignedChargerIds = locations.stream()
                    .flatMap(loc -> loc.getChargers().stream())
                    .map(CsmsOperatorChargerView::getId)
                    .collect(Collectors.toSet());

            List<CsmsOperatorChargerView> unassigned = csmsProviderSelector.current().operator().fetchChargingStations().stream()
                    .filter(s -> !assignedChargerIds.contains(s.getId()))
                    .map(this::toOperatorChargerFromCitrine)
                    .toList();

            if (!unassigned.isEmpty()) {
                stations.add(buildUnassignedStationSummary(unassigned));
            }
            return stations;
        }

        return fallbackStationsFromOperatorChargers();
    }

    public Optional<OperatorStationDetailDTO> getStation(Long stationId) {
        if (stationId == null) {
            return Optional.empty();
        }
        if (stationId == 0L) {
            return Optional.of(buildUnassignedStationDetail());
        }

        Optional<OperatorStationDetailDTO> fromHasura = csmsProviderSelector.current().operator().fetchOperatorLocations().stream()
                .filter(loc -> stationId.equals(loc.getId()))
                .findFirst()
                .map(this::toStationDetail);
        if (fromHasura.isPresent()) {
            return fromHasura;
        }

        return fallbackStationDetail(stationId);
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

        return csmsProviderSelector.current().operator().fetchChargingStations().stream()
                .filter(s -> citrineChargerId.equals(s.getId()))
                .map(CsmsChargingStationView::getActiveTransactionId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    private OperatorChargerDTO mergeDbAndCitrine(Charger charger, CsmsChargingStationView citrine, String citrineId) {
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

    private OperatorChargerDTO fromCitrineOnly(CsmsChargingStationView citrine) {
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

    private String deriveStatus(String dbStatus, CsmsChargingStationView citrine) {
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

    private List<OperatorStationDTO> fallbackStationsFromOperatorChargers() {
        Map<String, List<OperatorChargerDTO>> byLocation = listChargers().stream()
                .collect(Collectors.groupingBy(c -> {
                    String loc = c.getLocation();
                    return loc == null || loc.isBlank() ? "Unassigned" : loc;
                }));

        List<OperatorStationDTO> result = new ArrayList<>();
        long syntheticId = -1L;
        for (Map.Entry<String, List<OperatorChargerDTO>> entry : byLocation.entrySet()) {
            int chargerCount = entry.getValue().size();
            result.add(OperatorStationDTO.builder()
                    .id(syntheticId--)
                    .stationName(entry.getKey())
                    .chargers(chargerCount)
                    .connectors(chargerCount)
                    .connectorsSupported("—")
                    .cityDistrict("—")
                    .pincode("—")
                    .state("—")
                    .published("—")
                    .loadCapacity("—")
                    .accessibility("PUBLIC")
                    .build());
        }
        return result;
    }

    private Optional<OperatorStationDetailDTO> fallbackStationDetail(Long stationId) {
        List<Map.Entry<String, List<OperatorChargerDTO>>> groups = listChargers().stream()
                .collect(Collectors.groupingBy(c -> {
                    String loc = c.getLocation();
                    return loc == null || loc.isBlank() ? "Unassigned" : loc;
                }))
                .entrySet()
                .stream()
                .toList();

        long syntheticId = -1L;
        for (Map.Entry<String, List<OperatorChargerDTO>> entry : groups) {
            if (stationId.equals(syntheticId)) {
                return Optional.of(buildFallbackStationDetail(syntheticId, entry.getKey(), entry.getValue()));
            }
            syntheticId--;
        }

        // Legacy fallback rows used positive ids (1, 2, …) before Hasura locations loaded.
        if (stationId > 0) {
            int index = stationId.intValue() - 1;
            if (index >= 0 && index < groups.size()) {
                Map.Entry<String, List<OperatorChargerDTO>> entry = groups.get(index);
                return Optional.of(buildFallbackStationDetail(stationId, entry.getKey(), entry.getValue()));
            }
        }

        return Optional.empty();
    }

    private OperatorStationDetailDTO buildFallbackStationDetail(
            Long stationId,
            String stationName,
            List<OperatorChargerDTO> chargers
    ) {
        List<OperatorStationChargerDTO> chargerRows = chargers.stream()
                .map(this::toStationChargerFromOperator)
                .toList();

        return OperatorStationDetailDTO.builder()
                .id(stationId)
                .stationName(stationName)
                .address("—")
                .cityDistrict("—")
                .pincode("—")
                .state("—")
                .published("—")
                .loadCapacity("—")
                .accessibility("PUBLIC")
                .latitude("—")
                .longitude("—")
                .chargers(chargerRows)
                .build();
    }

    private OperatorStationChargerDTO toStationChargerFromOperator(OperatorChargerDTO charger) {
        String chargerId = charger.getCitrineChargerId();
        String status = charger.getCitrineOnline() == null
                ? charger.getStatus()
                : Boolean.TRUE.equals(charger.getCitrineOnline())
                        ? capitalize(charger.getConnectorStatus() != null ? charger.getConnectorStatus() : "Online")
                        : "Offline";

        String wsBase = csmsProviderSelector.current().operator().websocketUrl();
        String chargingUrl = wsBase != null && chargerId != null
                ? wsBase.replaceAll("/$", "") + "/" + chargerId
                : "—";

        return OperatorStationChargerDTO.builder()
                .chargerName(chargerId)
                .chargerId(chargerId)
                .physicalReference("—")
                .serialNumber("—")
                .smartCharger("—")
                .status(status)
                .connectors(charger.getConnectorStatus() != null ? 1 : 0)
                .power("—")
                .vendor(nullToDash(charger.getBrand()))
                .model(nullToDash(charger.getType()))
                .firmwareVersion("—")
                .ocpp(formatOcpp(charger.getProtocol()))
                .chargingUrl(chargingUrl)
                .coordinates("—")
                .createdOn("—")
                .statusLastUpdated("—")
                .build();
    }

    private OperatorStationDTO buildUnassignedStationSummary(List<CsmsOperatorChargerView> chargers) {
        return OperatorStationDTO.builder()
                .id(0L)
                .stationName("Unassigned chargers")
                .chargers(chargers.size())
                .connectors(chargers.stream().mapToInt(CsmsOperatorChargerView::getConnectorCount).sum())
                .connectorsSupported(collectConnectorTypes(chargers))
                .cityDistrict("—")
                .pincode("—")
                .state("—")
                .published("No")
                .loadCapacity(formatLoadCapacity(chargers))
                .accessibility("—")
                .build();
    }

    private OperatorStationDetailDTO buildUnassignedStationDetail() {
        Set<String> assignedChargerIds = csmsProviderSelector.current().operator().fetchOperatorLocations().stream()
                .flatMap(loc -> loc.getChargers().stream())
                .map(CsmsOperatorChargerView::getId)
                .collect(Collectors.toSet());

        List<CsmsOperatorChargerView> unassigned = csmsProviderSelector.current().operator().fetchChargingStations().stream()
                .filter(s -> !assignedChargerIds.contains(s.getId()))
                .map(this::toOperatorChargerFromCitrine)
                .toList();

        return OperatorStationDetailDTO.builder()
                .id(0L)
                .stationName("Unassigned chargers")
                .address("—")
                .cityDistrict("—")
                .pincode("—")
                .state("—")
                .published("No")
                .loadCapacity(formatLoadCapacity(unassigned))
                .accessibility("—")
                .latitude("—")
                .longitude("—")
                .chargers(unassigned.stream().map(this::toStationCharger).toList())
                .build();
    }

    private CsmsOperatorChargerView toOperatorChargerFromCitrine(CsmsChargingStationView citrine) {
        return CsmsOperatorChargerView.builder()
                .id(citrine.getId())
                .ocppConnectionName(citrine.getId())
                .isOnline(citrine.getIsOnline())
                .protocol(citrine.getProtocol())
                .chargePointVendor(citrine.getChargePointVendor())
                .chargePointModel(citrine.getChargePointModel())
                .firmwareVersion(null)
                .chargePointSerialNumber(null)
                .capabilities(List.of())
                .physicalReference(null)
                .connectorCount(citrine.getConnectorStatus() != null ? 1 : 0)
                .connectorsSupported(citrine.getConnectorStatus())
                .maxPowerWatts(null)
                .connectorStatus(citrine.getConnectorStatus())
                .smartCharger(false)
                .build();
    }

    private OperatorStationDTO toStationSummary(CsmsOperatorLocationView location) {
        List<CsmsOperatorChargerView> chargers = location.getChargers();
        return OperatorStationDTO.builder()
                .id(location.getId())
                .stationName(location.getName())
                .chargers(chargers.size())
                .connectors(chargers.stream().mapToInt(CsmsOperatorChargerView::getConnectorCount).sum())
                .connectorsSupported(collectConnectorTypes(chargers))
                .cityDistrict(nullToDash(location.getCity()))
                .pincode(nullToDash(location.getPostalCode()))
                .state(nullToDash(location.getState()))
                .published(Boolean.TRUE.equals(location.getPublishUpstream()) ? "Yes" : "No")
                .loadCapacity(formatLoadCapacity(chargers))
                .accessibility(formatAccessibility(location))
                .build();
    }

    private OperatorStationDetailDTO toStationDetail(CsmsOperatorLocationView location) {
        return OperatorStationDetailDTO.builder()
                .id(location.getId())
                .stationName(location.getName())
                .address(buildAddress(location))
                .cityDistrict(nullToDash(location.getCity()))
                .pincode(nullToDash(location.getPostalCode()))
                .state(nullToDash(location.getState()))
                .published(Boolean.TRUE.equals(location.getPublishUpstream()) ? "Yes" : "No")
                .loadCapacity(formatLoadCapacity(location.getChargers()))
                .accessibility(formatAccessibility(location))
                .latitude(location.getLatitude() != null ? String.format("%.5f", location.getLatitude()) : "—")
                .longitude(location.getLongitude() != null ? String.format("%.5f", location.getLongitude()) : "—")
                .chargers(location.getChargers().stream().map(this::toStationCharger).toList())
                .build();
    }

    private OperatorStationChargerDTO toStationCharger(CsmsOperatorChargerView charger) {
        String chargerId = charger.getId() != null ? charger.getId() : charger.getOcppConnectionName();
        String status = deriveChargerStatus(charger);
        String power = charger.getMaxPowerWatts() != null
                ? String.format("%.1f kW", charger.getMaxPowerWatts() / 1000.0)
                : "—";

        String wsBase = csmsProviderSelector.current().operator().websocketUrl();
        String chargingUrl = wsBase != null && chargerId != null
                ? wsBase.replaceAll("/$", "") + "/" + chargerId
                : "—";

        return OperatorStationChargerDTO.builder()
                .chargerName(charger.getOcppConnectionName() != null ? charger.getOcppConnectionName() : chargerId)
                .chargerId(chargerId)
                .physicalReference(nullToDash(charger.getPhysicalReference()))
                .serialNumber(nullToDash(charger.getChargePointSerialNumber()))
                .smartCharger(charger.isSmartCharger() ? "Yes" : "No")
                .status(status)
                .connectors(charger.getConnectorCount())
                .power(power)
                .vendor(nullToDash(charger.getChargePointVendor()))
                .model(nullToDash(charger.getChargePointModel()))
                .firmwareVersion(nullToDash(charger.getFirmwareVersion()))
                .ocpp(formatOcpp(charger.getProtocol()))
                .chargingUrl(chargingUrl)
                .coordinates("—")
                .createdOn("—")
                .statusLastUpdated("—")
                .build();
    }

    private String deriveChargerStatus(CsmsOperatorChargerView charger) {
        if (Boolean.FALSE.equals(charger.getIsOnline())) {
            return "Offline";
        }
        if (Boolean.TRUE.equals(charger.getIsOnline())) {
            if (charger.getConnectorStatus() != null) {
                return capitalize(charger.getConnectorStatus());
            }
            return "Online";
        }
        return "Unknown";
    }

    private String formatOcpp(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "—";
        }
        return switch (protocol.toLowerCase()) {
            case "ocpp1.6", "ocpp1.6json" -> "OCPP 1.6";
            case "ocpp2.0.1" -> "OCPP 2.0.1";
            case "ocpp2.1" -> "OCPP 2.1";
            default -> protocol;
        };
    }

    private String formatLoadCapacity(List<CsmsOperatorChargerView> chargers) {
        int totalWatts = chargers.stream()
                .map(CsmsOperatorChargerView::getMaxPowerWatts)
                .filter(w -> w != null && w > 0)
                .mapToInt(Integer::intValue)
                .sum();
        if (totalWatts <= 0) {
            return "—";
        }
        return String.format("%.1f kW", totalWatts / 1000.0);
    }

    private String collectConnectorTypes(List<CsmsOperatorChargerView> chargers) {
        Set<String> types = new LinkedHashSet<>();
        for (CsmsOperatorChargerView charger : chargers) {
            if (charger.getConnectorsSupported() != null && !charger.getConnectorsSupported().isBlank()) {
                for (String part : charger.getConnectorsSupported().split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        types.add(trimmed);
                    }
                }
            }
        }
        return types.isEmpty() ? "—" : String.join(", ", types);
    }

    private String formatAccessibility(CsmsOperatorLocationView location) {
        if (location.getParkingType() != null && !location.getParkingType().isBlank()) {
            return location.getParkingType();
        }
        if (location.getFacilities() != null && !location.getFacilities().isEmpty()) {
            return String.join(", ", location.getFacilities());
        }
        return "PUBLIC";
    }

    private String buildAddress(CsmsOperatorLocationView location) {
        List<String> parts = new ArrayList<>();
        if (location.getAddress() != null && !location.getAddress().isBlank()) {
            parts.add(location.getAddress());
        }
        if (location.getCity() != null && !location.getCity().isBlank()) {
            parts.add(location.getCity());
        }
        if (location.getState() != null && !location.getState().isBlank()) {
            parts.add(location.getState());
        }
        if (location.getPostalCode() != null && !location.getPostalCode().isBlank()) {
            parts.add(location.getPostalCode());
        }
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
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
