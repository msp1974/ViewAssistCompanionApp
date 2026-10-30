#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
JNI_LIBS_DIR="$MODULE_DIR/src/main/jniLibs"

echo "[clean_outputs] Removing generated JNI libraries and build info"
rm -rf \
    "$JNI_LIBS_DIR/arm64-v8a" \
    "$JNI_LIBS_DIR/armeabi-v7a" \
    "$JNI_LIBS_DIR/x86_64" \
    "$JNI_LIBS_DIR/BUILD_INFO.txt"

echo "[clean_outputs] Done"
