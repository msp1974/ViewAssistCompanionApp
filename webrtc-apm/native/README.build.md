# Native Build Guide

This module builds `libwebrtc_apms.so` from official Google WebRTC sources.

Build outputs:
- `src/main/jniLibs/arm64-v8a/libwebrtc_apms.so`
- `src/main/jniLibs/armeabi-v7a/libwebrtc_apms.so`
- `src/main/jniLibs/x86_64/libwebrtc_apms.so`
- `src/main/jniLibs/BUILD_INFO.txt`

`BUILD_INFO.txt` records pinned WebRTC revision and Docker builder metadata.

## Reproducible Docker Build (recommended)

```bash
cd /path/to/ViewAssistCompanionApp/webrtc-apm
./native/build_webrtc_docker.sh
```

What this does:
- Builds a Linux builder image from `native/docker/Dockerfile` (base image pinned by digest)
- Runs `native/build_webrtc.sh` in Docker
- Bootstraps/fetches WebRTC and tools into repo-local `.local/`
- Builds all ABIs and writes outputs into `src/main/jniLibs/`

Optional:

```bash
# Override pinned revision for this run
WEBRTC_REV=<commit-or-tag> ./native/build_webrtc_docker.sh

# Rebuild builder image even if cached
REBUILD_IMAGE=1 ./native/build_webrtc_docker.sh

# Override Docker cache location under repo root
DOCKER_CACHE_ROOT_REL=.local ./native/build_webrtc_docker.sh
```

## Native Linux Build

```bash
cd /path/to/ViewAssistCompanionApp/webrtc-apm
./native/build_webrtc.sh
```

`build_webrtc.sh` requires Linux and exits on non-Linux hosts.

## Cleanup Scripts

```bash
# Remove generated AAR JNI outputs
./native/clean_outputs.sh

# Remove local build cache and fetched sources (.local/)
./native/clean_local_cache.sh
```

## Verify Build Artifacts

```bash
llvm-readelf --dyn-syms src/main/jniLibs/arm64-v8a/libwebrtc_apms.so | grep JNI_OnLoad
```
