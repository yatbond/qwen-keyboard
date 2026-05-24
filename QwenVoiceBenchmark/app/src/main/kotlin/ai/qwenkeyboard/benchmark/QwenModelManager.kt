package ai.qwenkeyboard.benchmark

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object QwenModelManager {
    private const val REPO = "Daumee/Qwen3-ASR-0.6B-ONNX-CPU"
    private const val BASE_URL = "https://huggingface.co/$REPO/resolve/main"

    data class ModelFile(val path: String, val minBytes: Long)

    val requiredFiles = listOf(
        ModelFile("tokenizer.json", 10L * 1024L * 1024L),
        // These ONNX graph files are intentionally tiny; the tensor payloads live in .data files.
        ModelFile("onnx_models/encoder_conv.onnx", 4L * 1024L),
        ModelFile("onnx_models/encoder_conv.onnx.data", 40L * 1024L * 1024L),
        ModelFile("onnx_models/encoder_transformer.onnx", 100L * 1024L),
        ModelFile("onnx_models/encoder_transformer.onnx.data", 650L * 1024L * 1024L),
        ModelFile("onnx_models/decoder_init.int8.onnx", 550L * 1024L * 1024L),
        ModelFile("onnx_models/decoder_step.int8.onnx", 550L * 1024L * 1024L),
        ModelFile("onnx_models/embed_tokens.bin", 580L * 1024L * 1024L),
    )

    fun modelRoot(appFilesDir: File): File = File(appFilesDir, "models/qwen3-asr-0.6b-onnx-cpu")

    fun isInstalled(appFilesDir: File): Boolean {
        val root = modelRoot(appFilesDir)
        return requiredFiles.all { File(root, it.path).let { f -> f.isFile && f.length() >= it.minBytes } }
    }

    fun missingFiles(appFilesDir: File): List<ModelFile> {
        val root = modelRoot(appFilesDir)
        return requiredFiles.filter { File(root, it.path).let { f -> !f.isFile || f.length() < it.minBytes } }
    }

    fun installedBytes(appFilesDir: File): Long {
        val root = modelRoot(appFilesDir)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(appFilesDir: File): Boolean = modelRoot(appFilesDir).deleteRecursively()

    fun download(
        appFilesDir: File,
        onProgress: (fileLabel: String, fileIndex: Int, fileCount: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        val root = modelRoot(appFilesDir).apply { mkdirs() }
        val files = missingFiles(appFilesDir)
        if (files.isEmpty()) return root
        files.forEachIndexed { index, mf ->
            val target = File(root, mf.path)
            target.parentFile?.mkdirs()
            downloadOne("$BASE_URL/${mf.path}", target) { done, total ->
                onProgress(mf.path.substringAfterLast('/'), index + 1, files.size, done, total)
            }
        }
        if (!isInstalled(appFilesDir)) {
            throw IllegalStateException("Qwen 0.6B download incomplete. Missing: ${missingFiles(appFilesDir).joinToString { it.path }}")
        }
        return root
    }

    private fun downloadOne(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        if (target.isFile && target.length() > 1024L * 1024L) {
            onProgress(target.length(), target.length())
            return
        }
        val tmp = File(target.parentFile, "${target.name}.download")
        tmp.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "QwenVoiceKeyboard/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Download failed: HTTP $code for ${target.name}")
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            var downloaded = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 1024L * 1024L || downloaded == total) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            if (tmp.length() < 1024L) throw IllegalStateException("Downloaded ${target.name} is too small")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            onProgress(target.length(), if (total > 0L) total else target.length())
        } finally {
            conn.disconnect()
            if (tmp.exists() && !target.exists()) tmp.delete()
        }
    }
}
