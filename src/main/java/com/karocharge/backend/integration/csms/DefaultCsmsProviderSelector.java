package com.karocharge.backend.integration.csms;

import com.karocharge.backend.integration.csms.config.CsmsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultCsmsProviderSelector implements CsmsProviderSelector {

    private final CsmsProperties csmsProperties;
    private final List<CsmsProvider> providers;

    @Override
    public CsmsProvider current() {
        String key = csmsProperties.getProvider();
        if (key == null || key.isBlank()) {
            key = "default";
        }
        String normalized = key.trim();
        return providers.stream()
                .filter(p -> normalized.equalsIgnoreCase(p.providerKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No CsmsProvider registered for key '" + normalized + "'"));
    }
}

