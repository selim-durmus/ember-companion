# Ember Companion

A small, local-first Android controller for an Ember Ceramic Mug. It talks directly to the mug over BLE; it does not require an Ember account or cloud service.

Current MVP scope:

- reconnect to a previously bonded mug without tying BLE lifetime to the screen
- choose between bonded, saved, and nearby Ember mugs without dropping the active connection
- assign local nicknames so identical mugs are easy to distinguish
- show current/target temperature, battery, charger, liquid level, and liquid state
- set the target temperature from 49–63 °C
- switch between Celsius and Fahrenheit and save three presets
- turn the heater off
- customize the hardware mug name and identification LED colour
- inspect live and cached mug details, including firmware, hardware, bootloader, and control access
- optionally keep monitoring in the background and notify when a drink is ready
- use a home-screen widget and Quick Settings tile for common actions
- subscribe to mug push events and refresh changed values
- retry transient Android GATT disconnects with stale-callback and operation-timeout protection

Protocol details and the physical-device findings are in [docs/protocol.md](docs/protocol.md).

## Build

Install JDK 17 and the Android SDK, then point the standard environment variables at your local installations:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

Install the debug APK:

```sh
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app targets Android 16/API 36 and has a minimum of Android 12/API 31.

## Project status

This is an independent, experimental project based on observed BLE behaviour. It is not affiliated with or endorsed by Ember Technologies, Inc. Mug firmware changes may alter the protocol or behaviour.
