#!/usr/bin/env bash
set -euo pipefail
FILE="${1:?Usage: send-to-group.sh <file> [caption]}"
CAPTION="${2:-}"
CHAT_ID="-5138898227"
TOKEN=$(python3 - <<'PY2'
import json, os
p=os.path.expanduser('~/.openclaw/openclaw.json')
d=json.load(open(p))
t=d.get('channels',{}).get('telegram',{}).get('botToken')
if not t:
    raise SystemExit('Telegram botToken not found in OpenClaw config')
print(t)
PY2
)
if [[ ! -f "$FILE" ]]; then
  echo "File not found: $FILE" >&2
  exit 1
fi
mime=$(file --mime-type -b "$FILE" 2>/dev/null || echo application/octet-stream)
case "$mime" in
  image/*) method="sendPhoto"; field="photo" ;;
  video/*) method="sendVideo"; field="video" ;;
  audio/*) method="sendAudio"; field="audio" ;;
  *) method="sendDocument"; field="document" ;;
esac
args=(
  -sS -X POST "https://api.telegram.org/bot${TOKEN}/${method}"
  -F "chat_id=${CHAT_ID}"
  -F "${field}=@${FILE}"
)
if [[ -n "$CAPTION" ]]; then
  args+=( -F "caption=${CAPTION}" )
fi
curl "${args[@]}"
