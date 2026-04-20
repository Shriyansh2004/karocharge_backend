package com.karocharge.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sessions")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "charger_id", nullable = false)
    private Charger charger;

    // Behavioral Relationships
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "driver_id")
    private User driver;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "host_id")
    private User host;

    private LocalDateTime chargingStartedAt;

    // Snapshot Fields (To keep record even if Charger/User is deleted)
    @Column(nullable = false)
    private String brand;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String hostName;
    @Column(nullable = false)
    private String location;
    @Column(name = "driverName", nullable = false)
    private String userName; // Stores Driver's name snapshot

    @Column(nullable = false)
    private Integer duration;
    @Column(nullable = false)
    private String status;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Double bookedDuration;
    private Double totalEnergy;
    private Integer actualDuration;
    private Integer lateMinutes;
    private Integer idleMinutes;
    private String ocppTransactionId;
    private String chargerStatus;
    private LocalDateTime lastHeartbeat;
    private Double energyConsumed;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    // 1. Default Constructor for JPA/Jackson
    public Booking() {
        this.status = "PENDING";
        this.startTime = LocalDateTime.now();
        this.lateMinutes = 0;
        this.idleMinutes = 0;
        this.totalEnergy = 0.0;
        this.actualDuration = 0;
        this.chargerStatus = "AVAILABLE";
        this.energyConsumed = 0.0;
    }

    // 2. Main Constructor for Service Layer
    public Booking(Charger charger, User driver, Integer duration, String status) {
        if (charger == null) throw new IllegalArgumentException("Charger cannot be null");

        this.charger = charger;
        this.driver = driver;
        this.host = charger.getHost(); // Automatically pull host from charger
        this.duration = duration;
        this.status = (status != null) ? status : "BOOKED";
        this.startTime = LocalDateTime.now();

        // Safety check for Driver
        if (driver != null) {
            this.userName = driver.getUserName();
        } else {
            this.userName = "Unknown Driver";
        }

        // Snapshots from Charger
        this.brand = charger.getBrand();
        this.type = charger.getType();
        this.hostName = charger.getHostName() != null ? charger.getHostName() : "Unknown Host";
        this.location = charger.getLocation();

        // Calculations
        this.bookedDuration = (duration != null) ? duration / 60.0 : 0.0;
        this.lateMinutes = 0;
        this.idleMinutes = 0;
        this.totalEnergy = 0.0;
        this.actualDuration = 0;
        this.chargerStatus = "BOOKED";
        this.energyConsumed = 0.0;
    }
}