#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models/qwen3-asr-0.6b-onnx-cpu"
PKG="ai.qwenkeyboard.benchmark"
DST="/sdcard/Android/data/$PKG/files/models/qwen3-asr-0.6b-onnx-cpu"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1 && [[ -x "/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]]; then
  ADB="/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe"
fi

if [[ ! -d "$SRC/onnx_models" ]]; then
  echo "Model not found at $SRC"
  echo "Run: bash scripts/download-qwen3-asr-onnx-cpu.sh"
  exit 1
fi

adb_src() {
  if [[ "$ADB" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

"$ADB" devices
"$ADB" shell "mkdir -p '$DST/onnx_models'"

files=(
  "onnx_models/encoder_conv.onnx"
  "onnx_models/encoder_conv.onnx.data"
  "onnx_models/encoder_transformer.onnx"
  "onnx_models/encoder_transformer.onnx.data"
  "onnx_models/decoder_init.int8.onnx"
  "onnx_models/decoder_step.int8.onnx"
  "onnx_models/embed_tokens.bin"
  "tokenizer.json"
)

for rel in "${files[@]}"; do
  src_file="$SRC/$rel"
  if [[ ! -f "$src_file" ]]; then
    echo "Missing local file: $src_file" >&2
    exit 1
  fi
  remote_dir="$DST/$(dirname "$rel")"
  [[ "$remote_dir" == "$DST/." ]] && remote_dir="$DST"
  echo "Pushing $rel ..."
  "${ADB}" push "$(adb_src "$src_file")" "$remote_dir/"
done

"$ADB" shell "ls -lh '$DST' '$DST/onnx_models'"
echo "Pushed model to phone: $DST"
