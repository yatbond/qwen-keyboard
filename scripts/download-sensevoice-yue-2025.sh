#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL_DIR="${SENSEVOICE_2025_MODEL_DIR:-$ROOT/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
ARCHIVE="${MODEL_DIR}.tar.bz2"

mkdir -p "$(dirname "$MODEL_DIR")"
if [[ -f "$MODEL_DIR/model.int8.onnx" && -f "$MODEL_DIR/tokens.txt" ]]; then
  echo "SenseVoice 2025 model already present: $MODEL_DIR"
  exit 0
fi

echo "Downloading SenseVoice Cantonese-tuned 2025 INT8 model (~158 MB archive)…"
python3 - <<PY
from pathlib import Path
import urllib.request
url = "$URL"
out = Path("$ARCHIVE")
with urllib.request.urlopen(url) as r, out.open("wb") as f:
    total = int(r.headers.get("content-length") or 0)
    done = 0
    while True:
        chunk = r.read(1024 * 1024)
        if not chunk:
            break
        f.write(chunk)
        done += len(chunk)
        if total:
            print(f"\r{done/1024/1024:.1f}/{total/1024/1024:.1f} MiB", end="", flush=True)
    print()
PY

# Google Drive/WSL may reject utime/chown metadata from tar; --touch avoids
# false failures while preserving the actual model contents.
tar --touch --no-same-owner -xjf "$ARCHIVE" -C "$(dirname "$MODEL_DIR")"
rm -f "$ARCHIVE"

if [[ ! -f "$MODEL_DIR/model.int8.onnx" || ! -f "$MODEL_DIR/tokens.txt" ]]; then
  echo "ERROR: extracted model files not found under $MODEL_DIR" >&2
  exit 1
fi

echo "Ready: $MODEL_DIR"
