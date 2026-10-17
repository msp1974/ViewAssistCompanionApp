# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

View Assist Companion App (VACA / package `com.msp1974.vacompanion`) — an Android app that turns a tablet/kiosk device into a Home Assistant "View Assist" satellite: it shows a kiosk WebView pointed at a Home Assistant dashboard, and runs an always-listening voice satellite (wake word detection → audio streaming → TTS playback) speaking the [Wyoming protocol](https://github.com/rhasspy/wyoming) over a local TCP server that Home Assistant connects to.

## Build / lint / run

This is a Gradle Android project (Kotlin, Jetpack Compose, Hilt DI). Use the Gradle wrapper; there is no separate lint/test command setup beyond the Android Gradle Plugin defaults, and there are currently no unit/instrumentation tests checked in.

```
./gradlew assembleDebug              # build debug APK
./gradlew assembleRelease            # build release APK (needs KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars)
./gradlew lint                       # Android lint
./gradlew printVersionName           # prints app/build.gradle.kts versionName (used by CI/env)
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Note: every Gradle sync/build **increments and persists `version.properties`** (`VERSION_CODE`) as a side effect of evaluating `app/build.gradle.kts` — this file will show as modified after essentially any build, independent of code changes. Don't be alarmed by it appearing in `git status`/`git diff`; only worry if `VERSION_CODE` changes are being deliberately committed.

There are 3 Gradle modules (`settings.gradle.kts`):
- `:app` — the application itself.
- `:microfeatures` — an Android library with a JNI/CMake component (`src/main/cpp`, TensorFlow Lite + kissfft) providing the "micro frontend" (audio → spectrogram features) used by the microWakeWord engine.

## High-level architecture

### Two independent "screens" driven by one Activity

`MainActivity` hosts a single Compose tree that switches between:
- **ConnectionScreen** — shown before the satellite/webview is up (status text, connection state).
- **WebViewScreen** — the actual kiosk view once the satellite has started, loading the Home Assistant dashboard URL via a `CustomWebView`. `SettingsLayout` overlays this when the on-device settings menu is open.
- **BlackScreen** — shown when the screen is intentionally blanked (screensaver / `screenSaver` config flag).

State is centralized in `VAViewModel` (`ui/VAViewModel.kt`) via a single `vacaState` `StateFlow`, and cross-cutting settings/events flow through `APPConfig.eventBroadcaster` (an `EventNotifier`/`EventListener` pub-sub — see `utils/Events.kt`), which both `MainActivity` and `Satellite` subscribe to for things like screen mode, dark mode, mute state, etc. Don't wire new cross-component state through direct references between `MainActivity`/`Satellite`/`VAViewModel` — go through `config.eventBroadcaster` or `BroadcastSender` (`broadcasts/BroadcastSender.kt`, backed by `LocalBroadcastManager` + explicit `Context.registerReceiver`) the way existing code does.

### DI and app-wide singletons

Hilt (`@HiltAndroidApp` on `VACAApplication`, `@AndroidEntryPoint` on `MainActivity`/`VAForegroundService`). The one hand-wired module is `di/AppModule.kt`, providing the single `DeviceManager` singleton. `DeviceManager` (`device/DeviceManager.kt`) is the root object graph: it owns `APPConfig` (`settings/Settings.kt` — the mutable, `SharedPreferences`-backed app config, with `Delegates.observable` properties that fire `eventBroadcaster` notifications on change), `DeviceInfo`, `SensorManager`, and `AuthenticationManager` (HA long-lived token auth), and exposes `StateFlow`s for network/server/sensor/UI status.

### Wyoming server → Satellite → AudioPipeline (the core voice flow)

This is the part most feature work touches. Layers, outside-in:

1. **`wyoming/WyomingTCPServer`** (abstract, instantiated anonymously in `VAForegroundService`/`BackgroundTask`) — raw Ktor TCP server + mDNS/Zeroconf advertisement (`wyoming/Zeroconf.kt`) that Home Assistant discovers and connects to. Owns the client connection map, handles the Wyoming handshake events (`describe`/`info`, `capabilities`, `ping`), and on `run-satellite` creates a `Satellite` instance and forwards all other packets to it. Only one `Satellite` runs at a time; it re-pairs (`handleSatelliteTakeover`) if a new client reconnects with a different `clientId`. Devices are "paired" to the first HA server IP that starts a satellite (`config.pairedDeviceID`) and reject other IPs.
2. **`satellite/Satellite`** (abstract, instantiated anonymously in `WyomingTCPServer`) — the per-connection satellite session. Owns the `SatelliteWakeWorkHandler` (wake word listening loop), a `SatelliteAudioPipeline` (only one active at a time, representing one voice interaction), `SatelliteMediaManager` (music/sound-effect/voice/alarm players), `SatelliteAudioLog` (in-memory diagnostic conversation/audio log surfaced in the on-device diagnostics overlay), and custom Wyoming "events" (`event_type: action|settings|capabilities` — VACA's own extension on top of stock Wyoming, handled in `customEventHandler`/`handleAction`). Handles wake-word-detected → start pipeline, and routes settings/actions coming from the HA custom integration (play/pause media, alarms, toast messages, manual wake, refresh).
3. **`satellite/SatelliteAudioPipeline`** (abstract, instantiated anonymously per-interaction in `Satellite.startAudioPipeline`) — a state machine (`PipelineStage`: STARTING → LISTENING → VOICE_STARTED/STOPPED → AWAITING_RESPONSE → AWAITING_TTS → STREAMING_TTS → ENDED) driving one ASR→intent→TTS round trip over Wyoming messages, plus a watchdog timer that force-ends stuck pipelines. `pipelineStage` is monotonic (can't move backwards). Mic audio is pumped in via `sendMicAudio`; TTS audio comes back and is written to `SatelliteMediaManager.voicePlayer`.
4. **Wake word engines** (`wakeword/`) — `WakeWordEngine` is a thin selector over two implementations of abstract `WakeWordEngineProvider`: `microwakeword/MicroWakeWordEngine` (TFLite models + the native microfrontend from `:microfeatures`) and `openwakeword/OpenWakeWordEngine` (ONNX/TFLite via `ml/OnnxModelRunner` / `TfliteModelRunner`, selectable per `WakeWordEngineModel.OPENWAKEWORD` vs `OPENWAKEWORD_RT`). `SatelliteWakeWorkHandler` wraps whichever engine is active and normalizes it to `AudioResult`/`WakeWordDetection` callbacks consumed by `Satellite`. Audio capture itself goes through `audio/MicrophoneInput`, with DSP/noise-suppression in `audio/AudioDSP`, `audio/rnnoise` (native), `audio/dtln`, and `audio/AudioEnhancer` (software AGC/noise-suppression fallback used only for whichever platform `android.media.audiofx` effect a device doesn't support in hardware).

Wyoming wire format lives in `wyoming/Packet.kt` (`WyomingPacket`, JSON header + optional binary payload) and `wyoming/WyomingTypes.kt` (event name constants, `SatelliteState`, custom event type constants). `WyomingInfoBuilder`/`WyomingCapabilitiesBuilder` construct the `info`/`capabilities` response payloads describing this device to HA.

### WebView ↔ native bridge

`utils/CustomWebView` + `utils/CustomWebViewClient` host the HA dashboard; `jsinterface/JavascriptInterface.kt` is the `@JavascriptInterface`-annotated bridge exposed into the page for JS-to-native calls (e.g. reading device info/settings from dashboard-side custom cards).

### Foreground service lifecycle

`MainActivity` starts `service/VAForegroundService` (a `LifecycleService`), which in turn creates and starts `service/BackgroundTask` (the `WyomingTCPServer` subclass) — this is what keeps the Wyoming server/satellite alive while the Activity is backgrounded or the screen is off. `config.backgroundTaskStatus`/`config.backgroundTaskRunning` track this so `MainActivity.onResume` can detect and restart a dead background task.

### Settings/config persistence

`APPConfig` wraps default `SharedPreferences` plus a mix of transient in-memory fields. Most user-facing settings (wake word, thresholds, screen behavior, custom sounds, etc.) are `Delegates.observable` properties — changing one automatically notifies `eventBroadcaster` listeners (UI, `Satellite`, `MainActivity` screen handling) rather than requiring manual wiring. Server-driven settings arrive as a JSON blob over the custom Wyoming `settings` event and are applied via `APPConfig.processSettings`.

### Sendspin player

Optional (setting `APPConfig.sendspinEnabled`, key `sendspin_enabled`, settable on-device in Configuration or pushed by HA). `service/BackgroundTask` starts `sendspin/SendspinManager`, which runs a `SendspinPlayer` (native sendspin-cpp engine from the `:sendspin` module + `SendspinAudioOutput` AudioTrack) and advertises it over mDNS via `SendspinAdvertiser`; Music Assistant connects to it. It ducks via audio focus and the `voicePlaybackEnded` event, and publishes track info as the `sendspinNowPlaying` event, which `ui/components/NowPlayingOverlay` shows over the web view (transport buttons go back as `sendspinCommand` events). See `sendspin/README.md`.

### Bluetooth proxy

Optional (setting `APPConfig.bluetoothProxyEnabled`, key `bluetooth_proxy_enabled`, settable on-device in Configuration or pushed by HA). `service/BackgroundTask` starts `btproxy/BluetoothProxyManager`, which makes the device look like an ESPHome Bluetooth-proxy node to Home Assistant: `BtProxyApiServer` (pure-JVM ESPHome native API server on port 6053, **plaintext framing only** - no Noise encryption, so no key must be set in HA; hand-rolled protobuf in `ProtoIo`/`ApiMessages`), `BleScanner` (raw advertisement batching/dedupe), `GattManager` (connect/services/read/write/notify, 3 slots, per-connection op queue, synthetic ATT handles) and `BtProxyAdvertiser` (`_esphomelib._tcp` mDNS). Needs `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT` (or `ACCESS_FINE_LOCATION` pre-Android 12), requested via the `OPEN_PERMISSION_SCREEN` broadcast. Known limit: Android stops unfiltered BLE scans while the screen is off.

### Custom/downloadable assets

Wake sounds, alarms, and custom wake word models can be supplied by the HA-side integration and are fetched via `utils/CustomFileDownloader` / `satellite/SatelliteCustomFilesHandler` into app-private storage, then indexed via `data/AvailableWakeSounds`, `data/AvailableAlarms`, `wakeword/AvailableWakeWords`.

## Conventions to follow

- Long-lived per-connection/per-interaction objects (`Satellite`, `SatelliteAudioPipeline`, the anonymous `WyomingTCPServer`/wake-word-handler subclasses) are written as **abstract classes with callback methods** (`onEvent`, `onStateChange`, `onFinish`, etc.) instantiated as anonymous objects at their call site, rather than passed-in interface/lambda parameters. Match this pattern when extending these areas rather than introducing a parallel callback-interface style.
- Cross-cutting state changes propagate via `Event`/`EventListener`/`EventNotifier` (`utils/Events.kt`) through `config.eventBroadcaster`, or via `BroadcastSender` string-constant broadcast actions for coarser app-level signals (satellite started/stopped, version mismatch, close app, etc.). Prefer these existing channels over new direct cross-references between `MainActivity`, `Satellite`, and the UI layer.
- `Timber` is the logging facade everywhere (planted in `VACAApplication`); there's also a thin `utils/Logger` wrapper used in some older code — prefer `Timber` directly for new code, matching most of the codebase.
