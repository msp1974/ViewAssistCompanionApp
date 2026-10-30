#!/usr/bin/env bash
#
# Build libwebrtc_apms.so from a WebRTC source checkout.
#
# Prerequisites:
#   1. depot_tools on PATH (provides gn, ninja)
#   2. WebRTC Android source fetched:
#        mkdir webrtc-checkout && cd webrtc-checkout
#        fetch --nohooks webrtc_android
#        gclient sync
#   3. Set WEBRTC_SRC to point to the "src" directory inside the checkout.
#
# Usage:
#   WEBRTC_SRC=/path/to/webrtc-checkout/src ./build_webrtc.sh
#   # Optional override:
#   # WEBRTC_REV=<commit-or-tag> WEBRTC_SRC=/path/to/webrtc-checkout/src ./build_webrtc.sh
#
# The script will:
#   - Copy apm_jni.cpp, BUILD.gn, and jni_exports.lds into $WEBRTC_SRC/apm_jni/
#   - Verify the WebRTC checkout is at the pinned revision
#   - Build for arm64-v8a, armeabi-v7a, and x86_64
#   - Strip and copy the resulting .so files into src/main/jniLibs/<abi>/
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
LIBS_DIR="$MODULE_DIR/src/main/jniLibs"
PIN_FILE="$SCRIPT_DIR/WEBRTC_REVISION"
LOCAL_DIR="${WEBRTC_LOCAL_DIR:-$MODULE_DIR/.local}"
DEPOT_TOOLS_DIR="$LOCAL_DIR/depot_tools"
DEFAULT_WEBRTC_ROOT="${WEBRTC_ROOT:-$LOCAL_DIR/webrtc-checkout}"
DEFAULT_WEBRTC_SRC="$DEFAULT_WEBRTC_ROOT/src"
GN_BIN=""
NINJA_BIN=""

log() {
    echo "[build_webrtc] $*"
}

ensure_apm_dependency_in_root_build() {
    local root_build="$WEBRTC_SRC/BUILD.gn"
    local marker='deps += [ "//apm_jni:webrtc_apms" ]'

    if [[ ! -f "$root_build" ]]; then
        echo "ERROR: Missing root BUILD.gn at $root_build"
        exit 1
    fi

    if grep -Fq "$marker" "$root_build"; then
        return 0
    fi

    log "Wiring //apm_jni:webrtc_apms into root BUILD.gn default target"
    python3 - "$root_build" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text()

needle = """if (target_os == \"android\") {
      deps += [ \"tools_webrtc:binary_version_check\" ]
    }"""
replacement = """if (target_os == \"android\") {
      deps += [ \"//apm_jni:webrtc_apms\" ]
      deps += [ \"tools_webrtc:binary_version_check\" ]
    }"""

if needle not in text:
    raise SystemExit("ERROR: Could not locate android deps block in root BUILD.gn")

path.write_text(text.replace(needle, replacement, 1))
PY
}

ensure_depot_tools() {
    if command -v fetch &>/dev/null && command -v gclient &>/dev/null; then
        return 0
    fi

    if [[ ! -d "$DEPOT_TOOLS_DIR/.git" ]]; then
        log "Bootstrapping depot_tools into $DEPOT_TOOLS_DIR"
        mkdir -p "$LOCAL_DIR"
        git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS_DIR"
    fi

    export PATH="$DEPOT_TOOLS_DIR:$PATH"
    "$DEPOT_TOOLS_DIR/ensure_bootstrap" >/dev/null

    if ! command -v fetch &>/dev/null || ! command -v gclient &>/dev/null; then
        echo "ERROR: fetch/gclient are unavailable even after bootstrapping depot_tools."
        echo "  Expected depot_tools path: $DEPOT_TOOLS_DIR"
        exit 1
    fi
}

bootstrap_webrtc_checkout() {
    if [[ -f "$DEFAULT_WEBRTC_SRC/modules/audio_processing/include/audio_processing.h" ]]; then
        return 0
    fi

    ensure_depot_tools
    mkdir -p "$DEFAULT_WEBRTC_ROOT"

    if [[ ! -d "$DEFAULT_WEBRTC_SRC/.git" ]]; then
        log "Fetching WebRTC Android sources into $DEFAULT_WEBRTC_ROOT"
        (
            cd "$DEFAULT_WEBRTC_ROOT"
            fetch --nohooks webrtc_android
        )
    fi

    log "Syncing WebRTC dependencies"
    (
        cd "$DEFAULT_WEBRTC_ROOT"
        gclient sync
    )
}

ensure_linux_gn_in_checkout() {
    local linux_gn="$WEBRTC_SRC/buildtools/linux64/gn"
    if [[ -x "$linux_gn" ]]; then
        return 0
    fi
    if [[ "$WEBRTC_SRC" != "$DEFAULT_WEBRTC_SRC" ]]; then
        return 0
    fi

    ensure_depot_tools
    log "Linux GN binary missing; running gclient runhooks to fetch Linux build tools"
    (
        cd "$DEFAULT_WEBRTC_ROOT"
        gclient runhooks
    )
}

ensure_build_tools() {
    local host_os
    host_os="$(uname -s)"

    local gn_candidate=""
    local ninja_candidate="$WEBRTC_SRC/third_party/ninja/ninja"
    case "$host_os" in
        Darwin)
            gn_candidate="$WEBRTC_SRC/buildtools/mac/gn"
            ;;
        Linux)
            gn_candidate="$WEBRTC_SRC/buildtools/linux64/gn"
            ;;
    esac

    if command -v gn &>/dev/null; then
        GN_BIN="$(command -v gn)"
    elif [[ -n "$gn_candidate" && -x "$gn_candidate" ]]; then
        GN_BIN="$gn_candidate"
    fi
    if [[ -n "$GN_BIN" ]] && ! "$GN_BIN" --version >/dev/null 2>&1; then
        GN_BIN=""
    fi
    if [[ -z "$GN_BIN" ]]; then
        ensure_depot_tools
        if command -v gn &>/dev/null; then
            GN_BIN="$(command -v gn)"
        fi
    fi

    if command -v ninja &>/dev/null; then
        NINJA_BIN="$(command -v ninja)"
    elif [[ -x "$ninja_candidate" ]]; then
        NINJA_BIN="$ninja_candidate"
    fi
    if [[ -n "$NINJA_BIN" ]] && ! "$NINJA_BIN" --version >/dev/null 2>&1; then
        NINJA_BIN=""
    fi
    if [[ -z "$NINJA_BIN" ]]; then
        ensure_depot_tools
        if command -v ninja &>/dev/null; then
            NINJA_BIN="$(command -v ninja)"
        fi
    fi

    if [[ -z "$GN_BIN" ]]; then
        echo "ERROR: Could not find 'gn'."
        echo "  Tried PATH and: $gn_candidate"
        exit 1
    fi

    if [[ -z "$NINJA_BIN" ]]; then
        echo "ERROR: Could not find 'ninja'."
        echo "  Tried PATH and: $ninja_candidate"
        exit 1
    fi

    log "Using gn: $GN_BIN"
    log "Using ninja: $NINJA_BIN"
}

# ── Validate environment ────────────────────────────────────────────────────

if [[ -z "${WEBRTC_SRC:-}" ]]; then
    WEBRTC_SRC="$DEFAULT_WEBRTC_SRC"
    log "WEBRTC_SRC not set; using default local path: $WEBRTC_SRC"
fi

HOST_OS="$(uname -s)"
if [[ "$HOST_OS" != "Linux" ]]; then
    echo "ERROR: Android WebRTC builds are only supported on Linux (current host: $HOST_OS)."
    echo "  Use Docker wrapper for reproducible local builds:"
    echo "    $MODULE_DIR/native/build_webrtc_docker.sh"
    exit 1
fi

if [[ ! -f "$WEBRTC_SRC/modules/audio_processing/include/audio_processing.h" ]]; then
    if [[ "$WEBRTC_SRC" == "$DEFAULT_WEBRTC_SRC" ]]; then
        bootstrap_webrtc_checkout
    fi
fi

if [[ ! -f "$WEBRTC_SRC/modules/audio_processing/include/audio_processing.h" ]]; then
    echo "ERROR: Cannot find audio_processing.h in \$WEBRTC_SRC."
    echo "  WEBRTC_SRC=$WEBRTC_SRC"
    exit 1
fi

ensure_linux_gn_in_checkout
ensure_build_tools

if [[ ! -f "$PIN_FILE" && -z "${WEBRTC_REV:-}" ]]; then
    echo "ERROR: Missing pinned revision file: $PIN_FILE"
    echo "  Add a WebRTC commit hash to WEBRTC_REVISION, or set WEBRTC_REV explicitly."
    exit 1
fi

PINNED_REV="${WEBRTC_REV:-}"
if [[ -z "$PINNED_REV" ]]; then
    PINNED_REV="$(grep -v '^[[:space:]]*#' "$PIN_FILE" | tr -d '\r' | awk 'NF { print; exit }')"
fi
if [[ -z "$PINNED_REV" ]]; then
    echo "ERROR: No revision found in $PIN_FILE"
    echo "  Put a commit hash/tag on the first non-comment line."
    exit 1
fi

CURRENT_REV="$(git -C "$WEBRTC_SRC" rev-parse HEAD 2>/dev/null || true)"
if [[ -z "$CURRENT_REV" ]]; then
    echo "ERROR: WEBRTC_SRC is not a git checkout: $WEBRTC_SRC"
    exit 1
fi

RESOLVED_PINNED_REV="$(git -C "$WEBRTC_SRC" rev-parse "$PINNED_REV^{commit}" 2>/dev/null || true)"
if [[ -z "$RESOLVED_PINNED_REV" ]]; then
    log "Pinned revision not present locally; fetching git refs"
    git -C "$WEBRTC_SRC" fetch --tags origin
    RESOLVED_PINNED_REV="$(git -C "$WEBRTC_SRC" rev-parse "$PINNED_REV^{commit}" 2>/dev/null || true)"
    if [[ -z "$RESOLVED_PINNED_REV" ]]; then
        echo "ERROR: Pinned revision '$PINNED_REV' is not valid in $WEBRTC_SRC"
        exit 1
    fi
fi

if [[ "$CURRENT_REV" != "$RESOLVED_PINNED_REV" ]]; then
    log "Revision mismatch; checking out pinned revision"
    git -C "$WEBRTC_SRC" checkout "$RESOLVED_PINNED_REV"
    if [[ "$WEBRTC_SRC" == "$DEFAULT_WEBRTC_SRC" ]]; then
        ensure_depot_tools
        log "Running gclient sync after checkout"
        (
            cd "$DEFAULT_WEBRTC_ROOT"
            gclient sync
        )
    fi
fi

echo "Using pinned WebRTC revision: $RESOLVED_PINNED_REV"

# ── Copy JNI source into WebRTC tree ────────────────────────────────────────

JNI_DIR="$WEBRTC_SRC/apm_jni"
mkdir -p "$JNI_DIR"
cp "$SCRIPT_DIR/apm_jni.cpp"      "$JNI_DIR/"
cp "$SCRIPT_DIR/BUILD.gn"         "$JNI_DIR/"
cp "$SCRIPT_DIR/jni_exports.lds"  "$JNI_DIR/"

echo "Copied JNI sources to $JNI_DIR"
ensure_apm_dependency_in_root_build

mkdir -p "$LIBS_DIR"
cat > "$LIBS_DIR/BUILD_INFO.txt" <<EOF
webrtc_revision=$RESOLVED_PINNED_REV
webrtc_revision_input=$PINNED_REV
built_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF
if [[ -n "${DOCKER_BUILDER_IMAGE_TAG:-}" ]]; then
    echo "builder_image_tag=$DOCKER_BUILDER_IMAGE_TAG" >> "$LIBS_DIR/BUILD_INFO.txt"
fi
if [[ -n "${DOCKER_BUILDER_IMAGE_ID:-}" ]]; then
    echo "builder_image_id=$DOCKER_BUILDER_IMAGE_ID" >> "$LIBS_DIR/BUILD_INFO.txt"
fi
echo "Wrote build metadata: $LIBS_DIR/BUILD_INFO.txt"

# ── Common GN args ──────────────────────────────────────────────────────────

COMMON_ARGS='
  target_os="android"
  is_debug=false
  is_component_build=false
  rtc_include_tests=false
  treat_warnings_as_errors=false
  rtc_build_examples=false
  rtc_build_tools=false
'

# ── Build function ──────────────────────────────────────────────────────────

build_abi() {
    local cpu="$1"
    local abi="$2"
    local out_dir="$WEBRTC_SRC/out/apm-$cpu"

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  Building for $abi (target_cpu=$cpu)"
    echo "═══════════════════════════════════════════════════════════════"

    (
        cd "$WEBRTC_SRC"
        "$GN_BIN" gen "$out_dir" --args="$COMMON_ARGS target_cpu=\"$cpu\""
    )
    if ! "$NINJA_BIN" -C "$out_dir" webrtc_apms; then
        # Fallback for environments that expose the full label-like target name.
        "$NINJA_BIN" -C "$out_dir" apm_jni:webrtc_apms
    fi

    # Find the built .so
    local so_file="$out_dir/libwebrtc_apms.so"
    if [[ ! -f "$so_file" ]]; then
        # Some builds put it in a subdirectory
        so_file=$(find "$out_dir" -name "libwebrtc_apms.so" -type f | head -1)
    fi

    if [[ -z "$so_file" || ! -f "$so_file" ]]; then
        echo "ERROR: libwebrtc_apms.so not found in $out_dir"
        exit 1
    fi

    # Strip debug symbols to reduce size
    local strip_tool
    strip_tool="$(find "$WEBRTC_SRC/third_party/android_toolchain/ndk/toolchains/llvm/prebuilt" \
        -name "llvm-strip" -type f 2>/dev/null | head -1)"
    if [[ -n "$strip_tool" && -x "$strip_tool" ]]; then
        echo "Stripping $so_file"
        "$strip_tool" "$so_file"
    else
        echo "WARNING: llvm-strip not found, skipping strip"
    fi

    # Copy to module libs
    mkdir -p "$LIBS_DIR/$abi"
    cp "$so_file" "$LIBS_DIR/$abi/libwebrtc_apms.so"

    local size
    size=$(du -h "$LIBS_DIR/$abi/libwebrtc_apms.so" | cut -f1)
    echo "Installed $LIBS_DIR/$abi/libwebrtc_apms.so ($size)"
}

# ── Build all ABIs ──────────────────────────────────────────────────────────

build_abi "arm64" "arm64-v8a"
build_abi "arm"   "armeabi-v7a"
build_abi "x64"   "x86_64"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Build complete! Libraries installed to:"
echo "    $LIBS_DIR/arm64-v8a/libwebrtc_apms.so"
echo "    $LIBS_DIR/armeabi-v7a/libwebrtc_apms.so"
echo "    $LIBS_DIR/x86_64/libwebrtc_apms.so"
echo ""
echo "  Verify with:"
echo "    llvm-readelf --dyn-syms $LIBS_DIR/arm64-v8a/libwebrtc_apms.so | grep -i 'echo\|rnn\|audio_processing'"
echo "═══════════════════════════════════════════════════════════════"
