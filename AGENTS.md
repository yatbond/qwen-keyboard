# Qwen Keyboard Group Workspace

This workspace belongs to Dee's Telegram group/project: **Qwen Keyboard**.

## Telegram
- Group ID: `-5138898227`
- Reply mode: respond without requiring @mention.
- OpenClaw agent id: `qwen-keyboard`
- Model: `openai-codex/gpt-5.5` (Codex GPT-5.5)

## Purpose
- Research and build a Qwen-powered Android voice keyboard / IME.
- Explore local Qwen3-ASR on Android, especially Xiaomi 15 Ultra.
- Keep all generated code, APKs, notes, benchmark outputs, and artifacts inside this project folder unless Dee says otherwise.

## Files and Outputs
- Project root: `/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard`
- Memory: `/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard/memory/`
- Scripts: `/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard/scripts/`
- Output: `/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard/output/`
- Generated files should be saved under this folder.

## Telegram Direct Send
When Dee asks to send generated files to the Qwen Keyboard group, use:

```bash
bash "/mnt/g/My Drive/Ai Projects/2026-05-18 Qwen Keyboard/scripts/send-to-group.sh" <file> [caption]
```

Rules:
- The script reads `channels.telegram.botToken` from `~/.openclaw/openclaw.json`.
- Never print or expose the bot token.
- Prefer direct send for generated APKs, videos, large files, or when Dee explicitly asks.

## Privacy
Dee's private files and project materials stay private. Do not share outside the intended Telegram group unless Dee explicitly approves.
