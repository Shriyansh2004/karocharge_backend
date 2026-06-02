package com.karocharge.backend.integration.csms;

import com.karocharge.backend.integration.csms.ports.CsmsChargingPort;
import com.karocharge.backend.integration.csms.ports.CsmsOperatorPort;

/**
 * Provider-agnostic CSMS facade. Business/application services depend on this interface only.
 * Concrete implementations live in infrastructure adapters (e.g. provider-specific modules).
 */
public interface CsmsProvider {
    /**
     * A stable provider key used for configuration-driven selection (e.g. "default", "citrine", "vendor-x").
     */
    String providerKey();

    CsmsChargingPort charging();

    CsmsOperatorPort operator();
}

