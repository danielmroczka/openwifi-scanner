# Open WiFi Scanner

Android app that scans nearby **open Wi-Fi** networks, auto-connects, detects captive portals, records login steps, and manages trusted/blocked networks by BSSID.

## Features

- **Auto-connect** — foreground service continuously scans open networks and connects to the best available one.
  - **Intelligent prioritization**: Prioritizes whitelisted (favorite) networks, then sorts by signal strength (RSSI).
  - **Blacklist support**: Automatically skips blacklisted networks.
  - **Cooldown management**: Places networks on 1-hour cooldown after failed connection attempts.

- **Network management by BSSID** — track networks by both SSID and BSSID, so the same network name at different locations (different routers) is tracked separately.
  - *Whitelisted* networks are auto-connected without prompting.
  - *Blacklisted* networks are skipped by the scanner.
  - GPS location captured when network is added to a list.

- **Captive Portal Handling**:
  - Automatic HTTP-based portal solve attempts (parses forms, submits data).
  - Records login steps for manual solutions (clicks, inputs, form submissions).
  - Hierarchical solution lookup:
    1. Exact match: SSID + BSSID + portal host (for specific router instances)
    2. Portal-based: SSID + portal host (for same portal across multiple APs)
    3. Fallback: SSID only (legacy support)
  - Automatic playback of recorded steps on next connection.
  - Export/import solutions to share with other users (JSON format).

- **Scan logs** — in-memory log of scan activity and connection attempts (cleared on app close).
- **4-tab UI** — Scanner, Saved Networks, Portal Solutions, Logs.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room (KSP) for persistent blacklist/whitelist storage
- Foreground service for background auto-connect
- `WifiNetworkSuggestion` (silent) with `WifiNetworkSpecifier` fallback (Android 10+)

## Build requirements

- JDK 21 (project is configured for Java/Kotlin toolchain 21)

## Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (API 33+), `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE`

## Build & Test

```powershell
# Build debug APK
.\gradlew.bat assembleDebug --no-daemon

# Run unit tests
.\gradlew.bat testDebugUnitTest --no-daemon

# Key test suites
# - AutoConnectCoordinatorTest: basic auto-connect flow, filtering, cooldown
# - NetworkSortingAndPrioritizationTest: whitelist priority, signal strength sorting
# - CaptivePortalSolutionHierarchyTest: hierarchical solution lookup by SSID/BSSID/portal
# - CaptivePortalRecoveryHandlerTest: portal recovery flow
```

## Architecture & Data Flow

### Core Components

- **WifiScanner interface** → `AndroidWifiScanner`: Scans available open networks
- **WifiConnector interface** → `AndroidWifiConnector`: Connects to networks with API-level fallbacks
- **CaptivePortalChecker interface** → `AndroidCaptivePortalChecker`: Checks portal status (open internet / captive portal / unknown)
- **AutoConnectCoordinator**: Orchestrates scan → filter → prioritize → connect → validate → captive portal handling
- **CaptivePortalAutoSolver**: Attempts HTTP-based portal solve, then falls back to user-guided recording
- **CaptivePortalRecorder**: Records user interactions (clicks, inputs, form submits) for replay

### Database Schema (Room)

- **wifi_networks** — network metadata (SSID, BSSID, whitelist/blacklist flags, location)
- **captive_portal_solutions** — recorded login solutions (SSID, **BSSID**, **portalHost**, steps)
- **captive_portal_steps** — individual steps (type, CSS selector, form data, values)

### Network Prioritization Algorithm

When scanning, networks are filtered and sorted:

1. **Filter**: Remove blacklisted and cooldown networks
2. **Sort** (multi-level):
   - Whitelisted (favorite) networks first
   - Within each tier, sort by **signal strength** (RSSI, higher dBm = better)

Example:
- Whitelisted -50 dBm → attempted first
- Whitelisted -70 dBm → attempted second
- Unknown -40 dBm → attempted third
- Unknown -60 dBm → attempted fourth

### Captive Portal Solution Lookup

When a captive portal is detected, the app attempts to find and replay a saved solution:

**Priority 1: Exact match** (SSID + BSSID + portalHost)
- Use for specific router instances with known portal addresses

**Priority 2: Portal-based** (SSID + portalHost)
- Use when same portal is used across multiple APs/routers for same network

**Priority 3: SSID fallback** (SSID only)
- Legacy support for solutions recorded before BSSID/portalHost tracking

If no solution found, app attempts HTTP-based automatic solve, then prompts user to record steps.

## Important limitations

- The app does **not** bypass or auto-solve captchas. It only opens the sign-in page so the user can complete it.
- Recorded login steps are specific to their portal instance. If the portal changes HTML/layout, steps may not work.
- Replay assumes the portal structure remains consistent between visits (same selectors, form layout).
- Sharing solutions between users requires the JSON export/import flow — no built-in cloud sync.

## Future enhancements

- Peer-to-peer sharing of portal solutions (local network or cloud sync)
- Step validation/testing before saving
- Better captcha detection and user notification
- Analytics on success rates of auto-connect and portal solve attempts
