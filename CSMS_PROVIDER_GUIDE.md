# CSMS Replace / Add Guide (KaroCharge Backend)

This backend is **CSMS-provider agnostic**. Business services and controllers never call a vendor SDK/client directly. All CSMS behavior is isolated behind **ports** and a **provider adapter** selected by configuration.

This document tells you exactly **where to add/replace a CSMS**, how to implement **block / unblock / start / stop**, and how **live view / monitoring** works in the current codebase.

---

## Key concepts (where CSMS code is allowed)

- **Allowed to be CSMS-specific (Infrastructure / Adapter layer)**
  - HTTP request/response formats, headers, auth, retries
  - WebSocket connection behavior
  - Provider DTOs and GraphQL queries (if any)
  - Mapping provider responses into provider-neutral models

- **Must be CSMS-neutral (Business layer)**
  - `ChargingControlService`, `OperatorService`, `ChargerService`
  - Controllers and API DTOs
  - Booking/session state handling in DB

The CSMS-neutral entrypoint is:
- `com.karocharge.backend.integration.csms.CsmsProvider`

Provider-neutral feature ports used today:
- `com.karocharge.backend.integration.csms.ports.CsmsChargingPort` (block/unblock/start/stop)
- `com.karocharge.backend.integration.csms.ports.CsmsOperatorPort` (status/stations/locations for operator UI)

Provider selection happens via:
- `com.karocharge.backend.integration.csms.CsmsProviderSelector`
- `com.karocharge.backend.integration.csms.DefaultCsmsProviderSelector`

---

## Current behavior map (what calls what)

### Block / Unblock / Start / Stop
- API endpoints (unchanged):
  - Operator UI routes: `com.karocharge.backend.controller.OperatorController`
  - Test routes (if you use them): see `karocharge_API/README.md`

- Business orchestration (CSMS-neutral):
  - `com.karocharge.backend.service.ChargingControlService`
    - uses: `csmsProviderSelector.current().charging()`
  - `com.karocharge.backend.service.ChargerService`
    - books and blocks by calling `ChargingControlService`

- Provider adapter (CSMS-specific):
  - `com.karocharge.integration.citrine.CitrineChargingPortAdapter`
    - implements: `CsmsChargingPort`

### Live view / monitoring of charger status (current implementation)
The operator dashboard’s “live” charger list is currently driven by **CSMS reads** (polling via CSMS/Hasura), and also constructs a **chargingUrl** based on the CSMS WebSocket URL.

- Business logic (CSMS-neutral):
  - `com.karocharge.backend.service.OperatorService`
    - uses: `csmsProviderSelector.current().operator().fetchChargingStations()`
    - uses: `csmsProviderSelector.current().operator().fetchOperatorLocations()`
    - uses: `csmsProviderSelector.current().operator().websocketUrl()` to build `chargingUrl`

- Provider adapter (CSMS-specific):
  - `com.karocharge.integration.citrine.CitrineOperatorPortAdapter`
    - implements: `CsmsOperatorPort`
    - delegates to:
      - `CitrineHasuraClient` (GraphQL queries)
      - `CitrineClient.isReachable()` (HTTP health check)

Important: **the backend does not currently consume OCPP WebSocket events into the DB**. The WebSocket URL is mainly surfaced to the operator UI as a “charging URL” and the station status is fetched via the CSMS read API.

---

## Configuration (how provider switching works)

### Provider selection
In `src/main/resources/application.yml`:

- `csms.provider`: chooses the active provider key.
- Each adapter implements `CsmsProvider#providerKey()` and must match the configured key.

Example:

```yaml
csms:
  provider: ${CSMS_PROVIDER:default}
  providers:
    default:
      base-url: ${CSMS_BASE_URL:}
      websocket-url: ${CSMS_WS_URL:}
```

Today, the Citrine adapter is registered as:
- `CitrineCsmsProvider#providerKey()` → `"default"`

To switch providers you will:
- implement a new `CsmsProvider` adapter with a different `providerKey()` (e.g. `"vendorx"`)
- set `csms.provider=vendorx`
- **do not change services/controllers**

### Provider-specific URLs, auth, API paths
Provider-specific configuration should live under `csms.providers.<key>.*` OR under a provider-specific prefix.

This codebase currently keeps Citrine config under:
- `citrine.*` (kept for backward compatibility)

If your new CSMS needs:
- `base-url`
- `websocket-url`
- credentials / auth tokens
- tenant ids / site ids / timeouts / retries

Add them under:
- `csms.providers.<yourKey>.<field>`
and bind them via a `@ConfigurationProperties` class for that provider adapter.

---

## Where to implement CSMS features (block/unblock/start/stop/status)

### 1) Implement `CsmsProvider` (adapter “facade”)
Create a new package:
- `com.karocharge.integration.<providerKey>/`

Add:
- `<Provider>CsmsProvider implements CsmsProvider`
  - returns your charging + operator port implementations

Reference implementation (existing):
- `com.karocharge.integration.citrine.CitrineCsmsProvider`

### 2) Implement charging control port (block/unblock/start/stop)
Interface:
- `com.karocharge.backend.integration.csms.ports.CsmsChargingPort`

Methods you must implement:
- `Integer defaultEvseId()`
- `void blockCharger(String chargerId, Integer evseId)`
- `void unblockCharger(String chargerId, Integer evseId)`
- `CsmsStartChargingResult startCharging(String chargerId, Integer remoteStartId, String idToken, Integer evseId)`
- `void stopCharging(String chargerId, String transactionId)`

Return contract:
- `CsmsStartChargingResult.transactionId` must be set when the provider returns one.
  - If your provider doesn’t return a transaction id immediately, return `null`.
  - `ChargingControlService` will generate a fallback transaction id (preserves current behavior).

Existing implementation:
- `com.karocharge.integration.citrine.CitrineChargingPortAdapter`

### 3) Implement operator monitoring port (status live view)
Interface:
- `com.karocharge.backend.integration.csms.ports.CsmsOperatorPort`

Methods:
- `boolean isHttpReachable()`
- `List<CsmsChargingStationView> fetchChargingStations()`
- `List<CsmsOperatorLocationView> fetchOperatorLocations()`
- `String baseUrl()`
- `String websocketUrl()`

These views are provider-neutral shapes used by `OperatorService`:
- `CsmsChargingStationView`
- `CsmsOperatorLocationView` → includes `CsmsOperatorChargerView`

Existing implementation:
- `com.karocharge.integration.citrine.CitrineOperatorPortAdapter`

---

## Files you should update when adding a new CSMS

### Required: new adapter files (provider package)
Add under `karocharge_API/src/main/java/com/karocharge/integration/<yourProvider>/`:
- `<YourProvider>CsmsProvider.java` (implements `CsmsProvider`)
- `<YourProvider>ChargingPortAdapter.java` (implements `CsmsChargingPort`)
- `<YourProvider>OperatorPortAdapter.java` (implements `CsmsOperatorPort`)
- `<YourProvider>Config.java` (`@ConfigurationProperties` for your settings)
- `<YourProvider>Client.java` (REST/WS/GraphQL client(s), auth helpers, DTO mappers)

### Required: configuration
Update:
- `karocharge_API/src/main/resources/application.yml`
  - add `csms.provider: <yourKey>` (or set via env `CSMS_PROVIDER`)
  - add `csms.providers.<yourKey>.*` config (URLs, credentials, timeouts)

### Not required (and should NOT be edited for CSMS swap)
Do not change:
- `com.karocharge.backend.service.ChargingControlService`
- `com.karocharge.backend.service.OperatorService`
- `com.karocharge.backend.controller.*`
- API DTOs under `com.karocharge.backend.dto.*`

---

## “Replace Citrine with another CSMS” checklist

1. **Create new provider package**
   - `com.karocharge.integration.vendorx.*`

2. **Implement ports**
   - `CsmsChargingPort` for block/unblock/start/stop
   - `CsmsOperatorPort` for live view/status reads

3. **Implement `CsmsProvider`**
   - return your port adapters
   - set `providerKey()` to `"vendorx"`

4. **Configure**
   - set `CSMS_PROVIDER=vendorx`
   - set required env vars for your adapter (URLs, auth)

5. **Run**
   - existing endpoints must behave the same

---

## Notes on WebSocket “live monitoring”

Today, “live status” in operator UI comes from **CSMS read APIs** (in Citrine case: Hasura GraphQL) and the backend simply **exposes a computed `chargingUrl`** using `websocketUrl`.

If you want true event-driven monitoring (recommended future enhancement):
- add a new port (example): `CsmsEventsPort`
- implement it in each provider adapter (WS client, subscriptions, reconnect, etc.)
- update DB session/charger status asynchronously

That change can be done without touching controllers by keeping it inside:
- provider adapter + a CSMS-neutral event handler service

---

## Troubleshooting

- **App starts but operator shows no stations**
  - Check your adapter’s `fetchChargingStations()` implementation.
  - For Citrine: check `citrine.hasura-url` and admin secret.

- **Start/Stop works but transaction id missing**
  - Ensure `CsmsChargingPort.startCharging()` maps the provider response into `CsmsStartChargingResult.transactionId`.
  - If the provider doesn’t return an id, fallback ids will be generated (current behavior).

- **Switching provider does nothing**
  - Confirm your provider bean is discovered by Spring (package under `com.karocharge.*` is scanned).
  - Confirm `providerKey()` matches `csms.provider`.

