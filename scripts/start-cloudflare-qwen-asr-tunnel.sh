#!/usr/bin/env bash
set -euo pipefail
: "${QWEN_ASR_HOSTNAME:?Set QWEN_ASR_HOSTNAME, e.g. qwen-asr.example.com}"
if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared not found. Install/login cloudflared first." >&2
  exit 1
fi
if [[ -z "${QWEN_ASR_TOKEN:-}" ]]; then
  echo "Refusing to start public tunnel without QWEN_ASR_TOKEN." >&2
  exit 1
fi
cloudflared tunnel --url http://127.0.0.1:8765 --hostname "$QWEN_ASR_HOSTNAME"
