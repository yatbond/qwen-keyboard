#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models/qwen3-asr-0.6b-onnx-cpu"
PKG="ai.qwenkeyboard.benchmark"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1 && [[ -x "/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]]; then
  ADB="/mnt/c/Users/Derrick Pang/AppData/Local/Android/Sdk/platform-tools/adb.exe"
fi

if [[ ! -d "$SRC/onnx_models" ]]; then
  echo "Model not found at $SRC"
  echo "Run: bash scripts/download-qwen3-asr-onnx-cpu.sh"
  exit 1
fi

"$ADB" devices
"$ADB" shell "run-as $PKG mkdir -p files/models/qwen3-asr-0.6b-onnx-cpu/onnx_models"

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
  [[ -f "$src_file" ]] || { echo "Missing local file: $src_file" >&2; exit 1; }
  dst="files/models/qwen3-asr-0.6b-onnx-cpu/$rel"
  echo "Streaming $rel to internal app storage ..."
  # exec-in streams directly into run-as, avoiding a second temporary copy on the phone.
  "$ADB" exec-in run-as "$PKG" sh -c "cat > '$dst'" < "$src_file"
done

"$ADB" shell "run-as $PKG sh -c 'ls -lh files/models/qwen3-asr-0.6b-onnx-cpu files/models/qwen3-asr-0.6b-onnx-cpu/onnx_models'"
echo "Pushed model to internal app storage: /data/user/0/$PKG/files/models/qwen3-asr-0.6b-onnx-cpu"
