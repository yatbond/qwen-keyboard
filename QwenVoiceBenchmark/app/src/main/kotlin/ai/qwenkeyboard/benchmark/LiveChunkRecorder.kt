package ai.qwenkeyboard.benchmark

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class LiveChunkRecorder(
    private val outputDir: File,
    private val queue: LinkedBlockingQueue<LiveChunk>,
    private val chunkMs: Long = 4_000L,
    private val overlapMs: Long = 0L,
    private val vadEnabled: Boolean = true,
    private val minSpeechChunkMs: Long = 250L,
    private val pauseFlushMs: Long = 320L,
    private val silenceRmsThreshold: Double = 0.004,
    private val minAsrChunkMs: Long = 1200L,
) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        outputDir.mkdirs()
        thread = Thread({ recordLoop() }, "live-chunk-recorder").also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        thread?.join(1500)
        recorder?.release()
        recorder = null
    }

    private fun recordLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = max(minBuffer, SAMPLE_RATE / 4) // smaller reads lower VAD/flush latency
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return
        recorder = audioRecord
        val buffer = ByteArray(bufferSize)
        var index = 1
        var chunkStart = System.currentTimeMillis()
        var speechStart = 0L
        var lastSpeechAt = 0L
        var hasSpeech = false
        var chunkFile = File(outputDir, "live-chunk-$index.wav")
        var out = FileOutputStream(chunkFile, false)
        writeEmptyHeader(out)
        var bytesWritten = 0L
        val overlapBytes = ((SAMPLE_RATE * 2L * overlapMs) / 1000L).toInt().coerceAtLeast(0)
        var tail = ByteArray(0)

        fun updateTail(data: ByteArray, len: Int) {
            if (overlapBytes <= 0 || len <= 0) return
            val combined = ByteArray(tail.size + len)
            System.arraycopy(tail, 0, combined, 0, tail.size)
            System.arraycopy(data, 0, combined, tail.size, len)
            tail = if (combined.size > overlapBytes) combined.copyOfRange(combined.size - overlapBytes, combined.size) else combined
        }

        fun startNextChunk(now: Long) {
            index++
            chunkStart = now
            speechStart = 0L
            lastSpeechAt = 0L
            hasSpeech = false
            chunkFile = File(outputDir, "live-chunk-$index.wav")
            out = FileOutputStream(chunkFile, false)
            writeEmptyHeader(out)
            if (tail.isNotEmpty()) out.write(tail)
            bytesWritten = tail.size.toLong()
        }

        fun finishChunk(now: Long, keepIfSpeech: Boolean) {
            try { out.close() } catch (_: Exception) {}
            if (bytesWritten > 44 && (!vadEnabled || hasSpeech || keepIfSpeech)) {
                padShortChunkForAsr(chunkFile, minAsrChunkMs)
                fixHeader(chunkFile)
                queue.offer(LiveChunk(index, chunkFile, audioDurationMs(chunkFile).coerceAtLeast(now - chunkStart)))
            } else {
                chunkFile.delete()
            }
            startNextChunk(now)
        }

        try {
            audioRecord.startRecording()
            while (running.get()) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                val now = System.currentTimeMillis()
                if (read > 0) {
                    val speech = !vadEnabled || rmsPcm16(buffer, read) >= silenceRmsThreshold
                    if (speech) {
                        if (!hasSpeech) speechStart = now
                        hasSpeech = true
                        lastSpeechAt = now
                    }
                    out.write(buffer, 0, read)
                    bytesWritten += read
                    updateTail(buffer, read)
                }

                val age = now - chunkStart
                val speechAge = if (speechStart > 0) now - speechStart else 0L
                val paused = hasSpeech && lastSpeechAt > 0 && now - lastSpeechAt >= pauseFlushMs && speechAge >= minSpeechChunkMs
                val maxed = age >= chunkMs && bytesWritten > 0
                val longSilence = vadEnabled && !hasSpeech && age >= minOf(chunkMs, 1500L)

                when {
                    paused -> finishChunk(now, keepIfSpeech = true)          // send as soon as user pauses
                    maxed -> finishChunk(now, keepIfSpeech = hasSpeech)      // fixed max chunk fallback
                    longSilence -> finishChunk(now, keepIfSpeech = false)    // discard silence early
                }
            }
        } catch (_: IOException) {
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
            if (bytesWritten > 44 && (!vadEnabled || hasSpeech)) {
                padShortChunkForAsr(chunkFile, minAsrChunkMs)
                fixHeader(chunkFile)
                queue.offer(LiveChunk(index, chunkFile, audioDurationMs(chunkFile).coerceAtLeast(System.currentTimeMillis() - chunkStart)))
            } else {
                chunkFile.delete()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private fun rmsPcm16(bytes: ByteArray, len: Int): Double {
            var sum = 0.0
            var samples = 0
            var i = 0
            while (i + 1 < len) {
                val lo = bytes[i].toInt() and 0xff
                val hi = bytes[i + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt() / 32768.0
                sum += sample * sample
                samples++
                i += 2
            }
            return if (samples == 0) 0.0 else sqrt(sum / samples)
        }

        private fun writeEmptyHeader(out: FileOutputStream) { out.write(ByteArray(44)) }

        private fun audioDurationMs(file: File): Long = (max(0L, file.length() - 44L) * 1000L) / (SAMPLE_RATE * 2L)

        private fun padShortChunkForAsr(file: File, minDurationMs: Long) {
            val targetDataBytes = ((SAMPLE_RATE * 2L * minDurationMs) / 1000L).coerceAtLeast(0L)
            val dataLen = max(0L, file.length() - 44L)
            val missing = targetDataBytes - dataLen
            if (missing <= 0L) return
            FileOutputStream(file, true).use { out ->
                val zeros = ByteArray(8192)
                var left = missing
                while (left > 0L) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n)
                    left -= n
                }
            }
        }

        private fun fixHeader(file: File) {
            val dataLen = max(0L, file.length() - 44)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.writeBytes("RIFF")
                raf.writeLeInt((dataLen + 36).toInt())
                raf.writeBytes("WAVEfmt ")
                raf.writeLeInt(16)
                raf.writeLeShort(1)
                raf.writeLeShort(1)
                raf.writeLeInt(SAMPLE_RATE)
                raf.writeLeInt(SAMPLE_RATE * 2)
                raf.writeLeShort(2)
                raf.writeLeShort(16)
                raf.writeBytes("data")
                raf.writeLeInt(dataLen.toInt())
            }
        }

        private fun RandomAccessFile.writeLeInt(value: Int) {
            write(value and 0xff)
            write((value shr 8) and 0xff)
            write((value shr 16) and 0xff)
            write((value shr 24) and 0xff)
        }

        private fun RandomAccessFile.writeLeShort(value: Int) {
            write(value and 0xff)
            write((value shr 8) and 0xff)
        }
    }
}

data class LiveChunk(val index: Int, val file: File, val durationMs: Long)
