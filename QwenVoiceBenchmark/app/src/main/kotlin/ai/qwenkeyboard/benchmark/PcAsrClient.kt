package ai.qwenkeyboard.benchmark

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PcAsrClient(
    private val baseUrl: String,
    private val token: String,
    private val engine: String,
    private val chunkSec: Int,
) {
    fun transcribe(wavFile: File): PcAsrResult {
        val url = baseUrl.trim().trimEnd('/') + "/transcribe-raw"
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 10 * 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "audio/wav")
        conn.setRequestProperty("X-Filename", wavFile.name)
        conn.setRequestProperty("X-Asr-Engine", engine.trim())
        conn.setRequestProperty("X-Asr-Chunk-Sec", chunkSec.toString())
        if (token.isNotBlank()) conn.setRequestProperty("X-Qwen-Asr-Token", token.trim())
        wavFile.inputStream().use { input -> conn.outputStream.use { output -> input.copyTo(output) } }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw RuntimeException("PC ASR HTTP $code from $url\n${body.take(500)}")
        }
        if (!body.trimStart().startsWith("{")) {
            throw RuntimeException("PC ASR returned non-JSON response from $url\n${body.take(500)}")
        }
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", body))
        val timing = json.optJSONObject("timing")
        val chunking = timing?.optJSONObject("chunking")
        return PcAsrResult(
            text = json.optString("text"),
            language = json.optString("language"),
            engine = json.optString("engine", engine),
            serverElapsedMs = (json.optDouble("server_elapsed_s", 0.0) * 1000).toLong(),
            rtf = timing?.optDouble("rtf", 0.0) ?: 0.0,
            chunkSec = chunking?.optDouble("requested_chunk_sec", chunkSec.toDouble())?.toInt() ?: chunkSec,
            chunkCount = chunking?.optInt("chunk_count", 0) ?: 0,
            chunkingEnabled = chunking?.optBoolean("enabled", false) ?: false,
        )
    }

    fun fixText(text: String, model: String, language: String = "auto"): PcTextFixResult {
        val url = baseUrl.trim().trimEnd('/') + "/fix-text"
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token.isNotBlank()) conn.setRequestProperty("X-Qwen-Asr-Token", token.trim())
        val bodyJson = JSONObject()
            .put("text", text)
            .put("model", model)
            .put("language", language)
        conn.outputStream.use { it.write(bodyJson.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw RuntimeException("PC Fix HTTP $code from $url\n${body.take(500)}")
        if (!body.trimStart().startsWith("{")) throw RuntimeException("PC Fix returned non-JSON response from $url\n${body.take(500)}")
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", body))
        return PcTextFixResult(
            text = json.optString("text"),
            model = json.optString("model", model),
            changed = json.optBoolean("changed", false),
            serverElapsedMs = (json.optDouble("server_elapsed_s", 0.0) * 1000).toLong(),
        )
    }

    fun uploadLearning(payload: JSONObject): PcLearningUploadResult {
        val url = baseUrl.trim().trimEnd('/') + "/upload-learning"
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token.isNotBlank()) conn.setRequestProperty("X-Qwen-Asr-Token", token.trim())
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw RuntimeException("Learning upload HTTP $code from $url\n${body.take(500)}")
        if (!body.trimStart().startsWith("{")) throw RuntimeException("Learning upload returned non-JSON response from $url\n${body.take(500)}")
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", body))
        return PcLearningUploadResult(
            saved = json.optString("saved"),
            bytes = json.optLong("bytes", 0L),
            previewLines = json.optInt("preview_lines", 0),
        )
    }

}

data class PcLearningUploadResult(
    val saved: String,
    val bytes: Long,
    val previewLines: Int,
)

data class PcTextFixResult(
    val text: String,
    val model: String,
    val changed: Boolean,
    val serverElapsedMs: Long,
)

data class PcAsrResult(
    val text: String,
    val language: String,
    val engine: String,
    val serverElapsedMs: Long,
    val rtf: Double,
    val chunkSec: Int,
    val chunkCount: Int,
    val chunkingEnabled: Boolean,
)
