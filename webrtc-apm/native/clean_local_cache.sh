#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
LOCAL_DIR="$MODULE_DIR/.local"

if [[ "$LOCAL_DIR" == "/" || -z "$LOCAL_DIR" ]]; then
    echo "ERROR: Refusing to remove unsafe path: $LOCAL_DIR"
    exit 1
fi

echo "[clean_local_cache] Removing $LOCAL_DIR"
rm -rf "$LOCAL_DIR"
echo "[clean_local_cache] Done"
