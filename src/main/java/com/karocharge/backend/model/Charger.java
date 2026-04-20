package com.karocharge.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "chargers")
@Data
public class Charger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The User acting as the HOST
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "host_id")
    private User host;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "location")
    private String location;

    @Column(name = "brand")
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charger_model_id")
    private ChargerModel chargerModel;

    @Column(name = "type")
    private String type;

    @Column(name = "available_date")
    private LocalDate availableDate;

    @Column(name = "available_duration")
    private Integer duration;

    @Column(name = "status")
    private String status = "AVAILABLE";
}