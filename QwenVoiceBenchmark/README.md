# Qwen Voice Benchmark

Android-local benchmark harness for testing whether Xiaomi 15 Ultra can run Qwen3-ASR-0.6B in/near real time.

Current v0.1:
- records microphone audio as 16 kHz mono PCM/WAV
- runs through a pluggable `LocalAsrEngine`
- uses `StubAsrEngine` for now so the APK/build path can be verified before adding ONNX/MNN/QNN
- reports recording length, processing time, and realtime factor

Next ASR engine targets:
1. ONNX Runtime Mobile + Qwen3-ASR-0.6B INT8/CPU
2. MNN model/runtime if ONNX is too slow
3. Qualcomm QNN/NPU path after baseline works
