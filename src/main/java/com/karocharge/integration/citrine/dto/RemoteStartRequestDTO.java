package com.karocharge.integration.citrine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteStartRequestDTO {
    private Integer evseId;
    private Integer remoteStartId;
    private IdToken idToken;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdToken {
        private String idToken;
        private String type;
    }
}
