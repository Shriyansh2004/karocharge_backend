package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatorActionResultDTO {
    String action;
    String chargerId;
    boolean success;
    String message;
    String transactionId;
}
