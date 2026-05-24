package ai.qwenkeyboard.benchmark

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Foreground ADB test harness so Xiaomi/Android does not background-throttle phone-local ASR. */
class DebugAsrTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = TextView(this).apply {
            textSize = 16f
            text = "Running Dee Keyboard ASR debug test…"
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)

        thread(name = "qwen-debug-asr-activity") {
            val started = System.currentTimeMillis()
            val resultFile = File(filesDir, "debug-asr-result.txt")
            try {
                val modelId = intent.getStringExtra("model") ?: SenseVoiceModelManager.ENGINE_NAME
                val wavPath = intent.getStringExtra("wav")
                val wav = if (!wavPath.isNullOrBlank()) File(wavPath) else File(filesDir, "debug-asr-test.wav")
                Log.i(TAG, "START foreground model=$modelId wav=${wav.absolutePath} exists=${wav.exists()} bytes=${wav.length()}")
                require(wav.isFile && wav.length() > 44L) { "Missing test wav: ${wav.absolutePath}" }
                val audioMs = estimateWavMs(wav)
                val engine: LocalAsrEngine = SenseVoiceAsrEngine(filesDir, language = intent.getStringExtra("language") ?: "auto")
                val result = engine.transcribe(wav, audioMs)
                val elapsed = System.currentTimeMillis() - started
                val text = result.transcript.replace(Regex("\\s+"), " ").trim()
                val payload = "OK\nmodel=$modelId\nwav=${wav.name}\naudioMs=$audioMs\nprocessingMs=${result.processingMs}\nwallMs=$elapsed\ntext=$text\n"
                resultFile.writeText(payload)
                Log.i(TAG, payload.replace('\n', ' '))
                runOnUiThread { status.text = payload }
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - started
                val payload = "ERROR\nwallMs=$elapsed\n${t::class.java.name}: ${t.message}\n${t.stackTraceToString()}"
                resultFile.writeText(payload)
                Log.e(TAG, payload, t)
                runOnUiThread { status.text = payload.take(2000) }
            }
        }
    }

    private fun estimateWavMs(file: File): Long {
        val bytes = file.readBytes()
        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16
        var dataBytes = (bytes.size - 44).coerceAtLeast(0)
        var i = 12
        while (i <= bytes.size - 8) {
            val id = String(bytes, i, 4)
            val size = le32(bytes, i + 4).coerceAtLeast(0)
            if (id == "fmt " && i + 8 + size <= bytes.size) {
                channels = le16(bytes, i + 10).coerceAtLeast(1)
                sampleRate = le32(bytes, i + 12).coerceAtLeast(1)
                bitsPerSample = le16(bytes, i + 22).coerceAtLeast(8)
            } else if (id == "data") {
                dataBytes = size.coerceAtMost(bytes.size - i - 8)
                break
            }
            i += 8 + size + (size and 1)
        }
        val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8).coerceAtLeast(1)
        return (dataBytes * 1000L / bytesPerSecond).coerceAtLeast(1L)
    }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    companion object { private const val TAG = "QwenDebugAsr" }
}
