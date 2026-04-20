package com.karocharge.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "charger_brand")
public class ChargerBrand {
    @Id
    private Long id;

    @Column
    private String name;
}
