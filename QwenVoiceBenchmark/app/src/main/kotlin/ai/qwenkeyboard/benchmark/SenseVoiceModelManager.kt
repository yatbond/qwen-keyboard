package ai.qwenkeyboard.benchmark

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SenseVoiceModelManager {
    const val ENGINE_NAME = "phone_sensevoice_int8" // 2025 Yue default/current
    const val ENGINE_NAME_2024 = "phone_sensevoice_2024"

    data class Spec(
        val engineName: String,
        val label: String,
        val repo: String,
        val dirName: String,
        val userAgent: String,
    ) {
        val baseUrl: String get() = "https://huggingface.co/$repo/resolve/main"
    }

    private val specs = mapOf(
        ENGINE_NAME to Spec(
            ENGINE_NAME,
            "SenseVoice Yue 2025",
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "DeeKeyboard/1.10 SenseVoiceYue2025",
        ),
        ENGINE_NAME_2024 to Spec(
            ENGINE_NAME_2024,
            "SenseVoice 2024",
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            "DeeKeyboard/1.10 SenseVoice2024",
        ),
    )

    data class ModelFile(val path: String, val minBytes: Long)

    val requiredFiles = listOf(
        ModelFile("model.int8.onnx", 200L * 1024L * 1024L),
        ModelFile("tokens.txt", 200L * 1024L),
    )

    fun spec(engineName: String = ENGINE_NAME): Spec = specs[engineName] ?: specs.getValue(ENGINE_NAME)
    fun modelRoot(appFilesDir: File, engineName: String = ENGINE_NAME): File = File(appFilesDir, "models/${spec(engineName).dirName}")
    fun modelFile(appFilesDir: File, engineName: String = ENGINE_NAME): File = File(modelRoot(appFilesDir, engineName), "model.int8.onnx")
    fun tokensFile(appFilesDir: File, engineName: String = ENGINE_NAME): File = File(modelRoot(appFilesDir, engineName), "tokens.txt")

    fun isInstalled(appFilesDir: File, engineName: String = ENGINE_NAME): Boolean {
        val root = modelRoot(appFilesDir, engineName)
        return requiredFiles.all { File(root, it.path).let { f -> f.isFile && f.length() >= it.minBytes } }
    }

    fun missingFiles(appFilesDir: File, engineName: String = ENGINE_NAME): List<ModelFile> {
        val root = modelRoot(appFilesDir, engineName)
        return requiredFiles.filter { File(root, it.path).let { f -> !f.isFile || f.length() < it.minBytes } }
    }

    fun installedBytes(appFilesDir: File, engineName: String = ENGINE_NAME): Long {
        val root = modelRoot(appFilesDir, engineName)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(appFilesDir: File, engineName: String = ENGINE_NAME): Boolean = modelRoot(appFilesDir, engineName).deleteRecursively()

    fun cleanupLegacyModelFiles(appFilesDir: File) {
        listOf(File(appFilesDir, "models/" + "whis" + "per"))
            .forEach { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    fun download(
        appFilesDir: File,
        engineName: String = ENGINE_NAME,
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
