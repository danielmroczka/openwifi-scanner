# Agents guide — Open WiFi Scanner

Purpose
- Short, focused instructions to help an AI coding agent become productive in this repo quickly.

Big picture (how pieces fit)
- UI: Jetpack Compose in `app/src/main/java/com/dm/labs/wifi/ui/*` (MainActivity, `WifiViewModel`). The app is a single-activity Compose app with 4 tabs (Scanner, Networks, Solutions, Logs).
- Platform abstraction: `platform/AndroidWifiScanner`, `AndroidWifiConnector`, `AndroidCaptivePortalChecker` implement the interfaces in `model/WifiContracts.kt` (WifiScanner, WifiConnector, CaptivePortalChecker). Prefer changing interfaces or adding implementations there to affect runtime behaviour across app.
- Background auto-connect: `autoconnect/AutoConnectService` (foreground service) runs `AutoConnectCoordinator` which orchestrates scans, repository filtering and connection attempts.
- Data layer: Room entities/DAO/repository in `data/` (e.g. `WifiNetworkRepository`, `RoomWifiNetworkRepository`, `CaptivePortalSolutionDao` + `AppDatabase`). KSP is used (Room compiler configured in `app/build.gradle.kts`).
- Captive portal handling: `captive/CaptivePortalAutoSolver` attempts HTTP-based automatic solves, then launches `CaptivePortalSolverActivity` if needed.

Critical workflows (commands & runtime)
- Build APK: `.
  .\gradlew.bat assembleDebug --no-daemon
  ` (also in repo `README.md`).
- Run unit tests: `.
  .\gradlew.bat testDebugUnitTest --no-daemon
  `
- Key runtime actions:
  - Grant runtime permissions listed in `AndroidManifest.xml` / `MainActivity.requiredPermissions()` before scanning: ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, (API33+) NEARBY_WIFI_DEVICES and POST_NOTIFICATIONS.
  - Start/stop background auto-connect: `AutoConnectService.start(context)` / `AutoConnectService.stop(context)` (used by UI buttons in `MainActivity`). The service is declared in `AndroidManifest.xml` and created as a foreground service.
  - Boot auto-start: `BootReceiver` is registered in `AndroidManifest.xml` — service auto-start behaviour is guarded by `AppSettings.autoStartOnBoot`.

Project-specific conventions & patterns
- Platform interface pattern: The app depends on model interfaces (e.g. `WifiScanner`, `WifiConnector`, `CaptivePortalChecker`) and constructs Android implementations in `MainActivity` (via `WifiViewModelFactory`). Use the interface-first approach when adding tests or alternate implementations.
- No DI framework: Dependencies are passed explicitly (see `WifiViewModelFactory` in `WifiViewModel.kt`) — follow this pattern for new features or test doubles.
- Repository wrapper over Room: `WifiNetworkRepository` defines behaviour; `RoomWifiNetworkRepository` adapts DAO. Update repository methods if you change persistence semantics.
- Single source of auto-connect state: `AutoConnectRuntime` implements `AutoConnectStateSource` and is observed by `WifiViewModel` to reflect background service state in the UI.
- Scan results grouping: `WifiViewModel.scanOpenNetworksInternal()` groups ScanResults by SSID, selecting the strongest AP to represent an SSID in the UI (see `groupBy` then `maxByOrNull`). Keep this behaviour when modifying presentation logic.
- Logging: Two log channels — user-facing scan logs via `ScanLogManager` and developer logs via `DevLog` (files kept 7 days). Use those managers to surface messages to the Logs tab.
- Cooldown & approvals: `NetworkCooldownManager` prevents immediate reattempts; `NetworkApprovalManager` mediates prompting the user for unknown networks (the service uses `NetworkApprovalManager.requestApproval()` and `awaitDecision()`). Respect these when changing auto-connect flow.

Integration points & external dependencies
- Android system services: `WifiManager`, `ConnectivityManager` (used heavily in `platform/AndroidWifiConnector` and `AndroidCaptivePortalChecker`). Modifying connection logic must consider API level fallbacks (suggestion vs specifier). See `AndroidWifiConnector.connectToOpenNetwork()` for the suggestion/specifier pattern.
- Room DB: `AppDatabase`, DAOs in `data/` and `ksp` compiler configured in `app/build.gradle.kts` (ksp + Room). When changing schema, update Room entities/DAOs and migration policy.
- Network-based heuristics: `CaptivePortalAutoSolver` uses a connectivity check URL `http://connectivitycheck.gstatic.com/generate_204` and HTML regex extraction (FORM_ACTION_RE, INPUT_RE). Be cautious changing regexes — they are intentionally permissive.

Quick pointers for common changes
- Add a new WifiConnector implementation: implement `WifiConnector` and inject into `WifiViewModelFactory` in `MainActivity` for manual testing.
- Change auto-connect behaviour: modify `AutoConnectCoordinator.connectNextOpenNetworkCycle()` (filters, approval callback, attempt ordering) and `AutoConnectService` loop (how often to rescan, cooldown handling).
- Add a Room-backed feature: update `data/*` (Entity + Dao + Repository), then call `AppDatabase.getDatabase()` in `MainActivity` to construct repository instances.

Files to read first (fast path)
- `app/src/main/java/com/dm/labs/wifi/ui/MainActivity.kt` — composition + wiring of dependencies and runtime permission handling.
- `app/src/main/java/com/dm/labs/wifi/model/WifiContracts.kt` — interfaces & contracts.
- `app/src/main/java/com/dm/labs/wifi/platform/AndroidWifiConnector.kt` and `AndroidWifiScanner.kt` — critical platform logic.
- `app/src/main/java/com/dm/labs/wifi/autoconnect/AutoConnectCoordinator.kt` and `AutoConnectService.kt` — background logic and service lifecycle.
- `app/src/main/java/com/dm/labs/wifi/captive/CaptivePortalAutoSolver.kt` — how captive portals are detected & solved (HTTP-first, WebView fallback).
- `app/src/main/java/com/dm/labs/wifi/data/*` — Room DAOs, entities and repository adapters.

Testing & debug tips for agents
- Unit tests run via Gradle wrapper above. Many components use simple interfaces so create fake `WifiScanner`/`WifiConnector`/`CaptivePortalChecker` to test coordinator and ViewModel logic.
- To reproduce auto-connect flows locally, run on a device or emulator with Wi‑Fi available and grant location permissions. Use logs from `ScanLogManager` and `DevLog.readAllLogs()` to triage.

What this file includes
- A concise architecture overview, developer workflows (build/test), key conventions (interfaces, repository pattern, no DI), integration points (Wifi/Connectivity/Room/Foreground service), and file pointers to get started quickly.

If you want, I can also:
- Add a minimal CONTRIBUTING.md with reproducible debug steps and how to run the AutoConnectService in an emulator/device.
- Create test doubles (fake scanner/connector) and a unit test harness for `AutoConnectCoordinator`.

