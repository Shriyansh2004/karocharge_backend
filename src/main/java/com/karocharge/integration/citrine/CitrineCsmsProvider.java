package com.karocharge.integration.citrine;

import com.karocharge.backend.integration.csms.CsmsProvider;
import com.karocharge.backend.integration.csms.ports.CsmsChargingPort;
import com.karocharge.backend.integration.csms.ports.CsmsOperatorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Citrine adapter exposed behind the provider-agnostic {@link CsmsProvider} contract.
 * All Citrine-specific behavior stays inside the integration package.
 */
@Component
@RequiredArgsConstructor
public class CitrineCsmsProvider implements CsmsProvider {

    private final CitrineChargingPortAdapter chargingPort;
    private final CitrineOperatorPortAdapter operatorPort;

    @Override
    public String providerKey() {
        // Preserve current behavior: "default" provider is the existing Citrine integration.
        return "default";
    }

    @Override
    public CsmsChargingPort charging() {
        return chargingPort;
    }

    @Override
    public CsmsOperatorPort operator() {
        return operatorPort;
    }
}

