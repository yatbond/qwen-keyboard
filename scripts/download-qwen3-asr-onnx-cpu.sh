#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/models/qwen3-asr-0.6b-onnx-cpu"
REPO="Daumee/Qwen3-ASR-0.6B-ONNX-CPU"
mkdir -p "$DEST"

if command -v hf >/dev/null 2>&1; then
  hf download "$REPO" \
    --local-dir "$DEST"
elif command -v huggingface-cli >/dev/null 2>&1; then
  huggingface-cli download "$REPO" \
    --local-dir "$DEST"
else
  python3 -m pip install --user -U huggingface_hub
  python3 -m huggingface_hub.commands.huggingface_cli download "$REPO" \
    --local-dir "$DEST"
fi

echo "Downloaded to: $DEST"
find "$DEST" -maxdepth 3 -type f | sort
