package com.karocharge.backend.controller;

import com.karocharge.backend.dto.BookingRequest;
import com.karocharge.backend.model.Booking;
import com.karocharge.backend.model.Charger;
import com.karocharge.backend.repository.ChargerRepository;
import com.karocharge.backend.service.ChargerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/chargers")
public class ChargerController {

    private final ChargerService chargerService;
    private final ChargerRepository chargerRepository;

    public ChargerController(ChargerService chargerService, ChargerRepository chargerRepository) {
        this.chargerService = chargerService;
        this.chargerRepository = chargerRepository;
    }

    /**
     * 1. HOST MODE: Host a charger
     * Links the charger to the user (e.g., Anubhab) based on the userId provided.
     */
    @PostMapping
    public ResponseEntity<?> createCharger(
            @RequestBody Charger charger,
            @RequestParam String userId) {

        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.badRequest().body("userId is required to host a charger");
        }

        return ResponseEntity.ok(chargerService.createCharger(charger, userId));
    }

    // 2. Get all chargers
    @GetMapping
    public ResponseEntity<List<Charger>> getAllChargers() {
        return ResponseEntity.ok(chargerService.getAllChargers());
    }

    // 3. Get charger by ID
    @GetMapping("/{id}")
    public ResponseEntity<Charger> getChargerById(@PathVariable Long id) {
        Charger charger = chargerService.getChargerById(id);
        if (charger == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(charger);
    }

    // 4. Get chargers by Host Name
    @GetMapping("/host/{hostName}")
    public List<Charger> getByHost(@PathVariable String hostName) {
        return chargerRepository.findByHostName(hostName);
    }

    /**
     * 5. CHARGE MODE: Book a charger
     * Behaves as: A user (e.g., Anshu) books a charger using their userId.
     */
    @PostMapping("/{id}/book")
    public ResponseEntity<?> bookCharger(
            @PathVariable Long id, // Fixed: This id is now passed to the service
            @RequestBody BookingRequest request) {

        // Validating the request body
        if (request == null || request.getUserId() == null || request.getDuration() == null) {
            return ResponseEntity.badRequest().body("userId and duration are required");
        }

        // Behavior: Pass the charger 'id' from the URL and 'userId' from the body
        Booking booking = chargerService.bookCharger(
                id,
                request.getUserId(),
                request.getDuration()
        );

        if (booking == null) {
            return ResponseEntity.badRequest()
                    .body("Booking failed: Charger unavailable, User not found, or CMS unreachable");
        }

        return ResponseEntity.ok(booking);
    }

    // --- CMS HANDSHAKE ENDPOINTS ---

    @PutMapping("/{id}/unblock")
    public ResponseEntity<?> unblockCharger(@PathVariable Long id) {
        Charger charger = chargerService.unblockChargerLocally(id);
        if (charger == null) {
            return ResponseEntity.status(404).body("Charger not found for unblocking");
        }
        return ResponseEntity.ok("Charger " + id + " is now AVAILABLE");
    }

    @PutMapping("/{id}/block")
    public ResponseEntity<?> confirmBlock(@PathVariable Long id) {
        // Log that the hardware confirmed the block
        System.out.println("Hardware confirmation received for Charger ID: " + id);
        return ResponseEntity.ok("Block acknowledged by Backend");
    }

    // 6. Manual block
    @PostMapping("/{id}/block")
    public ResponseEntity<?> manualBlock(@PathVariable Long id) {
        Charger charger = chargerService.blockCharger(id);
        if (charger == null) {
            return ResponseEntity.badRequest()
                    .body("Charger block failed or already booked / CMS unreachable");
        }
        return ResponseEntity.ok(charger);
    }

    // 7. Ping endpoint
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}