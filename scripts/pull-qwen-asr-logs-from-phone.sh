#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="ai.qwenkeyboard.benchmark"
DEST="$ROOT/Log"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1 && [[ -x "/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]]; then
  ADB="/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe"
fi

mkdir -p "$DEST"
"$ADB" devices
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DEST/export-$STAMP"
mkdir -p "$OUT"

echo "Pulling Qwen ASR export package to: $OUT"
if "$ADB" exec-out run-as "$PKG" sh -c "cd files && tar cf - export_package" | tar --no-same-owner --no-same-permissions --touch -xf - -C "$OUT"; then
  if [[ -d "$OUT/export_package" ]]; then
    find "$OUT/export_package" -maxdepth 2 -type f | sort
    echo
    echo "Done. Edit the .txt files under: $OUT/export_package"
  else
    echo "No export_package found. In the app, tap: Prepare Export for PC" >&2
    exit 1
  fi
else
  echo "Pull failed. Make sure the app is installed and you tapped Prepare Export for PC." >&2
  exit 1
fi
