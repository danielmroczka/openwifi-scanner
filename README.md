# Open WiFi Scanner

Android app that scans nearby **open Wi-Fi** networks, auto-connects, detects captive portals, and manages trusted/blocked networks by BSSID.

## Features

- **Auto-connect** — foreground service continuously scans open networks and connects to the best available one.
- **Blacklist / Whitelist** — manage networks by BSSID (not just SSID), so the same network name at different locations is tracked separately.
  - *Whitelisted* networks are auto-connected without prompting.
  - *Blacklisted* networks are skipped by the scanner.
- **GPS location** — captures coordinates when a network is added to a list, so you can see where it was first encountered.
- **Captive portal detection** — checks for sign-in pages and offers a button to open the portal UI.
- **Scan logs** — in-memory log of scan activity and connection attempts (cleared on app close).
- **4-tab UI** — Scanner, Whitelist, Blacklist, Logs.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room (KSP) for persistent blacklist/whitelist storage
- Foreground service for background auto-connect
- `WifiNetworkSuggestion` (silent) with `WifiNetworkSpecifier` fallback (Android 10+)

## Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (API 33+), `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE`

## Build & Test

```powershell
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
```

## Important limitation

The app does **not** bypass or auto-solve captchas. It only opens the sign-in page so the user can complete it.
