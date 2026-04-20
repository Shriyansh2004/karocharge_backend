package com.karocharge.backend.controller;

import com.karocharge.backend.service.ChargingControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
@CrossOrigin(origins = "http://localhost:5173")
public class ChargingTestController {

    private final ChargingControlService chargingControlService;

    @PostMapping("/block/{chargerId}")
    public ResponseEntity<?> block(@PathVariable String chargerId,
                                   @RequestParam(defaultValue = "test-user") String userId,
                                   @RequestParam(required = false) String sessionId,
                                   @RequestParam(defaultValue = "30") Integer durationMinutes) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? "test-session-" + chargerId + "-" + System.currentTimeMillis()
                : sessionId;
        chargingControlService.blockCharger(chargerId, userId, effectiveSessionId, durationMinutes);
        return ResponseEntity.ok(Map.of(
                "message", "Block request sent to Citrine",
                "chargerId", chargerId,
                "userId", userId,
                "sessionId", effectiveSessionId
        ));
    }

    @PostMapping("/unblock/{chargerId}")
    public ResponseEntity<?> unblock(@PathVariable String chargerId) {
        chargingControlService.unblockCharger(chargerId);
        return ResponseEntity.ok(Map.of(
                "message", "Unblock request sent to Citrine",
                "chargerId", chargerId
        ));
    }

    @PostMapping("/start/{chargerId}")
    public ResponseEntity<?> start(@PathVariable String chargerId) {
        ChargingControlService.StartChargingResult result = chargingControlService.startCharging(chargerId, "test-user");
        return ResponseEntity.ok(Map.of(
                "message", "Start request sent to Citrine",
                "chargerId", chargerId,
                "transactionId", result.transactionId()
        ));
    }

    @PostMapping("/stop/{chargerId}/{transactionId}")
    public ResponseEntity<?> stop(@PathVariable String chargerId, @PathVariable String transactionId) {
        chargingControlService.stopCharging(chargerId, transactionId);
        return ResponseEntity.ok(Map.of(
                "message", "Stop request sent to Citrine",
                "chargerId", chargerId,
                "transactionId", transactionId
        ));
    }
}
