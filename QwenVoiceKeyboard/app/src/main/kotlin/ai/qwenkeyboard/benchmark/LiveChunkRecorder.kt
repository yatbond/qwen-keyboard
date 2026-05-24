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

class LiveChunkRecorder(
    private val outputDir: File,
    private val queue: LinkedBlockingQueue<LiveChunk>,
    private val chunkMs: Long = 4_000L,
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
        val bufferSize = max(minBuffer, SAMPLE_RATE / 2)
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return
        recorder = audioRecord
        val buffer = ByteArray(bufferSize)
        var index = 1
        var chunkStart = System.currentTimeMillis()
        var chunkFile = File(outputDir, "live-chunk-$index.wav")
        var out = FileOutputStream(chunkFile, false)
        writeEmptyHeader(out)
        var bytesWritten = 0L
        try {
            audioRecord.startRecording()
            while (running.get()) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    bytesWritten += read
                }
                val now = System.currentTimeMillis()
                if (now - chunkStart >= chunkMs && bytesWritten > 0) {
                    out.close()
                    fixHeader(chunkFile)
                    queue.offer(LiveChunk(index, chunkFile, now - chunkStart))
                    index++
                    chunkStart = now
                    chunkFile = File(outputDir, "live-chunk-$index.wav")
                    out = FileOutputStream(chunkFile, false)
                    writeEmptyHeader(out)
                    bytesWritten = 0L
                }
            }
        } catch (_: IOException) {
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
            if (bytesWritten > 0) {
                fixHeader(chunkFile)
                queue.offer(LiveChunk(index, chunkFile, System.currentTimeMillis() - chunkStart))
            } else {
                chunkFile.delete()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private fun writeEmptyHeader(out: FileOutputStream) { out.write(ByteArray(44)) }

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
