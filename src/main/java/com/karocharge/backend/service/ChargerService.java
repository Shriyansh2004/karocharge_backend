package com.karocharge.backend.service;

import com.karocharge.backend.model.Booking;
import com.karocharge.backend.model.Charger;
import com.karocharge.backend.model.User;
import com.karocharge.backend.repository.BookingRepository;
import com.karocharge.backend.repository.ChargerRepository;
import com.karocharge.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChargerService {

    private final ChargerRepository chargerRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ChargingControlService chargingControlService;

    public ChargerService(ChargerRepository chargerRepository,
                          BookingRepository bookingRepository,
                          UserRepository userRepository,
                          ChargingControlService chargingControlService) {
        this.chargerRepository = chargerRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.chargingControlService = chargingControlService;
    }

    /**
     * BEHAVIOR: HOST MODE
     * Links the User (Host) to the Charger entity.
     */
    @Transactional
    public Charger createCharger(Charger charger, String userId) {
        User hostUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

        charger.setHost(hostUser);
        charger.setHostName(hostUser.getUserName());
        charger.setStatus("AVAILABLE");

        return chargerRepository.save(charger);
    }

    public List<Charger> getAllChargers() {
        return chargerRepository.findAll();
    }

    public Charger getChargerById(Long id) {
        return chargerRepository.findById(id).orElse(null);
    }

    // --- CORE LOGIC METHODS ---

    /**
     * BEHAVIOR: CHARGE MODE (BOOKING)
     * Matches the constructor: Booking(Charger charger, User driver, Integer duration, String status)
     */
    @Transactional
    public Booking bookCharger(Long id, String userId, Integer duration) {
        // 1. Find the charger and its host
        Charger charger = chargerRepository.findById(id).orElse(null);
        if (charger == null || !"AVAILABLE".equalsIgnoreCase(charger.getStatus())) {
            return null;
        }

        // 2. Find the user booking the charger (The Driver)
        User driverUser = userRepository.findById(userId).orElse(null);
        if (driverUser == null) {
            return null;
        }

        // 3. Citrine Handshake
        String sessionId = "booking-" + id + "-" + userId + "-" + System.currentTimeMillis();
        if (!blockInCitrine(id, userId, sessionId, duration)) {
            return null;
        }

        // 4. Update Charger status
        charger.setStatus("BOOKED");
        chargerRepository.save(charger);

        // 5. Create Booking (Session)
        // FIXED: Passing the driverUser (User object) to match the model's constructor
        // Current Constructor Order: (Charger, User, Integer, String)
        Booking booking = new Booking(charger, driverUser, duration, "BOOKED");

        // The behavioral IDs (host_id and driver_id) are now set via the constructor logic
        // in Booking.java, so we just save and return.
        return bookingRepository.save(booking);
    }

    public Charger blockCharger(Long id) {
        Charger charger = chargerRepository.findById(id).orElse(null);
        if (charger == null || !"AVAILABLE".equalsIgnoreCase(charger.getStatus())) {
            return null;
        }

        String fallbackUserId = "manual-block-user";
        String fallbackSessionId = "manual-block-" + id + "-" + System.currentTimeMillis();
        if (blockInCitrine(id, fallbackUserId, fallbackSessionId, 30)) {
            charger.setStatus("BLOCKED");
            return chargerRepository.save(charger);
        }
        return null;
    }

    public Charger unblockChargerLocally(Long id) {
        Charger charger = chargerRepository.findById(id).orElse(null);
        if (charger == null) return null;

        charger.setStatus("AVAILABLE");
        return chargerRepository.save(charger);
    }

    public Charger setChargerToCharging(Long id) {
        Charger charger = chargerRepository.findById(id).orElse(null);
        if (charger == null) return null;

        charger.setStatus("CHARGING");
        return chargerRepository.save(charger);
    }

    // --- Citrine API CALLS ---

    private boolean blockInCitrine(Long id, String userId, String sessionId, Integer durationMinutes) {
        try {
            chargingControlService.blockCharger(String.valueOf(id), userId, sessionId, durationMinutes);
            return true;
        } catch (Exception e) {
            System.err.println("Citrine block failed for charger " + id + ": " + e.getMessage());
            return false;
        }
    }

    public boolean triggerCitrineUnblock(Long id) {
        try {
            chargingControlService.unblockCharger(String.valueOf(id));
            return true;
        } catch (Exception e) {
            System.err.println("Citrine unblock failed for charger " + id + ": " + e.getMessage());
            return false;
        }
    }
}