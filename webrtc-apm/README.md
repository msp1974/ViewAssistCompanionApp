# WebRTC Audio Processing Module for View Assist

A standalone Android library that exposes the modern WebRTC Audio Processing Module (APM) via JNI, enabling high-quality audio processing with AEC3, RNN-based noise suppression, and AGC2.

Built from the latest [Google WebRTC](https://webrtc.org/) source, replacing the legacy M70-era fork.

## Features

- **AEC3 Echo Cancellation** — Modern echo canceller (replaces legacy AEC)
- **RNN Noise Suppression** — Deep-learning based, far superior to the old spectral NS
- **AGC2 Gain Control** — Adaptive digital gain normalisation
- **Transient Suppression** — Removes keyboard clicks, tapping, and other transient noise
- **High-Pass Filter** — Removes DC offset and low-frequency rumble
- **Voice Activity Detection** — Detects the presence of speech

## Audio Format

- **Sample rate:** 16 kHz
- **Bit depth:** 16-bit PCM
- **Channels:** Mono
- **Frame size:** 160 samples (10 ms)

## Usage

```kotlin
import com.viewassist.webrtc.WebRtcApm

// Create APM with config (all features enabled by default)
val config = WebRtcApm.Config().apply {
    aecEnabled = true
    nsEnabled = true
    nsLevel = WebRtcApm.NsLevel.HIGH
    agcEnabled = true
    hpfEnabled = true
    transientSuppressionEnabled = true
    vadEnabled = false
}
val apm = WebRtcApm(config)

// Process captured audio in 160-sample (10 ms) frames
for (offset in 0 until buffer.size step 160) {
    apm.processCaptureStream(buffer, offset)
}

// Feed render (speaker) audio for AEC echo reference
apm.feedRenderAudioBytes(speakerPcmBytes)

// Reconfigure at runtime without recreating
val newConfig = WebRtcApm.Config().apply { nsLevel = WebRtcApm.NsLevel.VERY_HIGH }
apm.reconfigure(newConfig)

// Clean up
apm.close()
```

## Integration

This library is a module of the ViewAssistCompanionApp build, included in `settings.gradle.kts`:

```kotlin
include(":webrtc-apm")
```

And referenced as a dependency in `app/build.gradle.kts`:

```kotlin
implementation(project(":webrtc-apm"))
```

The app uses it through `audio/WebRtcMicProcessor`, for the microphone's gain control and noise suppression.

## Architecture

```text
com.viewassist.webrtc.NativeApm   — Package-private JNI bridge (RegisterNatives via JNI_OnLoad)
com.viewassist.webrtc.WebRtcApm   — Public API with Config, feedRenderAudio, AutoCloseable
libwebrtc_apms.so                  — Native library (arm64-v8a, armeabi-v7a, x86_64)
native/apm_jni.cpp                 — C++ JNI source using AudioProcessingBuilder
native/BUILD.gn                    — GN build target for WebRTC source tree
native/build_webrtc.sh             — Build automation script
```

## Building the Native Library

See `native/README.build.md` for reproducible Docker and Linux build workflows.
Prebuilt JNI binaries are committed under `src/main/jniLibs/` so consumers can use the module without rebuilding native code first.

Quick start:

```bash
cd /path/to/ViewAssistCompanionApp/webrtc-apm
./native/build_webrtc_docker.sh
```

## Attribution

Originally forked from [brucekayle/webrtc-apm](https://github.com/brucekayle/webrtc-apm). The native library has been completely rebuilt from the latest WebRTC source with a new JNI bridge targeting the modern `AudioProcessingBuilder` API.

## License

The WebRTC source code is licensed by Google under a BSD-style license.
See [webrtc.org/license](https://webrtc.org/support/license) for details.
