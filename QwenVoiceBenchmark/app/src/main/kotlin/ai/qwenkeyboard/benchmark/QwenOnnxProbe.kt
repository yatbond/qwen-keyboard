package ai.qwenkeyboard.benchmark

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

class QwenOnnxProbe(private val appFilesDir: File?) {
    fun probe(): String {
        val root = File(appFilesDir, "models/qwen3-asr-0.6b-onnx-cpu")
        val onnx = File(root, "onnx_models")
        val required = listOf(
            "encoder_conv.onnx",
            "encoder_conv.onnx.data",
            "encoder_transformer.onnx",
            "encoder_transformer.onnx.data",
            "decoder_init.int8.onnx",
            "decoder_step.int8.onnx",
            "embed_tokens.bin",
            "../tokenizer.json"
        )
        val missing = required.map { File(onnx, it).canonicalFile }.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            return buildString {
                appendLine("Model files not ready.")
                appendLine("Expected folder:")
                appendLine(root.absolutePath)
                appendLine()
                appendLine("Missing:")
                missing.forEach { appendLine("• ${it.name}") }
            }
        }

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val started = System.currentTimeMillis()
        return try {
            val conv = env.createSession(File(onnx, "encoder_conv.onnx").absolutePath, opts)
            val transformer = env.createSession(File(onnx, "encoder_transformer.onnx").absolutePath, opts)
            val init = env.createSession(File(onnx, "decoder_init.int8.onnx").absolutePath, opts)
            val step = env.createSession(File(onnx, "decoder_step.int8.onnx").absolutePath, opts)
            val elapsed = System.currentTimeMillis() - started
            val text = buildString {
                appendLine("ONNX MODEL PROBE PASS")
                appendLine("Loaded 4 ONNX sessions in ${elapsed} ms")
                appendLine("Model size on phone: ${humanSize(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })}")
                appendLine()
                appendLine("encoder_conv inputs: ${conv.inputNames}")
                appendLine("encoder_transformer inputs: ${transformer.inputNames}")
                appendLine("decoder_init inputs: ${init.inputNames}")
                appendLine("decoder_step inputs: ${step.inputNames}")
                appendLine()
                appendLine("Next: mel spectrogram + prompt embedding + greedy decoder.")
            }
            conv.close(); transformer.close(); init.close(); step.close()
            text
        } catch (e: Throwable) {
            "ONNX MODEL PROBE FAILED\n\n${e::class.java.simpleName}: ${e.message}"
        }
    }

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return "%.2f %s".format(v, units[i])
    }
}
