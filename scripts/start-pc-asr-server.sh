#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Keep the venv outside Google Drive/WSL-mounted storage: Python venv creates
# lib64 symlinks that Google Drive can reject with EPERM.
VENV="${QWEN_ASR_VENV:-$HOME/.local/share/qwen-keyboard/pc-asr-venv}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# This venv was originally copied out of /tmp; its activate script may still
# contain the old absolute VIRTUAL_ENV path. Avoid activation and call the venv
# Python directly so we always use the persistent environment.
PYTHON="$VENV/bin/python"
export PATH="$VENV/bin:$PATH"
# Avoid occasional Hugging Face Xet/CAS hangs on large model downloads in WSL.
export HF_HUB_DISABLE_XET="${HF_HUB_DISABLE_XET:-1}"
# Do not reinstall/upgrade dependencies on every service start. That made the
# /tmp venv drift after reboot and can break the Qwen CUDA stack. Run with
# QWEN_ASR_INSTALL_DEPS=1 only when intentionally rebuilding the environment.
if [[ "${QWEN_ASR_INSTALL_DEPS:-0}" == "1" ]]; then
  "$PYTHON" -m pip install --upgrade pip
  "$PYTHON" -m pip install fastapi 'uvicorn[standard]' python-multipart numpy onnxruntime librosa soundfile tokenizers requests qwen-asr sherpa-onnx nvidia-cublas-cu12 nvidia-cudnn-cu12
fi
MODEL_ROOT="${QWEN_ASR_MODEL_ROOT:-$ROOT/models/qwen3-asr-0.6b-onnx-cpu}"
# The bundled ONNX pipeline looks for tokenizer.json inside onnx_models,
# while the model package keeps it at model root. Add a local symlink/copy.
if [[ -f "$MODEL_ROOT/tokenizer.json" && -d "$MODEL_ROOT/onnx_models" && ! -e "$MODEL_ROOT/onnx_models/tokenizer.json" ]]; then
  ln -s ../tokenizer.json "$MODEL_ROOT/onnx_models/tokenizer.json" 2>/dev/null || cp "$MODEL_ROOT/tokenizer.json" "$MODEL_ROOT/onnx_models/tokenizer.json"
fi
if [[ -z "${QWEN_ASR_TOKEN:-}" ]]; then
  echo "WARNING: QWEN_ASR_TOKEN is not set. For Cloudflare/mobile use, set it first." >&2
  echo "Example: export QWEN_ASR_TOKEN='change-me-long-random-token'" >&2
fi
exec "$PYTHON" "$ROOT/scripts/pc-asr-server.py"
