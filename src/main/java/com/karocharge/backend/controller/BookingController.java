package com.karocharge.backend.controller;

import com.karocharge.backend.dto.BookingRequest;
import com.karocharge.backend.exception.ResourceNotFoundException;
import com.karocharge.backend.model.Booking;
import com.karocharge.backend.repository.BookingRepository;
import com.karocharge.backend.service.ChargerService;
import com.karocharge.backend.service.ChargingControlService;
import com.karocharge.backend.service.OtpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@CrossOrigin(origins = "http://localhost:5173")
public class BookingController {

    private final ChargerService chargerService;
    private final ChargingControlService chargingControlService;
    private final OtpService otpService;
    private final BookingRepository bookingRepository;

    public BookingController(ChargerService chargerService,
                             ChargingControlService chargingControlService,
                             OtpService otpService,
                             BookingRepository bookingRepository) {
        this.chargerService = chargerService;
        this.chargingControlService = chargingControlService;
        this.otpService = otpService;
        this.bookingRepository = bookingRepository;
    }

    /**
     * 1. Create Booking (Using DTO)
     * This receives the simplified JSON from React and triggers the CMS block logic.
     */
    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingRequest request) {
        // Validation
        if (request.getChargerId() == null || request.getUserId() == null) {
            return ResponseEntity.badRequest().body("Charger ID and User ID are required");
        }

        // Call Service: This method handles User/Charger lookups and CMS blocking
        Booking savedBooking = chargerService.bookCharger(
                request.getChargerId(),
                request.getUserId(),
                request.getDuration()
        );

        if (savedBooking != null) {
            // Calculate booked duration in hours for billing
            double durationMins = (request.getDuration() != null) ? request.getDuration() : 30;
            savedBooking.setBookedDuration(durationMins / 60.0);

            // Save final updates
            Booking finalSaved = bookingRepository.save(savedBooking);
            return ResponseEntity.ok(finalSaved);
        }

        return ResponseEntity.status(400).body("Booking failed: Charger unavailable or Citrine error");
    }

    /**
     * 4. Start charging
     * Triggered by Host when OTP is verified.
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<?> startCharging(@PathVariable Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));

        LocalDateTime now = LocalDateTime.now();
        Duration durationPassed = Duration.between(booking.getStartTime(), now);
        long totalMinutesPassed = durationPassed.toMinutes();

        // Calculate late fees if more than 1 minute past booking start
        if (totalMinutesPassed > 1) {
            booking.setLateMinutes((int) (totalMinutesPassed - 1));
        } else {
            booking.setLateMinutes(0);
        }

        ChargingControlService.StartChargingResult result = chargingControlService.startCharging(
                String.valueOf(booking.getCharger().getId()),
                booking.getDriver() != null ? booking.getDriver().getId() : booking.getUserName()
        );

        booking.setStatus("CHARGING");
        booking.setChargingStartedAt(LocalDateTime.now());
        booking.setOcppTransactionId(result.transactionId());
        booking.setChargerStatus("CHARGING");
        booking.setLastHeartbeat(result.heartbeatAt());
        booking.setEnergyConsumed(0.0);
        bookingRepository.save(booking);
        chargerService.setChargerToCharging(booking.getCharger().getId());

        return ResponseEntity.ok(Map.of(
                "message", "Charging started",
                "status", "CHARGING",
                "lateMinutes", booking.getLateMinutes(),
                "ocppTransactionId", booking.getOcppTransactionId()
        ));
    }

    /**
     * 7. Get Booking (Live Timer Sync)
     * Synchronizes the frontend timer with the server's charging start time.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getBookingById(@PathVariable Long id) {
        return bookingRepository.findById(id)
                .map(booking -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("id", booking.getId());
                    response.put("status", booking.getStatus());
                    response.put("userName", booking.getUserName());
                    response.put("hostName", booking.getHostName());
                    response.put("totalEnergy", booking.getTotalEnergy());
                    response.put("bookedDuration", booking.getBookedDuration());
                    response.put("lateMinutes", booking.getLateMinutes());
                    response.put("idleMinutes", booking.getIdleMinutes());
                    response.put("cancelledBy", booking.getCancelledBy());
                    response.put("charger", booking.getCharger());

                    // Behavioral IDs for frontend visibility
                    response.put("driverId", booking.getDriver() != null ? booking.getDriver().getId() : null);
                    response.put("hostId", booking.getHost() != null ? booking.getHost().getId() : null);

                    // Timer Sync logic
                    if ("CHARGING".equals(booking.getStatus()) && booking.getChargingStartedAt() != null) {
                        long liveSeconds = Duration.between(booking.getChargingStartedAt(), LocalDateTime.now()).getSeconds();
                        response.put("actualDuration", liveSeconds);
                    } else {
                        response.put("actualDuration", booking.getActualDuration());
                    }
                    response.put("ocppTransactionId", booking.getOcppTransactionId());
                    response.put("chargerStatus", booking.getChargerStatus());
                    response.put("lastHeartbeat", booking.getLastHeartbeat());
                    response.put("energyConsumed", booking.getEnergyConsumed());

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 8. Extend Booking
     */
    @PostMapping("/{id}/extend")
    public ResponseEntity<?> extendBooking(@PathVariable Long id, @RequestParam Integer extraMinutes) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) return ResponseEntity.badRequest().body("Booking not found");

        int newDurationMins = booking.getDuration() + extraMinutes;
        booking.setDuration(newDurationMins);
        booking.setBookedDuration(newDurationMins / 60.0);

        booking.setStatus("CHARGING");
        chargingControlService.unblockCharger(String.valueOf(booking.getCharger().getId()));

        bookingRepository.save(booking);

        return ResponseEntity.ok(Map.of(
                "message", "Session extended",
                "newDuration", newDurationMins,
                "status", "CHARGING"
        ));
    }

    @PostMapping("/{id}/generate-otp")
    public ResponseEntity<?> generateOtp(@PathVariable Long id) {
        return ResponseEntity.ok(otpService.generateOtp(id));
    }

    @PostMapping("/{id}/verify-otp")
    public ResponseEntity<?> verifyOtp(@PathVariable Long id, @RequestParam String otp) {
        boolean isValid = otpService.verifyOtp(id, otp);
        if (!isValid) return ResponseEntity.badRequest().body(Map.of("message", "Invalid OTP"));
        return ResponseEntity.ok(Map.of("message", "OTP Verified Successfully"));
    }

    /**
     * Stop Charging
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stopCharging(@PathVariable Long id, @RequestParam(required = false) String cancelledBy) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));

        if (cancelledBy != null && !cancelledBy.isEmpty()) {
            booking.setCancelledBy(cancelledBy);
            booking.setStatus("CANCELLED");
        }

        if (booking.getOcppTransactionId() == null || booking.getOcppTransactionId().isBlank()) {
            throw new IllegalArgumentException("Cannot stop charging without an OCPP transaction id");
        }

        chargingControlService.stopCharging(String.valueOf(booking.getCharger().getId()), booking.getOcppTransactionId());
        booking.setStatus("STOP_REQUESTED");
        booking.setChargerStatus("STOP_REQUESTED");
        booking.setEndTime(LocalDateTime.now());
        booking.setLastHeartbeat(LocalDateTime.now());
        bookingRepository.save(booking);

        return ResponseEntity.ok(Map.of(
                "message", "Stop signal sent",
                "cancelledBy", cancelledBy,
                "ocppTransactionId", booking.getOcppTransactionId()
        ));
    }

    /**
     * Receive Session Data from CMS
     */
    @PostMapping("/complete")
    public ResponseEntity<?> receiveSessionData(@RequestBody Map<String, Object> data) {
        Long chargerId = Long.valueOf(data.get("chargerId").toString());
        Booking booking = bookingRepository.findTopByChargerIdAndStatusOrderByStartTimeDesc(chargerId, "CHARGING");

        if (booking == null) {
            booking = bookingRepository.findTopByChargerIdAndStatusOrderByStartTimeDesc(chargerId, "CANCELLED");
        }

        if (booking != null) {
            if (!"CANCELLED".equals(booking.getStatus())) booking.setStatus("COMPLETED");
            booking.setTotalEnergy(Double.valueOf(data.get("totalEnergy").toString()));
            booking.setEnergyConsumed(Double.valueOf(data.get("totalEnergy").toString()));
            booking.setActualDuration(Integer.valueOf(data.get("durationSeconds").toString()));
            booking.setChargerStatus("AVAILABLE");
            booking.setLastHeartbeat(LocalDateTime.now());
            bookingRepository.save(booking);
            chargerService.unblockChargerLocally(chargerId);
            return ResponseEntity.ok("Sync successful");
        }
        return ResponseEntity.status(404).body("No active session");
    }
}