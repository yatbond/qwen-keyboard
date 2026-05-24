package ai.qwenkeyboard.benchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.*

class QwenRealAsrEngine(private val appFilesDir: File) : LocalAsrEngine {
    private val modelRoot = File(appFilesDir, "models/qwen3-asr-0.6b-onnx-cpu")
    private val onnxDir = File(modelRoot, "onnx_models")
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val opts by lazy {
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    }
    private val encoderConv by lazy { env.createSession(File(onnxDir, "encoder_conv.onnx").absolutePath, opts) }
    private val encoderTransformer by lazy { env.createSession(File(onnxDir, "encoder_transformer.onnx").absolutePath, opts) }
    private val decoderInit by lazy { env.createSession(File(onnxDir, "decoder_init.int8.onnx").absolutePath, opts) }
    private val decoderStep by lazy { env.createSession(File(onnxDir, "decoder_step.int8.onnx").absolutePath, opts) }
    private val embeddings by lazy { EmbeddingTable(File(onnxDir, "embed_tokens.bin")) }
    private val tokenizer by lazy { QwenByteLevelDecoder(File(modelRoot, "tokenizer.json")) }
    private val melFilters by lazy { makeMelFilters() }

    override fun transcribe(wavFile: File, audioMs: Long): BenchmarkResult {
        val started = System.currentTimeMillis()
        val wav = readMono16kPcm(wavFile)
        Log.i(TAG, "Qwen local transcribe start audioMs=$audioMs samples=${wav.size} maxTokens=96")
        val text = transcribeShort(wav, maxNewTokens = 96)
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "Qwen local transcribe done elapsed=${elapsed}ms textLen=${text.length} text=$text")
        return BenchmarkResult(text, audioMs, elapsed)
    }

    private fun transcribeShort(wav: FloatArray, maxNewTokens: Int): String {
        val mel = computeMel(wav)
        val audioFeatures = encodeAudio(mel)
        val promptIds = buildPrompt(audioFeatures.size / HIDDEN_SIZE)
        val inputEmbeds = FloatArray(promptIds.size * HIDDEN_SIZE)
        var audioCursor = 0
        for (i in promptIds.indices) {
            if (promptIds[i] == AUDIO_PAD_ID) {
                System.arraycopy(audioFeatures, audioCursor * HIDDEN_SIZE, inputEmbeds, i * HIDDEN_SIZE, HIDDEN_SIZE)
                audioCursor++
            } else {
                embeddings.copy(promptIds[i], inputEmbeds, i * HIDDEN_SIZE)
            }
        }
        val generated = ArrayList<Int>()
        val initResult = decoderInit.run(mapOf(
            "input_embeds" to tensor(inputEmbeds, longArrayOf(1, promptIds.size.toLong(), HIDDEN_SIZE.toLong())),
            "position_ids" to tensorLong(LongArray(promptIds.size) { it.toLong() }, longArrayOf(1, promptIds.size.toLong()))
        ))
        var currentResult: OrtSession.Result? = initResult
        var logitsTensor = initResult[0] as OnnxTensor
        var logits = logitsTensor.floatBuffer
        var next = argmaxLast(logits, VOCAB_SIZE)
        generated.add(next)
        var pos = promptIds.size

        try {
            repeat(maxNewTokens - 1) {
                if (next == IM_END_ID || next == ENDOFTEXT_ID) return@repeat
                val emb = FloatArray(HIDDEN_SIZE)
                embeddings.copy(next, emb, 0)
                val prev = currentResult ?: return@repeat
                val stepResult = decoderStep.run(mapOf(
                    "input_embeds" to tensor(emb, longArrayOf(1, 1, HIDDEN_SIZE.toLong())),
                    "position_ids" to tensorLong(longArrayOf(pos.toLong()), longArrayOf(1, 1)),
                    "past_keys" to (prev[1] as OnnxTensor),
                    "past_values" to (prev[2] as OnnxTensor)
                ))
                if (prev !== initResult) prev.close()
                currentResult = stepResult
                logitsTensor = stepResult[0] as OnnxTensor
                logits = logitsTensor.floatBuffer
                next = argmaxLast(logits, VOCAB_SIZE)
                if (next != IM_END_ID && next != ENDOFTEXT_ID) generated.add(next)
                pos++
            }
        } finally {
            currentResult?.close()
            if (currentResult !== initResult) initResult.close()
        }
        val raw = tokenizer.decode(generated)
        // If decoding fails or the model only emits control/debug tokens, return blank.
        // The IME must never paste token-id diagnostics into user text fields.
        return raw.substringAfter("<asr_text>", raw)
            .replace(Regex("<[^>]+>"), " ")
            .trim()
            .takeUnless { it.startsWith("Generated token", ignoreCase = true) || it.matches(Regex("[0-9\\s,]+")) }
            .orEmpty()
    }

    private fun encodeAudio(mel: Array<FloatArray>): FloatArray {
        val melLen = mel[0].size
        val chunkNum = ceil(melLen / CHUNK_SIZE.toDouble()).toInt().coerceAtLeast(1)
        val chunkLens = IntArray(chunkNum) { i -> min(CHUNK_SIZE, melLen - i * CHUNK_SIZE).coerceAtLeast(0) }
        val maxChunk = chunkLens.maxOrNull() ?: CHUNK_SIZE
        val padded = FloatArray(chunkNum * N_MELS * maxChunk)
        for (c in 0 until chunkNum) {
            val start = c * CHUNK_SIZE
            for (m in 0 until N_MELS) {
                for (t in 0 until chunkLens[c]) {
                    padded[((c * N_MELS + m) * maxChunk) + t] = mel[m][start + t]
                }
            }
        }
        val convResult = encoderConv.run(mapOf("padded_mel_chunks" to tensor(padded, longArrayOf(chunkNum.toLong(), 1, N_MELS.toLong(), maxChunk.toLong()))))
        val convOut = (convResult[0] as OnnxTensor).floatBuffer
        val convShape = (convResult[0] as OnnxTensor).info.shape
        val convTime = convShape[1].toInt()
        val packedTokens = chunkLens.sumOf { featLenAfterCnn(it) }
        val hidden = FloatArray(packedTokens * ENCODER_CONV_SIZE)
        var outPos = 0
        for (c in 0 until chunkNum) {
            val valid = featLenAfterCnn(chunkLens[c])
            for (t in 0 until valid) {
                val src = ((c * convTime + t) * ENCODER_CONV_SIZE)
                convOut.position(src)
                convOut.get(hidden, outPos, ENCODER_CONV_SIZE)
                outPos += ENCODER_CONV_SIZE
            }
        }
        convResult.close()
        val mask = FloatArray(packedTokens * packedTokens)
        val transformerResult = encoderTransformer.run(mapOf(
            "hidden_states" to tensor(hidden, longArrayOf(packedTokens.toLong(), ENCODER_CONV_SIZE.toLong())),
            "attention_mask" to tensor(mask, longArrayOf(1, 1, packedTokens.toLong(), packedTokens.toLong()))
        ))
        val out = FloatArray(packedTokens * HIDDEN_SIZE)
        ((transformerResult[0] as OnnxTensor).floatBuffer).get(out)
        transformerResult.close()
        return out
    }

    private fun buildPrompt(numAudioTokens: Int): IntArray {
        val ids = ArrayList<Int>()
        ids.add(IM_START_ID); ids.add(8948); ids.add(NEWLINE_ID); ids.add(IM_END_ID); ids.add(NEWLINE_ID) // system
        ids.add(IM_START_ID); ids.add(872); ids.add(NEWLINE_ID) // user
        ids.add(AUDIO_START_ID); repeat(numAudioTokens) { ids.add(AUDIO_PAD_ID) }; ids.add(AUDIO_END_ID)
        ids.add(IM_END_ID); ids.add(NEWLINE_ID)
        ids.add(IM_START_ID); ids.add(77091); ids.add(NEWLINE_ID) // assistant
        return ids.toIntArray()
    }

    private fun readMono16kPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        var dataOffset = 44
        for (i in 12 until bytes.size - 8) {
            if (bytes[i].toInt().toChar() == 'd' && bytes[i+1].toInt().toChar() == 'a' && bytes[i+2].toInt().toChar() == 't' && bytes[i+3].toInt().toChar() == 'a') {
                dataOffset = i + 8; break
            }
        }
        val samples = (bytes.size - dataOffset) / 2
        val out = FloatArray(samples)
        var p = dataOffset
        for (i in 0 until samples) {
            val lo = bytes[p].toInt() and 0xff
            val hi = bytes[p + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
            p += 2
        }
        return out
    }

    private fun computeMel(wav: FloatArray): Array<FloatArray> {
        val pad = N_FFT / 2
        val padded = FloatArray(wav.size + pad * 2)
        for (i in 0 until pad) padded[i] = wav[(pad - i).coerceIn(wav.indices)]
        System.arraycopy(wav, 0, padded, pad, wav.size)
        for (i in 0 until pad) padded[pad + wav.size + i] = wav[(wav.size - 2 - i).coerceIn(wav.indices)]
        val frames = 1 + (padded.size - N_FFT) / HOP_LENGTH
        val mel = Array(N_MELS) { FloatArray(frames) }
        val window = FloatArray(N_FFT) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / N_FFT)).toFloat() }
        val power = FloatArray(N_FFT / 2 + 1)
        var maxLog = -1e9f
        for (f in 0 until frames) {
            val offset = f * HOP_LENGTH
            for (k in power.indices) {
                var re = 0.0
                var im = 0.0
                for (n in 0 until N_FFT) {
                    val x = padded[offset + n] * window[n]
                    val ang = -2.0 * Math.PI * k * n / N_FFT
                    re += x * cos(ang)
                    im += x * sin(ang)
                }
                power[k] = (re * re + im * im).toFloat()
            }
            for (m in 0 until N_MELS) {
                var v = 0f
                for (k in power.indices) v += melFilters[m][k] * power[k]
                val logv = log10(max(v, 1e-10f))
                mel[m][f] = logv
                if (logv > maxLog) maxLog = logv
            }
        }
        val floor = maxLog - 8f
        for (m in 0 until N_MELS) for (f in 0 until frames) mel[m][f] = (max(mel[m][f], floor) + 4f) / 4f
        return mel
    }

    private fun makeMelFilters(): Array<FloatArray> {
        fun hzToMel(hz: Double): Double {
            val fMin = 0.0; val fSp = 200.0 / 3.0
            var mel = (hz - fMin) / fSp
            val minLogHz = 1000.0; val minLogMel = (minLogHz - fMin) / fSp; val logStep = ln(6.4) / 27.0
            if (hz >= minLogHz) mel = minLogMel + ln(hz / minLogHz) / logStep
            return mel
        }
        fun melToHz(mel: Double): Double {
            val fMin = 0.0; val fSp = 200.0 / 3.0
            val minLogHz = 1000.0; val minLogMel = (minLogHz - fMin) / fSp; val logStep = ln(6.4) / 27.0
            return if (mel >= minLogMel) minLogHz * exp(logStep * (mel - minLogMel)) else fMin + fSp * mel
        }
        val nFreqs = N_FFT / 2 + 1
        val fftFreqs = DoubleArray(nFreqs) { it * SAMPLE_RATE.toDouble() / N_FFT }
        val mMin = hzToMel(0.0); val mMax = hzToMel(SAMPLE_RATE / 2.0)
        val melPts = DoubleArray(N_MELS + 2) { i -> mMin + (mMax - mMin) * i / (N_MELS + 1) }
        val hzPts = melPts.map { melToHz(it) }
        val fdiff = DoubleArray(hzPts.size - 1) { hzPts[it + 1] - hzPts[it] }
        val ramps = Array(hzPts.size) { i -> DoubleArray(nFreqs) { j -> hzPts[i] - fftFreqs[j] } }
        val weights = Array(N_MELS) { FloatArray(nFreqs) }
        for (i in 0 until N_MELS) {
            val enorm = 2.0 / (hzPts[i + 2] - hzPts[i])
            for (j in 0 until nFreqs) {
                val lower = -ramps[i][j] / fdiff[i]
                val upper = ramps[i + 2][j] / fdiff[i + 1]
                weights[i][j] = (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
        return weights
    }

    private fun featLenAfterCnn(input: Int): Int { var x = input; repeat(3) { x = (x - 1) / 2 + 1 }; return x }
    private fun tensor(data: FloatArray, shape: LongArray) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    private fun tensorLong(data: LongArray, shape: LongArray) = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(data), shape)
    private fun argmaxLast(buf: FloatBuffer, vocab: Int): Int {
        val start = buf.limit() - vocab
        var best = 0; var bestVal = -Float.MAX_VALUE
        for (i in 0 until vocab) { val v = buf.get(start + i); if (v > bestVal) { bestVal = v; best = i } }
        return best
    }

    companion object {
        private const val TAG = "QwenRealAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val N_FFT = 400
        private const val HOP_LENGTH = 160
        private const val N_MELS = 128
        private const val CHUNK_SIZE = 100
        private const val ENCODER_CONV_SIZE = 896
        private const val HIDDEN_SIZE = 1024
        private const val VOCAB_SIZE = 151936
        private const val AUDIO_START_ID = 151669
        private const val AUDIO_END_ID = 151670
        private const val AUDIO_PAD_ID = 151676
        private const val IM_START_ID = 151644
        private const val IM_END_ID = 151645
        private const val ENDOFTEXT_ID = 151643
        private const val NEWLINE_ID = 198
    }
}

private class EmbeddingTable(file: File) {
    private val mapped: MappedByteBuffer = RandomAccessFile(file, "r").channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN) as MappedByteBuffer
    fun copy(id: Int, dest: FloatArray, offset: Int) {
        var p = id * 1024 * 4
        for (i in 0 until 1024) { dest[offset + i] = mapped.getFloat(p); p += 4 }
    }
}

private class QwenByteLevelDecoder(tokenizerJson: File) {
    private val idToToken = HashMap<Int, String>()
    private val byteDecoder = makeByteDecoder()
    init {
        val json = JSONObject(tokenizerJson.readText())
        val vocab = json.getJSONObject("model").getJSONObject("vocab")
        val keys = vocab.keys()
        while (keys.hasNext()) { val k = keys.next(); idToToken[vocab.getInt(k)] = k }
        val added = json.getJSONArray("added_tokens")
        for (i in 0 until added.length()) { val o = added.getJSONObject(i); idToToken[o.getInt("id")] = o.getString("content") }
    }
    fun decode(ids: List<Int>): String {
        val bytes = ArrayList<Byte>()
        for (id in ids) {
            val tok = idToToken[id] ?: continue
            if (tok.startsWith("<|") && tok.endsWith("|>")) continue
            if (tok.startsWith("<blank") || tok == "<asr_text>") { bytes.addAll(tok.toByteArray(StandardCharsets.UTF_8).toList()); continue }
            for (ch in tok) bytes.add((byteDecoder[ch] ?: ch.code).toByte())
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
    private fun makeByteDecoder(): Map<Char, Int> {
        val bs = ArrayList<Int>()
        bs.addAll(33..126); bs.addAll(161..172); bs.addAll(174..255)
        val cs = ArrayList<Int>(); cs.addAll(bs)
        var n = 0
        for (b in 0..255) if (!bs.contains(b)) { bs.add(b); cs.add(256 + n); n++ }
        return cs.map { it.toChar() }.zip(bs).toMap()
    }
}
