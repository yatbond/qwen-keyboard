package ai.qwenkeyboard.benchmark

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ParakeetModelManager {
    const val ENGINE_TDT_V3 = "phone_parakeet_tdt_0_6b_v3_int8"

    data class Spec(
        val engineName: String,
        val label: String,
        val repo: String,
        val dirName: String,
        val approxBytes: Long,
        val userAgent: String,
    ) {
        val baseUrl: String get() = "https://huggingface.co/$repo/resolve/main"
    }

    data class ModelFile(val path: String, val minBytes: Long)

    private val specs = mapOf(
        ENGINE_TDT_V3 to Spec(
            ENGINE_TDT_V3,
            "Parakeet TDT 0.6B v3 INT8",
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            671L * 1024L * 1024L,
            "DeeKeyboard/3.0 ParakeetTdtV3",
        ),
    )

    val requiredFiles = listOf(
        ModelFile("encoder.int8.onnx", 600L * 1024L * 1024L),
        ModelFile("decoder.int8.onnx", 10L * 1024L * 1024L),
        ModelFile("joiner.int8.onnx", 5L * 1024L * 1024L),
        ModelFile("tokens.txt", 64L * 1024L),
    )

    fun spec(engineName: String = ENGINE_TDT_V3): Spec = specs[engineName] ?: specs.getValue(ENGINE_TDT_V3)
    fun modelRoot(appFilesDir: File, engineName: String = ENGINE_TDT_V3): File = File(appFilesDir, "models/${spec(engineName).dirName}")
    fun encoderFile(appFilesDir: File, engineName: String = ENGINE_TDT_V3): File = File(modelRoot(appFilesDir, engineName), "encoder.int8.onnx")
    fun decoderFile(appFilesDir: File, engineName: String = ENGINE_TDT_V3): File = File(modelRoot(appFilesDir, engineName), "decoder.int8.onnx")
    fun joinerFile(appFilesDir: File, engineName: String = ENGINE_TDT_V3): File = File(modelRoot(appFilesDir, engineName), "joiner.int8.onnx")
    fun tokensFile(appFilesDir: File, engineName: String = ENGINE_TDT_V3): File = File(modelRoot(appFilesDir, engineName), "tokens.txt")

    fun isInstalled(appFilesDir: File, engineName: String = ENGINE_TDT_V3): Boolean {
        val root = modelRoot(appFilesDir, engineName)
        return requiredFiles.all { File(root, it.path).let { f -> f.isFile && f.length() >= it.minBytes } }
    }

    fun missingFiles(appFilesDir: File, engineName: String = ENGINE_TDT_V3): List<ModelFile> {
        val root = modelRoot(appFilesDir, engineName)
        return requiredFiles.filter { File(root, it.path).let { f -> !f.isFile || f.length() < it.minBytes } }
    }

    fun installedBytes(appFilesDir: File, engineName: String = ENGINE_TDT_V3): Long {
        val root = modelRoot(appFilesDir, engineName)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(appFilesDir: File, engineName: String = ENGINE_TDT_V3): Boolean = modelRoot(appFilesDir, engineName).deleteRecursively()

    fun download(
        appFilesDir: File,
        engineName: String = ENGINE_TDT_V3,
        onProgress: (fileLabel: String, fileIndex: Int, fileCount: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        val spec = spec(engineName)
        val root = modelRoot(appFilesDir, engineName).apply { mkdirs() }
        val files = missingFiles(appFilesDir, engineName)
        if (files.isEmpty()) return root
        files.forEachIndexed { index, mf ->
            val target = File(root, mf.path)
            target.parentFile?.mkdirs()
            downloadOne("${spec.baseUrl}/${mf.path}", target, spec.userAgent) { done, total ->
                onProgress(mf.path.substringAfterLast('/'), index + 1, files.size, done, total)
            }
        }
        if (!isInstalled(appFilesDir, engineName)) {
            throw IllegalStateException("${spec.label} download incomplete. Missing: ${missingFiles(appFilesDir, engineName).joinToString { it.path }}")
        }
        return root
    }

    private fun downloadOne(url: String, target: File, userAgent: String, onProgress: (Long, Long) -> Unit) {
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
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Download failed: HTTP $code for ${target.name}")
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            var downloaded = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
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
