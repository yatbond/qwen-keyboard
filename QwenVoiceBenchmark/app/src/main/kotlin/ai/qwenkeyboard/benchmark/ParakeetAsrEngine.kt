package ai.qwenkeyboard.benchmark

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** Phone-local NVIDIA Parakeet TDT 0.6B v3 INT8 engine backed by Sherpa-ONNX. */
class ParakeetAsrEngine(
    private val appFilesDir: File,
    private val numThreads: Int = 4,
    private val modelEngineName: String = ParakeetModelManager.ENGINE_TDT_V3,
) : LocalAsrEngine {
    private val recognizer: OfflineRecognizer by lazy { createRecognizer() }

    override fun transcribe(wavFile: File, audioMs: Long): BenchmarkResult {
        val started = System.currentTimeMillis()
        val samples = readMono16kPcm(wavFile)
        Log.i(TAG, "Transcribe start file=${wavFile.name} samples=${samples.size} audioMs=$audioMs threads=$numThreads")
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.replace(Regex("\\s+"), " ").trim()
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "Transcribe done elapsed=${elapsed}ms textLen=${text.length} text=$text")
            return BenchmarkResult(text, audioMs, elapsed)
        } finally {
            stream.release()
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        if (!ParakeetModelManager.isInstalled(appFilesDir, modelEngineName)) {
            val spec = ParakeetModelManager.spec(modelEngineName)
            throw IllegalStateException(
                "${spec.label} model missing. Download ${spec.label}, or copy Parakeet files into ${ParakeetModelManager.modelRoot(appFilesDir, modelEngineName).absolutePath}."
            )
        }
        val started = System.currentTimeMillis()
        val transducerConfig = OfflineTransducerModelConfig.builder()
            .setEncoder(ParakeetModelManager.encoderFile(appFilesDir, modelEngineName).absolutePath)
            .setDecoder(ParakeetModelManager.decoderFile(appFilesDir, modelEngineName).absolutePath)
            .setJoiner(ParakeetModelManager.joinerFile(appFilesDir, modelEngineName).absolutePath)
            .build()
        val modelConfig = OfflineModelConfig.builder()
            .setTransducer(transducerConfig)
            .setTokens(ParakeetModelManager.tokensFile(appFilesDir, modelEngineName).absolutePath)
            .setModelType("nemo_transducer")
            .setNumThreads(numThreads.coerceIn(1, 8))
            .setDebug(false)
            .setProvider("cpu")
            .build()
        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .build()
        val r = OfflineRecognizer(config)
        Log.i(TAG, "Recognizer created in ${System.currentTimeMillis() - started}ms model=${ParakeetModelManager.installedBytes(appFilesDir, modelEngineName)}")
        return r
    }

    private fun readMono16kPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        var dataOffset = 44
        var dataBytes = (bytes.size - dataOffset).coerceAtLeast(0)
        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16
        var i = 12
        while (i <= bytes.size - 8) {
            val id = String(bytes, i, 4)
            val size = le32(bytes, i + 4).coerceAtLeast(0)
            if (id == "fmt " && i + 8 + size <= bytes.size) {
                channels = le16(bytes, i + 10).coerceAtLeast(1)
                sampleRate = le32(bytes, i + 12).coerceAtLeast(1)
                bitsPerSample = le16(bytes, i + 22).coerceAtLeast(8)
            } else if (id == "data") {
                dataOffset = i + 8
                dataBytes = size.coerceAtMost(bytes.size - dataOffset)
                break
            }
            i += 8 + size + (size and 1)
        }
        require(sampleRate == 16000) { "Parakeet path expects 16 kHz WAV, got $sampleRate Hz" }
        require(channels == 1) { "Parakeet path expects mono WAV, got $channels channels" }
        require(bitsPerSample == 16) { "Parakeet path expects 16-bit PCM WAV, got $bitsPerSample-bit" }
        val samples = dataBytes / 2
        val out = FloatArray(samples)
        var p = dataOffset
        for (j in 0 until samples) {
            val lo = bytes[p].toInt() and 0xff
            val hi = bytes[p + 1].toInt()
            out[j] = ((hi shl 8) or lo).toShort() / 32768f
            p += 2
        }
        return out
    }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    companion object { private const val TAG = "DeeParakeet" }
}
