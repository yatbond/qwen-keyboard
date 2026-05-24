#!/usr/bin/env python3
"""PC ASR server for Qwen Keyboard.

Switchable engines via X-Asr-Engine header:
- qwen_0_6b / qwen_onnx: local Qwen3-ASR-0.6B ONNX CPU/ORT pipeline
- qwen_1_7b: official qwen-asr transformers backend on CUDA
- sensevoice_yue_2025: sherpa-onnx SenseVoice INT8, Cantonese-tuned 2025-09-09
- qwen_flash: DashScope qwen3-asr-flash OpenAI-compatible API
- qwen_flash_filetrans: placeholder for DashScope async file transcription

Security:
Set QWEN_ASR_TOKEN. Android sends it as X-Qwen-Asr-Token.
Set DASHSCOPE_API_KEY for DashScope cloud engines.
"""
import base64
import json
import hashlib
import mimetypes
import os
import sys
import re
import tempfile
import time
import wave
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, Any, Tuple, List

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = Path(os.environ.get("QWEN_ASR_MODEL_ROOT", ROOT / "models" / "qwen3-asr-0.6b-onnx-cpu")).expanduser().resolve()
ONNX_DIR = MODEL_ROOT / "onnx_models"
SENSEVOICE_2025_DIR = Path(os.environ.get(
    "SENSEVOICE_2025_MODEL_DIR",
    ROOT / "models" / "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
)).expanduser().resolve()
TOKEN = os.environ.get("QWEN_ASR_TOKEN", "")

LEARNING_UPLOAD_DIR = Path(os.environ.get(
    "QWEN_KEYBOARD_LEARNING_UPLOAD_DIR",
    ROOT / "output" / "learning-uploads",
)).expanduser().resolve()
DEFAULT_ENGINE = os.environ.get("QWEN_ASR_ENGINE", "qwen_0_6b")
HF_HOME = os.environ.get("HF_HOME", str(Path(os.environ.get("QWEN_ASR_CACHE", "/tmp/qwen-keyboard-model-cache")) / "hf"))
os.environ.setdefault("HF_HOME", HF_HOME)

app = FastAPI(title="Qwen Keyboard PC ASR Server")
_engines: Dict[str, Any] = {}

QWEN_06_ALIASES = {"qwen_0_6b", "qwen_06b", "qwen_0.6b", "qwen_onnx"}
QWEN_17_ALIASES = {"qwen_1_7b", "qwen_17b", "qwen_1.7b"}
SENSEVOICE_2025_ALIASES = {
    "sensevoice_yue_2025",
    "sensevoice_2025",
    "sense_voice_yue_2025",
    "sensevoice_yue_int8",
    "sensevoice_yue",
}
DASHSCOPE_FLASH_ALIASES = {"qwen_flash", "qwen3_asr_flash", "qwen3-asr-flash"}
DASHSCOPE_FILETRANS_ALIASES = {"qwen_flash_filetrans", "qwen3_asr_flash_filetrans", "qwen3-asr-flash-filetrans"}
DOUBAO_ASR_ALIASES = {"doubao_asr", "doubao", "seed_asr", "seed_asr_2", "volc_seed_asr"}
ENGINE_NAMES = sorted(QWEN_06_ALIASES | QWEN_17_ALIASES | SENSEVOICE_2025_ALIASES | DASHSCOPE_FLASH_ALIASES | DASHSCOPE_FILETRANS_ALIASES | DOUBAO_ASR_ALIASES)
TEXT_FIX_MODELS = {"rules", "dashscope_qwen", "qwen_text", "pc_qwen_text"}


def normalize_engine(name: str) -> str:
    name = (name or DEFAULT_ENGINE).strip().lower().replace("-", "_")
    aliases = {}
    return aliases.get(name, name)


def require_auth(request: Request):
    if TOKEN and request.headers.get("x-qwen-asr-token") != TOKEN:
        raise PermissionError("bad or missing X-Qwen-Asr-Token")


def cuda_device() -> Tuple[str, str]:
    # Keep CPU fallback explicit. For RTX 5090, default to CUDA float16.
    if os.environ.get("QWEN_ASR_FORCE_CPU", "").lower() in ("1", "true", "yes"):
        return "cpu", "int8"
    return "cuda", "float16"


def get_engine(name: str):
    name = normalize_engine(name)
    if name in _engines:
        return _engines[name]

    if name in QWEN_06_ALIASES:
        sys.path.insert(0, str(MODEL_ROOT))
        from onnx_inference import OnnxAsrPipeline
        _engines[name] = OnnxAsrPipeline(onnx_dir=str(ONNX_DIR), num_threads=0, quantize="int8")
        return _engines[name]


    if name in QWEN_17_ALIASES:
        import torch
        from qwen_asr import Qwen3ASRModel
        model_name = os.environ.get("QWEN_ASR_17_MODEL", "Qwen/Qwen3-ASR-1.7B")
        _engines[name] = Qwen3ASRModel.from_pretrained(
            model_name,
            dtype=torch.bfloat16,
            device_map="cuda:0",
            max_inference_batch_size=4,
            max_new_tokens=256,
        )
        return _engines[name]

    if name in SENSEVOICE_2025_ALIASES:
        return get_sensevoice_2025_engine(name, os.environ.get("SENSEVOICE_LANGUAGE", "yue"))

    if name in DASHSCOPE_FLASH_ALIASES:
        _engines[name] = {"kind": "dashscope_flash"}
        return _engines[name]

    if name in DASHSCOPE_FILETRANS_ALIASES:
        _engines[name] = {"kind": "dashscope_filetrans"}
        return _engines[name]

    if name in DOUBAO_ASR_ALIASES:
        _engines[name] = {"kind": "doubao_asr"}
        return _engines[name]

    raise ValueError(f"unknown engine: {name}. Available: {', '.join(ENGINE_NAMES)}")


def run_qwen_onnx(engine, path: str, engine_name: str, language: str):
    t0 = time.time()
    result = engine.transcribe(path, language=(language or None), max_new_tokens=128, chunk_sec=30)
    elapsed = time.time() - t0
    return {
        "ok": True,
        "engine": engine_name,
        "text": result.get("text", ""),
        "raw_output": result.get("raw_output", ""),
        "language": result.get("language", language),
        "timing": result.get("timing", {}),
        "server_elapsed_s": elapsed,
    }


def run_qwen_17(engine, path: str, engine_name: str, language: str):
    t0 = time.time()
    results = engine.transcribe(audio=path, language=(language or None))
    elapsed = time.time() - t0
    result = results[0] if isinstance(results, list) else results
    return {
        "ok": True,
        "engine": engine_name,
        "text": getattr(result, "text", str(result)),
        "language": getattr(result, "language", language) or "",
        "timing": {},
        "server_elapsed_s": elapsed,
    }


def run_qwen_17_batch(engine, paths: List[str], engine_name: str, language: str):
    t0 = time.time()
    lang = [language or None for _ in paths]
    results = engine.transcribe(audio=paths, language=lang)
    elapsed = time.time() - t0
    out = []
    for result in results:
        out.append({
            "text": getattr(result, "text", str(result)),
            "language": getattr(result, "language", language) or "",
        })
    return out, elapsed


def get_sensevoice_2025_engine(engine_name: str, language: str = ""):
    import sherpa_onnx

    lang = (language or os.environ.get("SENSEVOICE_LANGUAGE", "yue") or "yue").strip().lower()
    if lang not in {"auto", "zh", "en", "ko", "ja", "yue"}:
        lang = "auto"
    key = f"{normalize_engine(engine_name)}:{lang}"
    if key in _engines:
        return _engines[key]
    model = SENSEVOICE_2025_DIR / "model.int8.onnx"
    tokens = SENSEVOICE_2025_DIR / "tokens.txt"
    if not model.is_file() or not tokens.is_file():
        raise FileNotFoundError(
            f"SenseVoice 2025 model not found under {SENSEVOICE_2025_DIR}. "
            "Run scripts/download-sensevoice-yue-2025.sh first."
        )
    _engines[key] = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model),
        tokens=str(tokens),
        language=lang,
        use_itn=False,  # 2025-09-09 Cantonese-tuned model does not support punctuation/ITN.
        num_threads=int(os.environ.get("SENSEVOICE_NUM_THREADS", "2")),
        debug=os.environ.get("SENSEVOICE_DEBUG", "0").lower() in ("1", "true", "yes"),
    )
    return _engines[key]


def run_sensevoice(engine, path: str, engine_name: str, language: str):
    import soundfile as sf

    t0 = time.time()
    audio, sample_rate = sf.read(path, dtype="float32", always_2d=True)
    audio = audio[:, 0]
    stream = engine.create_stream()
    stream.accept_waveform(sample_rate, audio)
    engine.decode_stream(stream)
    elapsed = time.time() - t0
    raw = stream.result
    text = getattr(raw, "text", "")
    lang = language
    try:
        data = json.loads(str(raw))
        text = data.get("text", text)
        lang = (data.get("lang", lang) or lang).replace("<|", "").replace("|>", "")
    except Exception:
        pass
    duration = wav_duration_s(path)
    return {
        "ok": True,
        "engine": engine_name,
        "text": text.strip(),
        "language": lang or "",
        "raw_output": str(raw),
        "timing": {"audio_duration_s": duration, "rtf": elapsed / duration if duration else 0.0},
        "server_elapsed_s": elapsed,
    }


def run_dashscope_flash(path: str, engine_name: str, language: str):
    api_key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not api_key:
        raise RuntimeError("DASHSCOPE_API_KEY is not set on PC server")
    import requests

    t0 = time.time()
    base_url = os.environ.get("DASHSCOPE_COMPAT_BASE_URL", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1").rstrip("/")
    mime = mimetypes.guess_type(path)[0] or "audio/wav"
    data_uri = f"data:{mime};base64," + base64.b64encode(Path(path).read_bytes()).decode("ascii")
    body = {
        "model": os.environ.get("DASHSCOPE_ASR_MODEL", "qwen3-asr-flash"),
        "messages": [{"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": data_uri}}]}],
        "stream": False,
        "asr_options": {"enable_itn": False},
    }
    if language:
        body["asr_options"]["language"] = language
    resp = requests.post(
        f"{base_url}/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        data=json.dumps(body),
        timeout=300,
    )
    resp.raise_for_status()
    data = resp.json()
    msg = data["choices"][0]["message"]
    annotations = msg.get("annotations") or []
    elapsed = time.time() - t0
    return {
        "ok": True,
        "engine": engine_name,
        "text": msg.get("content", ""),
        "language": (annotations[0].get("language") if annotations else language) or "",
        "raw_output": data,
        "timing": {"dashscope_usage": data.get("usage", {})},
        "server_elapsed_s": elapsed,
    }


def run_dashscope_filetrans(path: str, engine_name: str, language: str):
    # qwen3-asr-flash-filetrans is async-only and is meant for long files.
    # It normally needs an OSS/public URL workflow. The phone sends raw bytes here,
    # so keep the switch visible but fail clearly until an upload target is configured.
    if not os.environ.get("DASHSCOPE_API_KEY", ""):
        raise RuntimeError("DASHSCOPE_API_KEY is not set on PC server")
    raise RuntimeError(
        "qwen_flash_filetrans requires DashScope async file transcription with a public/OSS audio URL. "
        "Use qwen_flash for phone short clips; configure OSS upload before using filetrans."
    )


def run_engine(path: str, engine_name: str, language: str = ""):
    engine_name = normalize_engine(engine_name)
    engine = get_sensevoice_2025_engine(engine_name, language) if engine_name in SENSEVOICE_2025_ALIASES else get_engine(engine_name)
    if engine_name in QWEN_06_ALIASES:
        return run_qwen_onnx(engine, path, engine_name, language)
    if engine_name in QWEN_17_ALIASES:
        return run_qwen_17(engine, path, engine_name, language)
    if engine_name in SENSEVOICE_2025_ALIASES:
        return run_sensevoice(engine, path, engine_name, language)
    if engine_name in DASHSCOPE_FLASH_ALIASES:
        return run_dashscope_flash(path, engine_name, language)
    if engine_name in DASHSCOPE_FILETRANS_ALIASES:
        return run_dashscope_filetrans(path, engine_name, language)
    if engine_name in DOUBAO_ASR_ALIASES:
        return run_doubao_asr(path, engine_name, language)
    raise ValueError(f"unsupported engine: {engine_name}")


def run_doubao_asr(path: str, engine_name: str, language: str):
    """VolcEngine Doubao/Seed-ASR recorded-file bigmodel API.

    Required env vars:
    - DOUBAO_STT_APP_ID: X-Api-App-Key
    - DOUBAO_STT_ACCESS_KEY: X-Api-Access-Key
    Optional:
    - DOUBAO_STT_RESOURCE_ID, default volc.seedasr.auc
    """
    import uuid
    import requests

    app_id = os.environ.get("DOUBAO_STT_APP_ID", "")
    access_key = os.environ.get("DOUBAO_STT_ACCESS_KEY", "")
    if not app_id or not access_key:
        raise RuntimeError("DOUBAO_STT_APP_ID / DOUBAO_STT_ACCESS_KEY are not set on PC server")
    resource_id = os.environ.get("DOUBAO_STT_RESOURCE_ID", "volc.seedasr.auc")
    submit_url = os.environ.get("DOUBAO_STT_SUBMIT_URL", "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit")
    query_url = os.environ.get("DOUBAO_STT_QUERY_URL", "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query")
    req_id = str(uuid.uuid4())
    suffix = Path(path).suffix.lower().lstrip(".") or "wav"
    fmt = {"wave": "wav", "m4a": "m4a", "mp4": "m4a"}.get(suffix, suffix)
    if fmt not in {"wav", "mp3", "ogg", "m4a", "flac"}:
        fmt = "wav"
    headers = {
        "Content-Type": "application/json",
        "X-Api-App-Key": app_id,
        "X-Api-Access-Key": access_key,
        "X-Api-Resource-Id": resource_id,
        "X-Api-Request-Id": req_id,
    }
    body = {
        "user": {"uid": os.environ.get("DOUBAO_STT_UID", "dee-keyboard")},
        "audio": {"data": base64.b64encode(Path(path).read_bytes()).decode("ascii"), "format": fmt},
    }
    t0 = time.time()
    r = requests.post(submit_url, headers=headers, data=json.dumps(body), timeout=60)
    r.raise_for_status()
    polls = int(os.environ.get("DOUBAO_STT_MAX_POLLS", "30"))
    interval = float(os.environ.get("DOUBAO_STT_POLL_INTERVAL", "2"))
    last = {}
    for _ in range(polls):
        time.sleep(interval)
        q = requests.post(query_url, headers=headers, data="{}", timeout=30)
        q.raise_for_status()
        try:
            last = q.json() if q.text.strip() else {}
        except Exception:
            last = {}
        text = ((last.get("result") or {}).get("text") or "").strip()
        if text:
            elapsed = time.time() - t0
            return {
                "ok": True,
                "engine": engine_name,
                "text": text,
                "language": language or "auto",
                "raw_output": last,
                "timing": {"audio_duration_s": wav_duration_s(path), "request_id": req_id},
                "server_elapsed_s": elapsed,
            }
    raise RuntimeError(f"Doubao ASR timed out waiting for result; request_id={req_id}; last={str(last)[:300]}")


def wav_duration_s(path: str) -> float:
    with wave.open(path, "rb") as w:
        return w.getnframes() / float(w.getframerate() or 1)


def split_wav(path: str, chunk_sec: float, overlap_sec: float = 0.5) -> List[Tuple[str, float, float]]:
    chunks: List[Tuple[str, float, float]] = []
    with wave.open(path, "rb") as src:
        params = src.getparams()
        rate = src.getframerate()
        total_frames = src.getnframes()
        chunk_frames = max(1, int(chunk_sec * rate))
        overlap_frames = max(0, int(min(overlap_sec, chunk_sec / 4.0) * rate))
        step = max(1, chunk_frames - overlap_frames)
        start = 0
        while start < total_frames:
            end = min(total_frames, start + chunk_frames)
            src.setpos(start)
            frames = src.readframes(end - start)
            tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".wav")
            tmp.close()
            with wave.open(tmp.name, "wb") as dst:
                dst.setparams(params)
                dst.writeframes(frames)
            chunks.append((tmp.name, start / rate, end / rate))
            if end >= total_frames:
                break
            start += step
    return chunks


def merge_chunk_texts(texts: List[str]) -> str:
    merged = ""
    for text in [t.strip() for t in texts if t and t.strip()]:
        if not merged:
            merged = text
            continue
        compact_tail = merged[-80:]
        best = 0
        max_overlap = min(len(compact_tail), len(text), 40)
        for n in range(max_overlap, 3, -1):
            if compact_tail[-n:] == text[:n]:
                best = n
                break
        merged += text[best:] if best else (" " + text)
    return merged.strip()


def run_engine_chunked(path: str, engine_name: str, language: str, chunk_sec: float):
    engine_name = normalize_engine(engine_name)
    duration = wav_duration_s(path)
    # Do not chunk short clips. This protects 3-5s keyboard dictation from overhead/boundary loss.
    if chunk_sec <= 0 or duration <= chunk_sec + 1.0:
        result = run_engine(path, engine_name, language)
        result.setdefault("timing", {})["chunking"] = {"enabled": False, "requested_chunk_sec": chunk_sec, "audio_duration_s": duration}
        return result

    t0 = time.time()
    chunks = split_wav(path, chunk_sec=chunk_sec, overlap_sec=float(os.environ.get("QWEN_ASR_CHUNK_OVERLAP_SEC", "0.5")))
    chunk_paths = [c[0] for c in chunks]
    try:
        texts: List[str] = [""] * len(chunks)
        languages: List[str] = []
        chunk_elapsed: List[float] = [0.0] * len(chunks)
        workers = max(1, int(os.environ.get("QWEN_ASR_CHUNK_WORKERS", "2")))

        if engine_name in QWEN_17_ALIASES:
            engine = get_engine(engine_name)
            batch, elapsed = run_qwen_17_batch(engine, chunk_paths, engine_name, language)
            for i, item in enumerate(batch):
                texts[i] = item.get("text", "")
                if item.get("language"):
                    languages.append(item["language"])
            chunk_elapsed = [elapsed / max(1, len(chunks))] * len(chunks)
        else:
            for i, p in enumerate(chunk_paths):
                st = time.time()
                r = run_engine(p, engine_name, language)
                texts[i] = r.get("text", "")
                if r.get("language"):
                    languages.append(r["language"])
                chunk_elapsed[i] = time.time() - st

        elapsed = time.time() - t0
        text = merge_chunk_texts(texts)
        return {
            "ok": True,
            "engine": engine_name,
            "text": text,
            "language": languages[0] if languages else language,
            "timing": {
                "audio_duration_s": duration,
                "rtf": elapsed / duration if duration else 0.0,
                "chunking": {
                    "enabled": True,
                    "requested_chunk_sec": chunk_sec,
                    "chunk_count": len(chunks),
                    "workers": "batch" if engine_name in QWEN_17_ALIASES else 1,
                    "overlap_sec": float(os.environ.get("QWEN_ASR_CHUNK_OVERLAP_SEC", "0.5")),
                    "chunks": [
                        {"index": i, "start_s": s, "end_s": e, "elapsed_s": chunk_elapsed[i]}
                        for i, (_p, s, e) in enumerate(chunks)
                    ],
                },
            },
            "server_elapsed_s": elapsed,
        }
    finally:
        for p in chunk_paths:
            try:
                os.unlink(p)
            except OSError:
                pass


@app.get("/health")
def health():
    return {
        "ok": True,
        "default_engine": DEFAULT_ENGINE,
        "loaded_engines": sorted(_engines.keys()),
        "available_engines": ENGINE_NAMES,
        "auth": bool(TOKEN),
        "dashscope": bool(os.environ.get("DASHSCOPE_API_KEY", "")),
        "hf_home": HF_HOME,
    }


@app.get("/engines")
def engines():
    return {"ok": True, "engines": ENGINE_NAMES, "loaded_engines": sorted(_engines.keys())}


def rule_fix_text(text: str) -> str:
    out = (text or "").strip()
    out = re.sub(r"\s+", " ", out)
    out = re.sub(r"\s+([,.?!:;，。？！：；])", r"\1", out)
    out = re.sub(r"([,.?!:;，。？！：；])([^\s,.?!:;，。？！：；])", r"\1 \2", out)
    # Conservative English typo fixes and product/capitalization fixes.
    replacements = {
        "q wen": "Qwen", "qwen": "Qwen", "sense voice": "SenseVoice", "sensevoice": "SenseVoice",
        "open claw": "OpenClaw", "openclaw": "OpenClaw", "cloud flare": "Cloudflare", "cloudflare": "Cloudflare",
        "whats app": "WhatsApp", "whatsapp": "WhatsApp", "telegram": "Telegram", "android": "Android",
        "teh": "the", "adn": "and", "dont": "don't", "cant": "can't", "wont": "won't", "im": "I'm",
    }
    for wrong, correct in replacements.items():
        out = re.sub(rf"(?i)\b{re.escape(wrong)}\b", correct, out)
    return out


def dashscope_fix_text(text: str, language: str = "auto") -> Tuple[str, str, float]:
    api_key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not api_key:
        raise RuntimeError("DASHSCOPE_API_KEY is not set on PC server; use Rules or set the key to enable PC Qwen Text Fix")
    import requests

    t0 = time.time()
    base_url = os.environ.get("DASHSCOPE_COMPAT_BASE_URL", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1").rstrip("/")
    model = os.environ.get("DASHSCOPE_TEXT_MODEL", "qwen-plus")
    prompt = (
        "Correct this voice dictation text. Preserve the user's meaning and language. "
        "Fix obvious ASR mistakes, Cantonese/Chinese wording, punctuation, spacing, and capitalization. "
        "Do not explain. Return only the corrected text.\n\n"
        f"Text: {text}"
    )
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a concise voice dictation correction engine. Return only corrected text."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.1,
        "stream": False,
    }
    resp = requests.post(
        f"{base_url}/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        data=json.dumps(body),
        timeout=90,
    )
    resp.raise_for_status()
    data = resp.json()
    fixed = data["choices"][0]["message"].get("content", "").strip()
    return fixed or text, model, time.time() - t0




def scrub_learning_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Keep Dee's full private upload locally, but normalize obvious metadata.

    This endpoint is explicitly opt-in from the phone. We still avoid trusting
    arbitrary clients by bounding body size and storing append-only JSON files
    under the project output folder for later curation into bundled assets.
    """
    if not isinstance(payload, dict):
        raise ValueError("learning upload must be a JSON object")
    out = dict(payload)
    out["server_received_ts"] = int(time.time() * 1000)
    return out


@app.post("/upload-learning")
async def upload_learning(request: Request):
    try:
        require_auth(request)
        raw = await request.body()
        max_bytes = int(os.environ.get("QWEN_KEYBOARD_LEARNING_MAX_BYTES", str(2 * 1024 * 1024)))
        if len(raw) > max_bytes:
            return JSONResponse({"ok": False, "error": f"upload too large: {len(raw)} bytes > {max_bytes}"}, status_code=413)
        payload = scrub_learning_payload(json.loads(raw.decode("utf-8")))
        LEARNING_UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(raw).hexdigest()[:16]
        ts = time.strftime("%Y%m%d-%H%M%S")
        target = LEARNING_UPLOAD_DIR / f"learning-{ts}-{digest}.json"
        target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        # Also maintain a latest file, making it easy for future build tooling to consume.
        latest = LEARNING_UPLOAD_DIR / "latest-learning-upload.json"
        latest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        preview_lines = str(payload.get("preview_corrections_jsonl") or "").count("\n")
        prefs = payload.get("prefs") if isinstance(payload.get("prefs"), dict) else {}
        return JSONResponse({
            "ok": True,
            "saved": str(target),
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "preview_lines": preview_lines,
            "prefs_keys": sorted(prefs.keys()),
        })
    except PermissionError as e:
        return JSONResponse({"ok": False, "error": str(e)}, status_code=401)
    except Exception as e:
        return JSONResponse({"ok": False, "error": f"{type(e).__name__}: {e}"}, status_code=500)


@app.post("/fix-text")
async def fix_text(request: Request):
    try:
        require_auth(request)
        body = await request.json()
        text = str(body.get("text") or "")
        requested_model = str(body.get("model") or "rules").strip().lower()
        language = str(body.get("language") or "auto")
        if requested_model in {"qwen_1_7b", "pc_qwen", "qwen"}:
            requested_model = "dashscope_qwen"
        if requested_model not in TEXT_FIX_MODELS:
            requested_model = "rules"
        t0 = time.time()
        if requested_model == "rules":
            fixed = rule_fix_text(text)
            actual_model = "rules"
            elapsed = time.time() - t0
        else:
            fixed, actual_model, elapsed = dashscope_fix_text(text, language)
        return JSONResponse({
            "ok": True,
            "text": fixed,
            "model": actual_model,
            "requested_model": requested_model,
            "changed": fixed.strip() != text.strip(),
            "server_elapsed_s": elapsed,
        })
    except Exception as e:
        return JSONResponse({"ok": False, "error": f"{type(e).__name__}: {e}"}, status_code=500)


@app.post("/transcribe-raw")
async def transcribe_raw(request: Request):
    try:
        require_auth(request)
        engine = request.headers.get("x-asr-engine") or DEFAULT_ENGINE
        language = request.headers.get("x-asr-language") or ""
        chunk_sec = float(request.headers.get("x-asr-chunk-sec") or "0")
        with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as f:
            path = f.name
            f.write(await request.body())
        try:
            if chunk_sec > 0:
                return JSONResponse(run_engine_chunked(path, engine, language, chunk_sec))
            return JSONResponse(run_engine(path, engine, language))
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass
    except Exception as e:
        return JSONResponse({"ok": False, "error": f"{type(e).__name__}: {e}"}, status_code=500)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8765)
