#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN_FILE="${QWEN_ASR_TOKEN_FILE:-$HOME/.config/qwen-keyboard/qwen_asr_token}"
mkdir -p "$(dirname "$TOKEN_FILE")"
if [[ ! -s "$TOKEN_FILE" ]]; then
  if [[ -s /tmp/qwen_asr_token_current ]]; then
    cp /tmp/qwen_asr_token_current "$TOKEN_FILE"
  else
    openssl rand -hex 32 > "$TOKEN_FILE"
  fi
  chmod 600 "$TOKEN_FILE"
fi
cp "$TOKEN_FILE" /tmp/qwen_asr_token_current 2>/dev/null || true
chmod 600 /tmp/qwen_asr_token_current 2>/dev/null || true
export QWEN_ASR_TOKEN="${QWEN_ASR_TOKEN:-$(cat "$TOKEN_FILE" 2>/dev/null || true)}"
export QWEN_ASR_ENGINE="${QWEN_ASR_ENGINE:-sensevoice_yue_2025}"
export QWEN_ASR_MODEL_ROOT="${QWEN_ASR_MODEL_ROOT:-/tmp/qwen3-asr-0.6b-onnx-cpu}"
export QWEN_ASR_VENV="${QWEN_ASR_VENV:-$HOME/.local/share/qwen-keyboard/pc-asr-venv}"
export HF_HUB_DISABLE_XET="${HF_HUB_DISABLE_XET:-1}"
export QWEN_ASR_CHUNK_WORKERS="${QWEN_ASR_CHUNK_WORKERS:-2}"
exec bash "$ROOT/scripts/start-pc-asr-server.sh"
