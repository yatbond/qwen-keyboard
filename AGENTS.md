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

## Active Android App Branch

- The active Dee Keyboard / v4.x app is `QwenVoiceBenchmark/`.
- Latest confirmed baseline: `versionName=4.9.4-chinese-punctuation-delete`, `versionCode=242`.
- Do **not** build `QwenVoiceKeyboard/` unless Dee explicitly asks for the old/simple separate keyboard fork.
- For release/debug builds, prefer copying the active app to local Linux `/tmp` first, then copy APK artifacts back to `output/`; Gradle/Kotlin builds can be slow or get killed on the Google Drive `/mnt/g` filesystem.

## Default Coding Tool — Claude Code CLI

- Claude Code CLI is the default coding tool for this Telegram group workspace.
- OpenClaw routing is configured to prefer `agentRuntime.id: "claude-cli"` with primary model `anthropic/claude-opus-4-7`.
- For coding/debugging/refactoring/build tasks, use Claude Code first from this project root. Use other tools/models only as fallback or when Dee explicitly asks.
- `claude-code` skill must remain enabled for this group.
- Keep generated code, logs, and artifacts inside this workspace unless Dee instructs otherwise.

