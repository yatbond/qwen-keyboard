#!/usr/bin/env bash
set -euo pipefail
URL="${1:-http://127.0.0.1:8765}"
TOKEN="${QWEN_ASR_TOKEN:-}"
if [[ -n "$TOKEN" ]]; then
  curl -fsS -H "X-Qwen-Asr-Token: $TOKEN" "$URL/health"; echo
else
  curl -fsS "$URL/health"; echo
fi
