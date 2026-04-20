package com.karocharge.backend.dto;

import lombok.Data;

@Data
public class BookingRequest {

    // The Unique ID of the user booking the charger (e.g., "KC-USER-123")
    // This is what the Controller calls via .getUserId()
    private String userId;

    // The name of the guest booking the charger (optional snapshot)
    private String userName;

    // The duration selected in React (e.g., 15, 30, 60 minutes)
    private Integer duration;

    // Crucial for linking: the ID of the charger being booked
    private Long chargerId;
}