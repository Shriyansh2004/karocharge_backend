package com.karocharge.backend.controller;

import com.karocharge.backend.dto.operator.OperatorActionResultDTO;
import com.karocharge.backend.dto.operator.OperatorChargerDTO;
import com.karocharge.backend.dto.operator.OperatorConnectionStatusDTO;
import com.karocharge.backend.dto.operator.OperatorStationDTO;
import com.karocharge.backend.dto.operator.OperatorStationDetailDTO;
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

    @GetMapping("/stations")
    public ResponseEntity<List<OperatorStationDTO>> listStations() {
        return ResponseEntity.ok(operatorService.listStations());
    }

    @GetMapping("/stations/{stationId}")
    public ResponseEntity<OperatorStationDetailDTO> getStation(@PathVariable Long stationId) {
        return operatorService.getStation(stationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/chargers/{citrineChargerId}/block")
    public ResponseEntity<OperatorActionResultDTO> block(@PathVariable String citrineChargerId) {
        OperatorActionResultDTO result = operatorService.blockCharger(citrineChargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{citrineChargerId}/unblock")
    public ResponseEntity<OperatorActionResultDTO> unblock(@PathVariable String citrineChargerId) {
        OperatorActionResultDTO result = operatorService.unblockCharger(citrineChargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{citrineChargerId}/start")
    public ResponseEntity<OperatorActionResultDTO> start(@PathVariable String citrineChargerId) {
        OperatorActionResultDTO result = operatorService.startCharging(citrineChargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/chargers/{citrineChargerId}/stop")
    public ResponseEntity<OperatorActionResultDTO> stop(@PathVariable String citrineChargerId) {
        OperatorActionResultDTO result = operatorService.stopCharging(citrineChargerId);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
