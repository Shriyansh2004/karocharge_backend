package com.karocharge.backend.integration.csms.ports.model;

public record CsmsStartChargingResult(
        String transactionId,
        String rawResponse
) {
}

