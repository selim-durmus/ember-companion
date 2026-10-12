# Ember Companion

A small, local-first Android controller for Ember mugs. It talks directly to the mug over Bluetooth LE; no Ember account or cloud service is involved.

## Features

- Live drink temperature on a dial, with liquid state, battery/charging and fill level
- Target temperature (49–63 °C / 121–145 °F) via −/+ stepper, slider or three presets. Changes apply automatically and are read back from the mug to confirm them
- Heater on/off switch
- Long-press a preset to save the current target to it
- Celsius or Fahrenheit, synced to the mug
- Several mugs: pick, pair and switch between them; local nicknames tell identical mugs apart
- Rename the mug and change its LED colour
- Mug details (firmware, hardware, bootloader, control access) and an activity log
- **Ready alerts**: an optional foreground service keeps the mug connected and notifies once when the drink reaches its target. It pauses while the mug is empty
- Home-screen widget and Quick Settings tile that can heat or turn off the mug without opening the app
- Recovers on its own from dropped connections, a sleeping mug and Bluetooth being toggled

Protocol notes and the physical-device findings are in [docs/protocol.md](docs/protocol.md).

## Build

Requirements: JDK 17+ and the Android SDK with platform 37.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Install the debug APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app targets Android 17 (API 37) and supports Android 12 (API 31) and newer.

## Architecture

| Layer | Files |
|---|---|
| BLE protocol and GATT session | `ble/EmberProtocol.kt`, `ble/EmberBleClient.kt`, `ble/ReconnectPolicy.kt` |
| State and persistence | `data/EmberRepository.kt`, `data/EmberAppState.kt`, `data/EmberPreferences.kt` |
| UI (Jetpack Compose, Material 3) | `MainActivity.kt`, `MainViewModel.kt`, `ui/` |
| Background and system surfaces | `EmberConnectionService.kt`, `QuickActionReceiver.kt`, `EmberWidgetProvider.kt`, `EmberTileService.kt` |

`EmberRepository` is the single source of truth. It decides when the monitoring service runs and how hard the BLE client should try to stay connected. The activity, service, widget and tile all observe its `StateFlow`.

## Project status

This is an independent, experimental project based on observed BLE behaviour. It is not affiliated with or endorsed by Ember Technologies, Inc. Mug firmware changes may alter the protocol or behaviour.
