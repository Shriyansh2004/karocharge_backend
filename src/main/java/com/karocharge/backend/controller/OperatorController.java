package com.karocharge.backend.controller;

import com.karocharge.backend.dto.operator.OperatorActionResultDTO;
import com.karocharge.backend.dto.operator.OperatorChargerDTO;
import com.karocharge.backend.dto.operator.OperatorConnectionStatusDTO;
import com.karocharge.backend.service.OperatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/operator")
@RequiredArgsConstructor
@CrossOrigin
public class OperatorController {

    private final OperatorService operatorService;

    @GetMapping("/status")
    public ResponseEntity<OperatorConnectionStatusDTO> getStatus() {
        return ResponseEntity.ok(operatorService.getConnectionStatus());
    }

    @GetMapping("/chargers")
    public ResponseEntity<List<OperatorChargerDTO>> listChargers() {
        return ResponseEntity.ok(operatorService.listChargers());
    }

    @PostMapping("/chargers/{chargerId}/block")
    public ResponseEntity<OperatorActionResultDTO> block(@PathVariable Long chargerId) {
        OperatorActionResultDTO result = operatorService.blockCharger(chargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{chargerId}/unblock")
    public ResponseEntity<OperatorActionResultDTO> unblock(@PathVariable Long chargerId) {
        OperatorActionResultDTO result = operatorService.unblockCharger(chargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{chargerId}/start")
    public ResponseEntity<OperatorActionResultDTO> start(@PathVariable Long chargerId) {
        OperatorActionResultDTO result = operatorService.startCharging(chargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{chargerId}/stop")
    public ResponseEntity<OperatorActionResultDTO> stop(@PathVariable Long chargerId) {
        OperatorActionResultDTO result = operatorService.stopCharging(chargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
