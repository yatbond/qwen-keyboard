#!/usr/bin/env bash
set -euo pipefail
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1 && [[ -x "/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]]; then
  ADB="/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe"
fi
"$ADB" devices
"$ADB" reverse tcp:8765 tcp:8765
"$ADB" reverse --list | grep 8765 || true
echo "Phone can now reach PC ASR server at http://127.0.0.1:8765"
