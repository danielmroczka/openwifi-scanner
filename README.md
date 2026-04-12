# WIFI app

This Android app scans nearby **open Wi-Fi** networks, lets the user tap to connect, and then checks whether a captive portal (sign-in/captcha page) is required.

## What it does

- Requests required runtime permissions for Wi-Fi scan/connect.
- Scans and lists open SSIDs.
- Connects to selected open SSID on Android 10+ using `WifiNetworkSpecifier`.
- Checks network capabilities for captive portal status.
- Shows a **Resolve Captive Portal** button that first tries Android system captive portal UI, then falls back to browser.
- Starts a foreground background-service after app launch (when permissions are granted) and keeps trying open networks until internet is validated.
- If connected network has no validated internet, it disconnects and retries the next open network.

## Important limitation

The app does **not** bypass or auto-solve captchas. It only opens the sign-in page so the user can complete it.

## Test

Run unit tests:

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

