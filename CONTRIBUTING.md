# Contributing / Developer quickstart

This document has quick, copy-paste steps for building, testing and debugging Open WiFi Scanner
on a machine with a bash shell (Linux, macOS, or WSL). It also includes tips for running the background
AutoConnectService and where to find logs and test doubles.

Prerequisite: JDK 21 (the project uses Java/Kotlin toolchain 21).

Build

```bash
./gradlew assembleDebug --no-daemon
```

Run unit tests

Run all unit tests:

```bash
./gradlew testDebugUnitTest --no-daemon
```

Run a single test class (example):

```bash
./gradlew testDebugUnitTest --tests "com.dm.labs.wifi.autoconnect.AutoConnectCoordinatorTest" --no-daemon
```

Install & run on device/emulator

```bash
./gradlew installDebug --no-daemon
# Launch the main activity
adb shell am start -n com.dm.labs.wifi/.ui.MainActivity
```

Grant runtime permissions (useful for automated testing via adb)

```bash
# Location & Wi-Fi permissions
adb shell pm grant com.dm.labs.wifi android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.dm.labs.wifi android.permission.ACCESS_WIFI_STATE
adb shell pm grant com.dm.labs.wifi android.permission.CHANGE_WIFI_STATE

# (API 33+) Nearby devices & notifications
adb shell pm grant com.dm.labs.wifi android.permission.NEARBY_WIFI_DEVICES
adb shell pm grant com.dm.labs.wifi android.permission.POST_NOTIFICATIONS
```

Start/stop background AutoConnectService (foreground service)

```bash
# API >= 26: use start-foreground-service
adb shell am start-foreground-service -n com.dm.labs.wifi/.autoconnect.AutoConnectService
# Stop it
adb shell am force-stop com.dm.labs.wifi
# Or explicitly stop the service
adb shell am stopservice -n com.dm.labs.wifi/.autoconnect.AutoConnectService
```

Where logs live

- User-facing scan logs: in-app `ScanLogManager` (visible in the Logs tab).
- Developer logs: file-based, stored under the app files directory in `dev_logs/` (see `DevLog.getLogFiles()` / `DevLog.readAllLogs()`).
    When running on device, pull logs via adb:

    ```bash
    adb shell run-as com.dm.labs.wifi cat files/dev_logs/dev_log_2026-04-13.txt > dev_log.txt
    ```

Quick test double pattern

Many core components are defined as simple interfaces in `app/src/main/java/com/dm/labs/wifi/model/WifiContracts.kt`:
- `WifiScanner`, `WifiConnector`, `CaptivePortalChecker`.

When writing unit tests, create small fake implementations and inject them into the class under test. Example (Kotlin, unit test):

```kotlin
class FakeScanner(private val result: Result<List<WifiNetwork>>) : WifiScanner {
    override suspend fun scanOpenNetworks() = result
}

class FakeConnector(private val result: ConnectAttemptResult) : WifiConnector {
    override suspend fun connectToOpenNetwork(ssid: String) = result
    override fun disconnectCurrentNetwork() {}
}

// Inject into AutoConnectCoordinator or WifiViewModel in tests
val coord = AutoConnectCoordinator(FakeScanner(Result.success(listOf(...))), FakeConnector(...), FakeChecker(...))
```

Key files to read first
- `app/src/main/java/com/dm/labs/wifi/ui/MainActivity.kt` — wiring + runtime permission handling
- `app/src/main/java/com/dm/labs/wifi/model/WifiContracts.kt` — interfaces used across the codebase
- `app/src/main/java/com/dm/labs/wifi/autoconnect/AutoConnectCoordinator.kt` and `AutoConnectService.kt` — core auto-connect logic and service loop
- `app/src/main/java/com/dm/labs/wifi/platform/AndroidWifiConnector.kt` / `AndroidWifiScanner.kt` — platform implementations that call Android system APIs
- `app/src/main/java/com/dm/labs/wifi/captive/CaptivePortalAutoSolver.kt` — captive portal heuristics and HTTP auto-solve
- `app/src/main/java/com/dm/labs/wifi/data/*` — Room DAOs/entities and repository adapters

Notes & gotchas
- No DI framework: dependencies are passed explicitly (see `WifiViewModelFactory`) — follow this pattern for tests and alternative implementations.
- Auto-connect uses the `WifiNetworkSuggestion` API first, and falls back to `WifiNetworkSpecifier` which shows a system dialog; changing connection behaviour must consider API-level differences (see `AndroidWifiConnector.connectToOpenNetwork`).
- `CaptivePortalAutoSolver` performs HTTP-based form submission using permissive regexes. Be careful when editing regex patterns — they intentionally accept imperfect HTML.

If you'd like, I can add a small sample test double package (`app/src/test/.../fakes`) containing reusable fakes for `WifiScanner`/`WifiConnector`/`CaptivePortalChecker` — say the word and I'll add it.

