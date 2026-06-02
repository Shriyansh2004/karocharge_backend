package com.karocharge.backend.integration.csms;

/**
 * Configuration-driven provider selection.
 * Today this resolves a single "active" provider (preserving existing behavior),
 * but can be extended later to resolve per-charger providers using DB mappings.
 */
public interface CsmsProviderSelector {
    CsmsProvider current();
}

