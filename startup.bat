@echo off
setlocal
title Qwen Keyboard - Start PC ASR + Cloudflare Tunnel

echo Starting Qwen Keyboard PC ASR backend and Cloudflare tunnel...
echo.

wsl.exe -d Ubuntu -- bash "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard/scripts/start-services.sh"

echo.
echo Done. If health shows ok:true, the keyboard PC ASR is ready.
pause
