#!/usr/bin/env bash
set -euo pipefail
: "${QWEN_ASR_HOSTNAME:?Set QWEN_ASR_HOSTNAME, e.g. qwen-asr.example.com}"
TUNNEL_NAME="${QWEN_ASR_TUNNEL_NAME:-qwen-keyboard-asr}"
CONFIG_DIR="$HOME/.cloudflared"
CONFIG_FILE="$CONFIG_DIR/$TUNNEL_NAME.yml"
mkdir -p "$CONFIG_DIR"

if [[ ! -f "$CONFIG_DIR/cert.pem" ]]; then
  echo "Cloudflare login is required. A browser URL will be shown; log in and choose the domain for $QWEN_ASR_HOSTNAME." >&2
  cloudflared tunnel login
fi

if ! cloudflared tunnel list 2>/dev/null | grep -q "[[:space:]]$TUNNEL_NAME[[:space:]]"; then
  cloudflared tunnel create "$TUNNEL_NAME"
fi

TUNNEL_ID="$(cloudflared tunnel list | awk -v name="$TUNNEL_NAME" '$2 == name {print $1; exit}')"
if [[ -z "$TUNNEL_ID" ]]; then
  echo "Could not find tunnel id for $TUNNEL_NAME" >&2
  exit 1
fi

CRED_FILE="$CONFIG_DIR/$TUNNEL_ID.json"
cat > "$CONFIG_FILE" <<EOF
tunnel: $TUNNEL_ID
credentials-file: $CRED_FILE

ingress:
  - hostname: $QWEN_ASR_HOSTNAME
    service: http://127.0.0.1:8765
  - service: http_status:404
EOF

cloudflared tunnel route dns "$TUNNEL_NAME" "$QWEN_ASR_HOSTNAME"

UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/qwen-keyboard-cloudflared.service" <<EOF
[Unit]
Description=Qwen Keyboard Cloudflare named tunnel
After=network-online.target qwen-keyboard-pc-asr.service
Wants=qwen-keyboard-pc-asr.service

[Service]
Type=simple
ExecStart=/usr/bin/cloudflared tunnel --config $CONFIG_FILE run
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now qwen-keyboard-cloudflared.service
systemctl --user status qwen-keyboard-cloudflared.service --no-pager -l

echo "Named tunnel ready: https://$QWEN_ASR_HOSTNAME"
