# PC / Cloudflare ASR Backend

Goal: let the Android benchmark app compare:

- local phone Qwen ONNX inference
- PC inference on Dee-Home / RTX 5090
- different PC engines such as Qwen3-ASR ONNX and SenseVoice Cantonese-tuned 2025 INT8

## Files

- Android APK output: `output/qwen-voice-benchmark-v1.1.0-cloud-pc-engines-debug.apk`
- PC server: `scripts/pc-asr-server.py`
- Start server: `scripts/start-pc-asr-server.sh`
- Start Cloudflare tunnel: `scripts/start-cloudflare-qwen-asr-tunnel.sh`
- Health check: `scripts/check-pc-asr-server.sh`
- Sample test: `scripts/test-pc-asr-server-with-sample.sh`
- Token generator: `scripts/generate-qwen-asr-token.sh`

## Engines

The server currently supports:

- `sensevoice_yue_2025` — sherpa-onnx SenseVoice INT8 2025-09-09, fine-tuned for Cantonese; no punctuation/ITN
- `qwen_onnx` / `qwen_0_6b` — workspace Qwen3-ASR ONNX model
- `qwen_1_7b` — official Qwen3-ASR 1.7B CUDA backend

## Local PC test

From WSL in the project folder:

```bash
cd "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard"
export QWEN_ASR_TOKEN="$(bash scripts/generate-qwen-asr-token.sh)"
bash scripts/download-sensevoice-yue-2025.sh
export QWEN_ASR_ENGINE="sensevoice_yue_2025"
bash scripts/start-pc-asr-server.sh
```

In a second terminal:

```bash
cd "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard"
export QWEN_ASR_TOKEN="same-token-as-server"
bash scripts/check-pc-asr-server.sh
bash scripts/test-pc-asr-server-with-sample.sh http://127.0.0.1:8765 sensevoice_yue_2025
bash scripts/test-pc-asr-server-with-sample.sh http://127.0.0.1:8765 qwen_onnx
```

## Phone over USB test, no Cloudflare

```bash
bash scripts/connect-phone-to-pc-asr.sh
```

Then in the app:

- URL: `http://127.0.0.1:8765`
- Engine: `sensevoice_yue_2025`, `qwen_onnx`, or `qwen_1_7b`
- Token: same `QWEN_ASR_TOKEN`

## Cloudflare mobile-data setup

Later, after Dee provides the Cloudflare hostname/domain:

```bash
cd "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard"
export QWEN_ASR_TOKEN="long-random-token"
export QWEN_ASR_ENGINE="sensevoice_yue_2025"
bash scripts/start-pc-asr-server.sh
```

Second terminal:

```bash
cd "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard"
export QWEN_ASR_TOKEN="same-long-random-token"
export QWEN_ASR_HOSTNAME="qwen-asr.example.com"
bash scripts/start-cloudflare-qwen-asr-tunnel.sh
```

Then in the app:

- URL: `https://qwen-asr.example.com`
- Engine: `sensevoice_yue_2025`, `qwen_onnx`, or `qwen_1_7b`
- Token: same token

## Security note

Do not expose this server publicly without `QWEN_ASR_TOKEN`. The endpoint accepts audio uploads and runs inference on the PC.

Cloudflare Access can be added later, but the app-level token is already implemented via the `X-Qwen-Asr-Token` HTTP header.
