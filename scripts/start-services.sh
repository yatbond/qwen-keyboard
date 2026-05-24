#!/usr/bin/env bash
set -euo pipefail
systemctl --user start qwen-keyboard-pc-asr.service qwen-keyboard-cloudflared.service
sleep 2
echo "PC ASR: $(systemctl --user is-active qwen-keyboard-pc-asr.service)"
echo "Tunnel: $(systemctl --user is-active qwen-keyboard-cloudflared.service)"
echo
echo "Health:"
curl --max-time 8 -fsS https://voice.dee-photography.com/health
echo
