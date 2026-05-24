#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR" "$ROOT/Log"
cat > "$UNIT_DIR/qwen-keyboard-pc-asr.service" <<EOF
[Unit]
Description=Qwen Keyboard PC ASR server
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$ROOT
ExecStart=/usr/bin/env bash -lc 'exec bash "$ROOT/scripts/run-pc-asr-service.sh"'
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now qwen-keyboard-pc-asr.service
systemctl --user status qwen-keyboard-pc-asr.service --no-pager -l
