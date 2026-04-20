package com.karocharge.backend.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "charger_model")
@Data
public class ChargerModel {
    @Id
    private Long id;

    @Column
    private String name;

    @ManyToOne
    @JoinColumn(name = "chargerBrandId")
    private ChargerBrand chargerBrand;

}
