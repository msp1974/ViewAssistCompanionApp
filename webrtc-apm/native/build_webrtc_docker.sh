#!/usr/bin/env bash
#
# Reproducible Docker wrapper for building WebRTC APM JNI libs.
# This script:
#   1) Builds a pinned Linux builder image from native/docker/Dockerfile.
#   2) Runs native/build_webrtc.sh inside that container.
#   3) Persists all caches/sources in repo-local .local/ for repeatable builds.
#
# Usage:
#   ./native/build_webrtc_docker.sh
#   # Optional:
#   # WEBRTC_REV=<commit-or-tag> ./native/build_webrtc_docker.sh
#   # REBUILD_IMAGE=1 ./native/build_webrtc_docker.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
DOCKERFILE="$SCRIPT_DIR/docker/Dockerfile"
IMAGE_TAG="viewassist/webrtc-apm-builder:ubuntu22.04-v1"
REBUILD_IMAGE="${REBUILD_IMAGE:-0}"
DOCKER_PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
DOCKER_CACHE_ROOT_REL="${DOCKER_CACHE_ROOT_REL:-.local}"
CACHE_ROOT_IN_CONTAINER="/workspace/webrtc-apm/$DOCKER_CACHE_ROOT_REL"

if ! command -v docker &>/dev/null; then
    echo "ERROR: docker is required but was not found on PATH."
    exit 1
fi

if [[ ! -f "$DOCKERFILE" ]]; then
    echo "ERROR: Dockerfile not found at $DOCKERFILE"
    exit 1
fi

if [[ "$REBUILD_IMAGE" == "1" ]] || ! docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
    echo "[build_webrtc_docker] Building builder image: $IMAGE_TAG ($DOCKER_PLATFORM)"
    docker build --platform "$DOCKER_PLATFORM" -t "$IMAGE_TAG" -f "$DOCKERFILE" "$MODULE_DIR"
fi

IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_TAG")"
echo "[build_webrtc_docker] Using builder image id: $IMAGE_ID"
echo "[build_webrtc_docker] Docker cache root: $DOCKER_CACHE_ROOT_REL"

CONTAINER_CMD="./native/build_webrtc.sh"
if [[ -n "${WEBRTC_REV:-}" ]]; then
    CONTAINER_CMD="WEBRTC_REV=${WEBRTC_REV@Q} ./native/build_webrtc.sh"
fi

echo "[build_webrtc_docker] Running Linux build container"
docker run --rm -t \
    --platform "$DOCKER_PLATFORM" \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e WEBRTC_LOCAL_DIR="$CACHE_ROOT_IN_CONTAINER" \
    -e DOCKER_BUILDER_IMAGE_TAG="$IMAGE_TAG" \
    -e DOCKER_BUILDER_IMAGE_ID="$IMAGE_ID" \
    -v "$MODULE_DIR:/workspace/webrtc-apm" \
    -w /workspace/webrtc-apm \
    "$IMAGE_TAG" \
    bash -lc "$CONTAINER_CMD"

echo "[build_webrtc_docker] Done. ABI libs are under:"
echo "  $MODULE_DIR/src/main/jniLibs/arm64-v8a/libwebrtc_apms.so"
echo "  $MODULE_DIR/src/main/jniLibs/armeabi-v7a/libwebrtc_apms.so"
echo "  $MODULE_DIR/src/main/jniLibs/x86_64/libwebrtc_apms.so"
