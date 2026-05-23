package com.karocharge.backend.dto.operator;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatorConnectionStatusDTO {
    String backendStatus;
    String citrineStatus;
    String citrineBaseUrl;
    String citrineWebsocketUrl;
    String message;
    long checkedAtEpochMs;
}
