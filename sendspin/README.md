# :sendspin

Native [Sendspin](https://www.sendspin-audio.com/) player support. The protocol, clock sync, FLAC/Opus
decoding and playback scheduling come from [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp)
(player, metadata and controller roles), pinned in `src/main/cpp/CMakeLists.txt`. `sendspin_jni.cpp` is the JNI bridge and
`NativeSendspin.kt` its Kotlin surface. The app side lives in
`app/.../sendspin/` (audio output, player session, mDNS advertiser, manager).

## Build

The first native build fetches sendspin-cpp and its dependencies with CMake `FetchContent`, so it needs
`git` and network access. Only `arm64-v8a` and `armeabi-v7a` are built, matching `:app`.

## Third-party licenses

Fetched at build time and linked into `libsendspin_jni.so`:

- sendspin-cpp — Apache-2.0
- ArduinoJson — MIT
- micro-flac, micro-opus (libopus, libFLAC) — Apache-2.0 / BSD-3-Clause
- IXWebSocket — BSD-3-Clause

## Protocol version

The pinned sendspin-cpp (v0.8.0) speaks the plaintext Sendspin protocol: it has no Noise encryption
or pairing, so Music Assistant reports the device as connected in legacy mode. v0.9.0 and later add
encryption and pairing; moving to one means a bridge change (a persistence provider for the device
key and paired-server records, and the pairing callbacks).
