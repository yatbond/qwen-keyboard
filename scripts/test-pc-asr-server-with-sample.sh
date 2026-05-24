#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${1:-http://127.0.0.1:8765}"
ENGINE="${2:-qwen_onnx}"
WAV="${3:-$ROOT/models/qwen3-asr-0.6b-onnx-cpu/test_audio/librispeech_0_1089_0.wav}"
TOKEN="${QWEN_ASR_TOKEN:-}"
headers=(-H "Content-Type: audio/wav" -H "X-Asr-Engine: $ENGINE")
if [[ -n "$TOKEN" ]]; then headers+=(-H "X-Qwen-Asr-Token: $TOKEN"); fi
curl -fsS -X POST "${URL%/}/transcribe-raw" "${headers[@]}" --data-binary "@$WAV" | python3 -m json.tool
