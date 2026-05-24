package ai.qwenkeyboard.benchmark

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val recorder = WavRecorder()
    private lateinit var asrEngine: LocalAsrEngine
    private lateinit var status: TextView
    private lateinit var errorBox: TextView
    private lateinit var errorScroll: ScrollView
    private lateinit var transcriptBox: TextView
    private lateinit var recordButton: Button
    private lateinit var correctionInput: EditText
    private lateinit var saveCorrectionButton: Button
    private lateinit var exportButton: Button
    private lateinit var benchmarkLogButton: Button
    private lateinit var clearBenchmarkLogButton: Button
    private lateinit var liveButton: Button
    private lateinit var livePcButton: Button
    private lateinit var pcButton: Button
    private lateinit var pcUrlInput: EditText
    private lateinit var pcTokenInput: EditText
    private lateinit var pcTokenEditButton: Button
    private lateinit var pcEngineSpinner: Spinner
    private lateinit var pcChunkSpinner: Spinner
    private lateinit var localEngineSpinner: Spinner
    private val localEngineOptions = listOf(
        SenseVoiceModelManager.ENGINE_NAME,
        SenseVoiceModelManager.ENGINE_NAME_2024,
        "phone_qwen_0_6b",
    )
    private var activeLocalEngineId = SenseVoiceModelManager.ENGINE_NAME
    private val pcEngineOptions = listOf(
        "sensevoice_yue_2025",
        "qwen_0_6b",
        "qwen_1_7b",
    )
    private val pcChunkOptions = listOf("2s", "3s", "5s", "7s", "10s", "12s", "15s")
    private var recording = false
    private var pcRecording = false
    private val liveRunning = AtomicBoolean(false)
    private var liveRecorder: LiveChunkRecorder? = null
    private var liveWorker: Thread? = null
    private val liveQueue = LinkedBlockingQueue<LiveChunk>()
    private val liveTranscript = StringBuilder()
    private val pcLiveRunning = AtomicBoolean(false)
    private var pcLiveRecorder: LiveChunkRecorder? = null
    private var pcLiveWorker: Thread? = null
    private var pcLiveQueue = LinkedBlockingQueue<LiveChunk>()
    private val pcLiveTranscript = StringBuilder()
    private var lastSample: LoggedSample? = null
    private var clearLogArmedUntilMs = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SenseVoiceModelManager.cleanupLegacyModelFiles(filesDir)
        activeLocalEngineId = localPrefs().getString("engine", SenseVoiceModelManager.ENGINE_NAME) ?: SenseVoiceModelManager.ENGINE_NAME
        asrEngine = createLocalAsrEngine(activeLocalEngineId)
        setContentView(makeUi())
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private fun makeUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Dee Keyboard Benchmark\nv1.10.8-sense-yue-local"
            textSize = 20f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "Ready. Compare local SenseVoice Yue 2025 vs PC Qwen/SenseVoice. App v1.10.8"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        errorBox = TextView(this).apply {
            text = ""
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        errorScroll = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x22FF0000)
            setNestedScrollTouchGuard()
            addView(errorBox, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(errorScroll, LinearLayout.LayoutParams(-1, dp(82)).apply { bottomMargin = dp(6) })

        transcriptBox = EditText(this).apply {
            setText("Transcript will appear here.")
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            keyListener = null
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            movementMethod = ScrollingMovementMethod.getInstance()
            setBackgroundColor(0x11000000)
            setNestedTextTouchGuard()
        }
        root.addView(transcriptBox, LinearLayout.LayoutParams(-1, dp(170)).apply { bottomMargin = dp(6) })
        root.addView(TextView(this).apply {
            text = "Phone-local ASR engine"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }, LinearLayout.LayoutParams(-1, -2))

        localEngineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                localEngineOptions.map { localEngineLabel(it) },
            )
            val saved = localPrefs().getString("engine", SenseVoiceModelManager.ENGINE_NAME) ?: SenseVoiceModelManager.ENGINE_NAME
            val index = localEngineOptions.indexOf(saved).takeIf { it >= 0 } ?: 0
            setSelection(index)
        }
        root.addView(localEngineSpinner, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "Download selected local model"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { downloadSelectedLocalModel() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })

        recordButton = Button(this).apply {
            text = "Record → Local ASR"
            textSize = 15f
            setOnClickListener { toggleRecording() }
        }
        root.addView(recordButton, LinearLayout.LayoutParams(-1, dp(44)))

        pcUrlInput = EditText(this).apply {
            hint = "PC ASR URL, e.g. https://qwen-asr.example.com"
            setText(pcPrefs().getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com")
            setSingleLine(true)
            textSize = 13f
        }
        root.addView(pcUrlInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "PC ASR model"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }, LinearLayout.LayoutParams(-1, -2))

        pcEngineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                pcEngineOptions,
            )
            val saved = pcPrefs().getString("engine", "qwen_0_6b") ?: "qwen_0_6b"
            val index = pcEngineOptions.indexOf(saved).takeIf { it >= 0 } ?: pcEngineOptions.indexOf("qwen_0_6b")
            setSelection(index)
        }
        root.addView(pcEngineSpinner, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Chunking for long recordings"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }, LinearLayout.LayoutParams(-1, -2))

        pcChunkSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                pcChunkOptions,
            )
            val saved = pcPrefs().getString("chunk_sec", "12") ?: "12"
            val label = "${saved}s"
            val index = pcChunkOptions.indexOf(label).takeIf { it >= 0 } ?: pcChunkOptions.indexOf("12s")
            setSelection(index)
        }
        root.addView(pcChunkSpinner, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Access token — advanced, leave unchanged unless server token changed"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }, LinearLayout.LayoutParams(-1, -2))

        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pcTokenInput = EditText(this).apply {
            hint = "Protected PC ASR access token"
            setText(pcPrefs().getString("token", "") ?: "")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 13f
            isEnabled = false
            alpha = 0.72f
        }
        tokenRow.addView(pcTokenInput, LinearLayout.LayoutParams(0, -2, 1f))
        pcTokenEditButton = Button(this).apply {
            text = "Edit"
            textSize = 13f
            setOnClickListener {
                val editing = !pcTokenInput.isEnabled
                pcTokenInput.isEnabled = editing
                pcTokenInput.alpha = if (editing) 1.0f else 0.72f
                text = if (editing) "Lock" else "Edit"
                if (editing) {
                    pcTokenInput.requestFocus()
                    pcTokenInput.setSelection(pcTokenInput.text.length)
                    status.text = "Token editing enabled. Be careful: changing it can break PC ASR auth."
                } else {
                    val url = pcUrlInput.text.toString().ifBlank { "http://127.0.0.1:8765" }
                    val token = pcTokenInput.text.toString()
                    val engine = pcEngineSpinner.selectedItem?.toString() ?: "qwen_0_6b"
                    val chunkSec = selectedChunkSec()
                    savePcPrefs(url, token, engine, chunkSec)
                    status.text = "Token locked and saved."
                }
            }
        }
        tokenRow.addView(pcTokenEditButton, LinearLayout.LayoutParams(dp(88), -2).apply { leftMargin = dp(8) })
        root.addView(tokenRow, LinearLayout.LayoutParams(-1, -2))

        pcButton = Button(this).apply {
            text = "Record → PC ASR"
            textSize = 14f
            setOnClickListener { togglePcRecording() }
        }
        root.addView(pcButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        livePcButton = Button(this).apply {
            text = "Live PC ASR Chunks"
            textSize = 14f
            setOnClickListener { togglePcLiveMode() }
        }
        root.addView(livePcButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        benchmarkLogButton = Button(this).apply {
            text = "Show Benchmark Log"
            textSize = 14f
            setOnClickListener { showBenchmarkLog() }
        }
        root.addView(benchmarkLogButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        clearBenchmarkLogButton = Button(this).apply {
            text = "Clear Benchmark Log"
            textSize = 14f
            setOnClickListener { clearBenchmarkLog() }
        }
        root.addView(clearBenchmarkLogButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        liveButton = Button(this).apply {
            text = "Start Local Live 4s"
            textSize = 14f
            setOnClickListener { toggleLiveMode() }
        }
        root.addView(liveButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        val probeButton = Button(this).apply {
            text = "Probe Qwen ONNX Model"
            textSize = 14f
            setOnClickListener { probeModel() }
        }
        root.addView(probeButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        correctionInput = EditText(this).apply {
            hint = "Type correct transcript here after recording"
            minLines = 2
            textSize = 16f
        }
        root.addView(correctionInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        saveCorrectionButton = Button(this).apply {
            text = "Save Correction"
            textSize = 14f
            isEnabled = false
            setOnClickListener { saveCorrection() }
        }
        root.addView(saveCorrectionButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        exportButton = Button(this).apply {
            text = "Prepare Export for PC"
            textSize = 14f
            setOnClickListener { prepareExportPackage() }
        }
        root.addView(exportButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        return scroll
    }

    private fun toggleRecording() = if (recording) stopAndRunBenchmark() else startRecording()

    private fun togglePcRecording() = if (pcRecording) stopAndRunPcAsr() else startPcRecording()

    private fun togglePcLiveMode() {
        if (pcLiveRunning.get()) stopPcLiveMode() else startPcLiveMode()
    }

    private fun ScrollView.setNestedScrollTouchGuard() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun TextView.setNestedTextTouchGuard() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun clearError() {
        errorBox.text = ""
        errorScroll.visibility = View.GONE
    }

    private fun showError(title: String, message: String?) {
        errorBox.text = buildString {
            append(title)
            val msg = message?.trim().orEmpty()
            if (msg.isNotBlank()) {
                appendLine()
                append(msg)
            }
        }
        errorScroll.visibility = View.VISIBLE
        errorScroll.post { errorScroll.scrollTo(0, 0) }
    }

    private fun startPcRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (recording || liveRunning.get() || pcLiveRunning.get()) return
        try {
            val wav = File(cacheDir, "qwen-asr-pc-sample.wav")
            recorder.start(wav)
            clearError()
            pcRecording = true
            recordButton.isEnabled = false
            liveButton.isEnabled = false
            livePcButton.isEnabled = false
            pcButton.text = "Stop + PC ASR"
            status.text = "Recording for PC ASR… speak normally."
            transcriptBox.text = ""
        } catch (e: Exception) {
            status.text = "Could not start PC recording."
            showError("PC recording start error", e.message)
        }
    }

    private fun stopAndRunPcAsr() {
        pcRecording = false
        clearError()
        pcButton.isEnabled = false
        pcButton.text = "Sending to PC…"
        status.text = "Sending WAV to PC ASR server…"
        Thread {
            try {
                val rec = recorder.stop()
                val started = System.currentTimeMillis()
                val url = pcUrlInput.text.toString().ifBlank { "http://127.0.0.1:8765" }
                val token = pcTokenInput.text.toString()
                val engine = pcEngineSpinner.selectedItem?.toString()?.ifBlank { "qwen_0_6b" } ?: "qwen_0_6b"
                val chunkSec = selectedChunkSec()
                savePcPrefs(url, token, engine, chunkSec)
                val pc = PcAsrClient(
                    baseUrl = url,
                    token = token,
                    engine = engine,
                    chunkSec = chunkSec,
                ).transcribe(rec.file)
                val elapsed = System.currentTimeMillis() - started
                val result = BenchmarkResult(pc.text, rec.durationMs, elapsed)
                runOnUiThread {
                    val sample = saveSample(
                        result,
                        rec.file,
                        idPrefix = "pc",
                        source = "pc",
                        engine = pc.engine,
                        serverElapsedMs = pc.serverElapsedMs,
                        engineRtf = pc.rtf,
                        chunkSec = pc.chunkSec,
                        chunkCount = pc.chunkCount,
                        chunkingEnabled = pc.chunkingEnabled,
                    )
                    lastSample = sample
                    correctionInput.setText("")
                    saveCorrectionButton.isEnabled = true
                    val chunkLabel = if (pc.chunkingEnabled) ", chunks: ${pc.chunkCount}×${pc.chunkSec}s" else ", no split"
                    status.text = "PC ASR PASS (${pc.engine}) — Audio: ${rec.durationMs} ms, words: ${sample.wordCount}, server: ${pc.serverElapsedMs} ms, roundtrip: ${elapsed} ms, RTF: ${DecimalFormat("0.00").format(sample.roundtripRtf)}x$chunkLabel\nSample: ${sample.id}"
                    transcriptBox.text = pc.text
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PC ASR failed. See error box below."
                    showError("PC ASR error — check server / tunnel / URL", e.message)
                }
            } finally {
                runOnUiThread {
                    pcButton.isEnabled = true
                    pcButton.text = "Record → PC ASR"
                    recordButton.isEnabled = true
                    liveButton.isEnabled = true
                    livePcButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun startPcLiveMode() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (recording || pcRecording || liveRunning.get()) {
            status.text = "Another recording mode is active. Stop it first, then start Live PC ASR."
            return
        }
        pcLiveRecorder?.stop()
        pcLiveRecorder = null
        pcLiveWorker?.interrupt()
        clearError()
        pcLiveTranscript.clear()
        pcLiveQueue = LinkedBlockingQueue()
        val runQueue = pcLiveQueue
        val url = pcUrlInput.text.toString().ifBlank { "https://voice.dee-photography.com" }
        val token = pcTokenInput.text.toString()
        val engine = pcEngineSpinner.selectedItem?.toString()?.ifBlank { "qwen_0_6b" } ?: "qwen_0_6b"
        val chunkSec = selectedChunkSec()
        savePcPrefs(url, token, engine, chunkSec)

        pcLiveRunning.set(true)
        recordButton.isEnabled = false
        pcButton.isEnabled = false
        liveButton.isEnabled = false
        livePcButton.text = "Stop Live PC ASR"
        status.text = "Live PC ASR started. Sending ${chunkSec}s chunks to $engine…"
        transcriptBox.text = ""
        val dir = File(cacheDir, "pc_live_chunks").apply { deleteRecursively(); mkdirs() }
        pcLiveRecorder = LiveChunkRecorder(dir, runQueue, chunkMs = chunkSec * 1000L).also { it.start() }
        pcLiveWorker = Thread({ pcLiveTranscribeLoop(runQueue, url, token, engine, chunkSec) }, "pc-live-asr-worker").also { it.start() }
    }

    private fun stopPcLiveMode() {
        pcLiveRunning.set(false)
        pcLiveRecorder?.stop()
        pcLiveRecorder = null
        livePcButton.text = "Live PC ASR Chunks"
        recordButton.isEnabled = true
        pcButton.isEnabled = true
        liveButton.isEnabled = true
        status.text = "Stopping Live PC ASR… finishing queued chunks if any."
        transcriptBox.text = pcLiveTranscript.toString()
    }

    private fun pcLiveTranscribeLoop(queue: LinkedBlockingQueue<LiveChunk>, url: String, token: String, engine: String, chunkSec: Int) {
        var totalAudioMs = 0L
        val startedAll = System.currentTimeMillis()
        while (pcLiveRunning.get() || queue.isNotEmpty()) {
            val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val started = System.currentTimeMillis()
            runOnUiThread {
                status.text = "Live PC ASR transcribing chunk ${chunk.index} (${chunkSec}s setting)…"
                transcriptBox.text = pcLiveTranscript.toString()
            }
            try {
                // Send each phone-recorded live chunk as-is. Disable server-side re-chunking here,
                // otherwise every live chunk may be split again and delay intermediate text.
                val pc = PcAsrClient(
                    baseUrl = url,
                    token = token,
                    engine = engine,
                    chunkSec = 0,
                ).transcribe(chunk.file)
                val elapsed = System.currentTimeMillis() - started
                totalAudioMs += chunk.durationMs
                val text = pc.text.trim()
                if (text.isNotBlank()) pcLiveTranscript.append(text).append(' ')
                val result = BenchmarkResult(text, chunk.durationMs, elapsed)
                val sample = saveSample(
                    result,
                    chunk.file,
                    idPrefix = "pc-live-${chunk.index}",
                    source = "pc_live",
                    engine = pc.engine,
                    serverElapsedMs = pc.serverElapsedMs,
                    engineRtf = pc.rtf,
                    chunkSec = chunkSec,
                    chunkCount = chunk.index,
                    chunkingEnabled = true,
                )
                runOnUiThread {
                    lastSample = sample
                    correctionInput.setText("")
                    saveCorrectionButton.isEnabled = true
                    val avgRtf = if (totalAudioMs <= 0) 0.0 else (System.currentTimeMillis() - startedAll).toDouble() / totalAudioMs
                    status.text = "LIVE PC ASR (${pc.engine}) — chunk ${chunk.index}, server ${pc.serverElapsedMs} ms, roundtrip ${elapsed} ms, avg RTF ${DecimalFormat("0.00").format(avgRtf)}x\nSample: ${sample.id}"
                    transcriptBox.text = pcLiveTranscript.toString()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Live PC chunk ${chunk.index} failed. See error box below."
                    showError("Live PC chunk ${chunk.index} error", e.message)
                    transcriptBox.text = pcLiveTranscript.toString()
                }
            } finally {
                chunk.file.delete()
            }
        }
        runOnUiThread {
            if (!pcLiveRunning.get()) {
                livePcButton.text = "Live PC ASR Chunks"
                recordButton.isEnabled = true
                pcButton.isEnabled = true
                liveButton.isEnabled = true
                status.text = "Live PC ASR stopped. Transcript has ${countWords(pcLiveTranscript.toString())} text tokens."
                transcriptBox.text = pcLiveTranscript.toString()
            }
        }
    }

    private fun toggleLiveMode() {
        if (liveRunning.get()) stopLiveMode() else startLiveMode()
    }

    private fun startLiveMode() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (recording || pcRecording || pcLiveRunning.get()) return
        clearError()
        liveTranscript.clear()
        liveQueue.clear()
        liveRunning.set(true)
        recordButton.isEnabled = false
        pcButton.isEnabled = false
        livePcButton.isEnabled = false
        liveButton.text = "Stop Live"
        val engineId = selectedLocalEngineId()
        activeLocalEngineId = engineId
        asrEngine = createLocalAsrEngine(engineId)
        saveLocalEnginePref(engineId)
        status.text = "Live local ASR started with ${localEngineLabel(engineId)}. Recording 4-second chunks…"
        transcriptBox.text = ""
        val dir = File(cacheDir, "live_chunks").apply { mkdirs() }
        liveRecorder = LiveChunkRecorder(dir, liveQueue).also { it.start() }
        liveWorker = Thread({ liveTranscribeLoop() }, "live-asr-worker").also { it.start() }
    }

    private fun stopLiveMode() {
        liveRunning.set(false)
        liveRecorder?.stop()
        liveRecorder = null
        liveButton.text = "Start Local Live 4s"
        recordButton.isEnabled = true
        pcButton.isEnabled = true
        livePcButton.isEnabled = true
        status.text = "Stopping live mode… finishing queued chunks if any."
        transcriptBox.text = liveTranscript.toString()
    }

    private fun liveTranscribeLoop() {
        while (liveRunning.get() || liveQueue.isNotEmpty()) {
            val chunk = liveQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val started = System.currentTimeMillis()
            runOnUiThread {
                status.text = "Live transcribing chunk ${chunk.index}…"
                transcriptBox.text = liveTranscript.toString()
            }
            try {
                val engineId = activeLocalEngineId
                val result = asrEngine.transcribe(chunk.file, chunk.durationMs)
                val sample = saveSample(result, chunk.file, idPrefix = "live-${chunk.index}", engine = engineId)
                val line = result.transcript.trim()
                if (line.isNotBlank()) liveTranscript.append(line).append(' ')
                runOnUiThread {
                    lastSample = sample
                    correctionInput.setText("")
                    saveCorrectionButton.isEnabled = true
                    val elapsed = System.currentTimeMillis() - started
                    status.text = "LIVE MODE — Last chunk: ${chunk.index}, ${elapsed} ms\nSample: ${sample.id}\nType correction for last chunk below if needed."
                    transcriptBox.text = liveTranscript.toString()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Live chunk ${chunk.index} failed. See error box below."
                    showError("Live chunk ${chunk.index} error", e.message)
                    transcriptBox.text = liveTranscript.toString()
                }
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        try {
            clearError()
            val engineId = selectedLocalEngineId()
            activeLocalEngineId = engineId
            asrEngine = createLocalAsrEngine(engineId)
            saveLocalEnginePref(engineId)
            val wav = File(cacheDir, "qwen-asr-sample.wav")
            recorder.start(wav)
            recording = true
            recordButton.text = "Stop + Benchmark"
            status.text = "Recording for ${localEngineLabel(engineId)}… speak normally.\nSample rate: ${WavRecorder.SAMPLE_RATE} Hz mono PCM16"
        } catch (e: Exception) {
            status.text = "Could not start recording."
            showError("Recording start error", e.message)
        }
    }

    private fun stopAndRunBenchmark() {
        recording = false
        clearError()
        recordButton.isEnabled = false
        recordButton.text = "Processing…"
        status.text = "Stopping recorder and running ASR benchmark…"
        Thread {
            try {
                val rec = recorder.stop()
                val engineId = activeLocalEngineId
                val result = asrEngine.transcribe(rec.file, rec.durationMs)
                runOnUiThread { showResult(result, rec.file, engineId) }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Benchmark failed. See error box below."
                    showError("Benchmark error", e.message)
                }
            } finally {
                runOnUiThread {
                    recordButton.isEnabled = true
                    recordButton.text = "Record → Local ASR"
                }
            }
        }.start()
    }

    private fun probeModel() {
        status.text = "Loading ONNX sessions from phone storage…"
        Thread {
            val result = QwenOnnxProbe(filesDir).probe()
            runOnUiThread {
                status.text = "ONNX probe finished."
                transcriptBox.text = result
            }
        }.start()
    }

    private fun showResult(result: BenchmarkResult, file: File, engineId: String = selectedLocalEngineId()) {
        val df = DecimalFormat("0.00")
        val pass = if (result.realtimeFactor() <= 1.0) "REALTIME PASS" else "SLOWER THAN REALTIME"
        val sample = saveSample(result, file, engine = engineId)
        lastSample = sample
        correctionInput.setText("")
        saveCorrectionButton.isEnabled = true
        status.text = "$pass (${localEngineLabel(engineId)}) — Audio: ${result.audioMs} ms, words: ${sample.wordCount}, Processing: ${result.processingMs} ms, RTF: ${df.format(result.realtimeFactor())}x\nSample: ${sample.id}\nIf wrong, type correct text below and tap Save Correction."
        transcriptBox.text = result.transcript
    }

    private fun saveSample(
        result: BenchmarkResult,
        wavFile: File,
        idPrefix: String = "sample",
        source: String = "local",
        engine: String = "phone_local",
        serverElapsedMs: Long = -1L,
        engineRtf: Double = result.realtimeFactor(),
        chunkSec: Int = 0,
        chunkCount: Int = 0,
        chunkingEnabled: Boolean = false,
    ): LoggedSample {
        val dir = File(filesDir, "eval_samples")
        val audioDir = File(dir, "audio")
        audioDir.mkdirs()
        val id = "$idPrefix-${System.currentTimeMillis()}"
        val savedWav = File(audioDir, "$id.wav")
        wavFile.copyTo(savedWav, overwrite = true)
        val sample = LoggedSample(
            id = id,
            wavFile = savedWav,
            metaFile = File(dir, "$id.json"),
            modelTranscript = result.transcript,
            audioMs = result.audioMs,
            processingMs = result.processingMs,
            source = source,
            engine = engine,
            serverElapsedMs = serverElapsedMs,
            engineRtf = engineRtf,
            chunkSec = chunkSec,
            chunkCount = chunkCount,
            chunkingEnabled = chunkingEnabled,
        )
        writeSampleMeta(sample, "")
        return sample
    }

    private fun showBenchmarkLog() {
        try {
            val dir = File(filesDir, "eval_samples")
            val files = dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (files.isEmpty()) {
                status.text = "No benchmark recordings yet."
                transcriptBox.text = "Record with PC ASR first, then come back here."
                return
            }
            val df = DecimalFormat("0.00")
            val recent = files.take(30).mapNotNull { file ->
                runCatching { JSONObject(file.readText()) }.getOrNull()
            }
            val byEngine = linkedMapOf<String, MutableList<JSONObject>>()
            for (j in recent) byEngine.getOrPut(j.optString("engine", "unknown")) { mutableListOf() }.add(j)

            val out = StringBuilder()
            out.appendLine("Benchmark Log — latest ${recent.size} recordings")
            out.appendLine()
            out.appendLine("Averages by model:")
            for ((engine, rows) in byEngine) {
                val avgAudio = rows.map { it.optDouble("audio_ms") }.average()
                val avgWords = rows.map { it.optDouble("word_count") }.average()
                val avgRoundtrip = rows.map { it.optDouble("roundtrip_ms", it.optDouble("processing_ms")) }.average()
                val avgServer = rows.map { it.optDouble("server_elapsed_ms", -1.0) }.filter { it >= 0 }.averageOrNull()
                val avgRtf = rows.map { it.optDouble("roundtrip_realtime_factor", it.optDouble("realtime_factor")) }.average()
                out.append("• ").append(engine)
                    .append(" — runs: ").append(rows.size)
                    .append(", avg audio: ").append(df.format(avgAudio / 1000.0)).append("s")
                    .append(", avg words: ").append(df.format(avgWords))
                    .append(", avg roundtrip: ").append(df.format(avgRoundtrip / 1000.0)).append("s")
                if (avgServer != null) out.append(", avg server: ").append(df.format(avgServer / 1000.0)).append("s")
                out.append(", avg RTF: ").append(df.format(avgRtf)).append("x")
                    .appendLine()
            }
            out.appendLine()
            out.appendLine("Recent recordings:")
            for (j in recent) {
                val id = j.optString("id")
                val engine = j.optString("engine", "unknown")
                val words = j.optInt("word_count", 0)
                val audioMs = j.optLong("audio_ms", 0)
                val roundtripMs = j.optLong("roundtrip_ms", j.optLong("processing_ms", 0))
                val serverMs = j.optLong("server_elapsed_ms", -1)
                val rtf = j.optDouble("roundtrip_realtime_factor", j.optDouble("realtime_factor", 0.0))
                val wps = j.optDouble("words_per_second_audio", 0.0)
                val chunking = j.optBoolean("chunking_enabled", false)
                val chunkText = if (chunking) ", chunks: ${j.optInt("chunk_count", 0)}×${j.optInt("chunk_sec", 0)}s" else ""
                out.appendLine("• $engine — $id")
                out.append("  duration: ").append(df.format(audioMs / 1000.0)).append("s")
                    .append(", words: ").append(words)
                    .append(", transcribe/roundtrip: ").append(df.format(roundtripMs / 1000.0)).append("s")
                if (serverMs >= 0) out.append(", server: ").append(df.format(serverMs / 1000.0)).append("s")
                out.append(", RTF: ").append(df.format(rtf)).append("x")
                    .append(", speech rate: ").append(df.format(wps)).append(" w/s")
                    .append(chunkText)
                    .appendLine()
                val text = j.optString("model_transcript").trim()
                if (text.isNotBlank()) out.appendLine("  text: ${text.take(140)}${if (text.length > 140) "…" else ""}")
            }
            status.text = "Showing benchmark log. Lower RTF/roundtrip is faster; higher transcript quality still needs your correction check."
            transcriptBox.text = out.toString()
        } catch (e: Exception) {
            status.text = "Could not read benchmark log. See error box below."
            showError("Benchmark log error", e.message)
        }
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        val values = filter { !it.isNaN() }
        return if (values.isEmpty()) null else values.average()
    }

    private fun clearBenchmarkLog() {
        val now = System.currentTimeMillis()
        if (now > clearLogArmedUntilMs) {
            clearLogArmedUntilMs = now + 5000
            clearBenchmarkLogButton.text = "Tap Again to Clear"
            status.text = "Tap Clear again within 5 seconds to delete previous benchmark logs and saved audio."
            return
        }
        clearError()
        File(filesDir, "eval_samples").deleteRecursively()
        File(filesDir, "export_package").deleteRecursively()
        lastSample = null
        saveCorrectionButton.isEnabled = false
        correctionInput.setText("")
        clearBenchmarkLogButton.text = "Clear Benchmark Log"
        clearLogArmedUntilMs = 0L
        status.text = "Benchmark log cleared. Ready for fresh recordings."
        transcriptBox.text = "Previous benchmark logs, saved transcripts, and benchmark audio were deleted."
    }

    private fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return 0
        val latinWords = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)?").findAll(trimmed).count()
        val cjkChars = Regex("[\\p{IsHan}]").findAll(trimmed).count()
        val kanaChars = Regex("[\\p{InHiragana}\\p{InKatakana}]").findAll(trimmed).count()
        val hangulSyllables = Regex("[\\p{InHangulSyllables}]").findAll(trimmed).count()
        val otherTokenRuns = Regex("[^\\s\\p{Punct}\\p{IsHan}\\p{InHiragana}\\p{InKatakana}\\p{InHangulSyllables}A-Za-z0-9]+")
            .findAll(trimmed)
            .count()
        // For Chinese/Cantonese there are usually no spaces, so count each Han character
        // as one comparable text token instead of returning 1 huge “word”.
        return latinWords + cjkChars + kanaChars + hangulSyllables + otherTokenRuns
    }

    private fun saveCorrection() {
        val sample = lastSample ?: return
        val correct = correctionInput.text.toString().trim()
        writeSampleMeta(sample, correct)
        File(filesDir, "eval_samples/corrections.jsonl").appendText(sampleJson(sample, correct).toString() + "\n")
        status.text = status.text.toString() + "\nCorrection saved ✅"
        saveCorrectionButton.isEnabled = false
    }

    private fun writeSampleMeta(sample: LoggedSample, correctText: String) {
        sample.metaFile.parentFile?.mkdirs()
        sample.metaFile.writeText(sampleJson(sample, correctText).toString(2))
    }

    private fun prepareExportPackage() {
        try {
            val samplesDir = File(filesDir, "eval_samples")
            val exportDir = File(filesDir, "export_package")
            val audioDir = File(exportDir, "audio")
            exportDir.deleteRecursively()
            audioDir.mkdirs()
            val metaFiles = samplesDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
            var count = 0
            val index = StringBuilder()
            index.appendLine("Qwen ASR correction log")
            index.appendLine()
            for (meta in metaFiles) {
                val json = JSONObject(meta.readText())
                val id = json.optString("id", meta.nameWithoutExtension)
                val sourceWav = File(json.optString("audio_path"))
                val wavName = "$id.wav"
                if (sourceWav.exists()) sourceWav.copyTo(File(audioDir, wavName), overwrite = true)
                val modelText = json.optString("model_transcript")
                val corrected = json.optString("correct_text")
                val note = buildString {
                    appendLine("1. voice file name")
                    appendLine(wavName)
                    appendLine()
                    appendLine("2. transcribed text:")
                    appendLine(modelText)
                    appendLine()
                    appendLine("3. corrected text:")
                    appendLine(corrected)
                }
                File(exportDir, "$id.txt").writeText(note)
                index.appendLine("- $id → $wavName / $id.txt")
                count++
            }
            File(exportDir, "INDEX.txt").writeText(index.toString())
            status.text = "Prepared $count samples for PC export.\n\nNow connect USB and run scripts/pull-qwen-asr-logs-from-phone.sh on the PC/WSL."
        } catch (e: Exception) {
            status.text = "Export prepare failed. See error box below."
            showError("Export prepare error", e.message)
        }
    }

    private fun sampleJson(sample: LoggedSample, correctText: String) = JSONObject().apply {
        val wordCount = countWords(sample.modelTranscript)
        val roundtripRtf = if (sample.audioMs <= 0) 0.0 else sample.processingMs.toDouble() / sample.audioMs
        put("id", sample.id)
        put("created_ms", sample.createdMs)
        put("source", sample.source)
        put("engine", sample.engine)
        put("audio_path", sample.wavFile.absolutePath)
        put("model_transcript", sample.modelTranscript)
        put("correct_text", correctText)
        put("word_count", wordCount)
        put("char_count", sample.modelTranscript.length)
        put("audio_ms", sample.audioMs)
        put("recording_duration_ms", sample.audioMs)
        put("processing_ms", sample.processingMs)
        put("roundtrip_ms", sample.processingMs)
        put("server_elapsed_ms", sample.serverElapsedMs)
        put("realtime_factor", sample.engineRtf)
        put("roundtrip_realtime_factor", roundtripRtf)
        put("chunk_sec", sample.chunkSec)
        put("chunk_count", sample.chunkCount)
        put("chunking_enabled", sample.chunkingEnabled)
        put("words_per_second_audio", if (sample.audioMs <= 0) 0.0 else wordCount * 1000.0 / sample.audioMs)
        put("words_per_second_transcribe", if (sample.processingMs <= 0) 0.0 else wordCount * 1000.0 / sample.processingMs)
        put("device_note", "Xiaomi 17U")
    }

    private fun pcPrefs() = getSharedPreferences("pc_asr", Context.MODE_PRIVATE)

    private fun localPrefs() = getSharedPreferences("local_asr", Context.MODE_PRIVATE)

    private fun selectedLocalEngineId(): String {
        val index = if (::localEngineSpinner.isInitialized) localEngineSpinner.selectedItemPosition else 0
        return localEngineOptions.getOrElse(index) { SenseVoiceModelManager.ENGINE_NAME }
    }

    private fun saveLocalEnginePref(engineId: String) {
        localPrefs().edit().putString("engine", engineId).apply()
    }

    private fun createLocalAsrEngine(engineId: String): LocalAsrEngine = when (engineId) {
        SenseVoiceModelManager.ENGINE_NAME -> SenseVoiceAsrEngine(filesDir, modelEngineName = engineId)
        SenseVoiceModelManager.ENGINE_NAME_2024 -> SenseVoiceAsrEngine(filesDir, modelEngineName = engineId)
        "phone_qwen_0_6b" -> QwenRealAsrEngine(filesDir)
        else -> SenseVoiceAsrEngine(filesDir)
    }

    private fun localEngineLabel(engineId: String): String = when (engineId) {
        SenseVoiceModelManager.ENGINE_NAME -> "Local: SenseVoice Yue 2025"
        SenseVoiceModelManager.ENGINE_NAME_2024 -> "Local: SenseVoice 2024"
        "phone_qwen_0_6b" -> "Local: Qwen 0.6B"
        else -> engineId
    }

    private fun downloadSelectedLocalModel() {
        val engineId = selectedLocalEngineId()
        if (engineId == SenseVoiceModelManager.ENGINE_NAME || engineId == SenseVoiceModelManager.ENGINE_NAME_2024) {
            clearError()
            val label = SenseVoiceModelManager.spec(engineId).label
            status.text = "Downloading $label (~228 MB)… keep the app open."
            Thread {
                try {
                    SenseVoiceModelManager.download(filesDir, engineId) { name, index, count, done, total ->
                        runOnUiThread {
                            val progress = if (total > 0L) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
                            status.text = "$label file $index/$count: $name — $progress"
                        }
                    }
                    runOnUiThread {
                        activeLocalEngineId = engineId
                        saveLocalEnginePref(activeLocalEngineId)
                        asrEngine = SenseVoiceAsrEngine(filesDir, modelEngineName = engineId)
                        status.text = "Downloaded $label: ${formatBytes(SenseVoiceModelManager.installedBytes(filesDir, engineId))}. Ready for phone-local ASR."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "$label download failed."
                        showError("Download $label error", e.message)
                    }
                }
            }.start()
            return
        }
        status.text = "This local model has no in-app downloader yet."
    }

    private fun selectedChunkSec(): Int {
        val label = pcChunkSpinner.selectedItem?.toString() ?: "12s"
        return label.removeSuffix("s").toIntOrNull() ?: 12
    }

    private fun savePcPrefs(url: String, token: String, engine: String, chunkSec: Int) {
        pcPrefs().edit()
            .putString("url", url)
            .putString("token", token)
            .putString("engine", engine)
            .putString("chunk_sec", chunkSec.toString())
            .apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object { private const val REQ_AUDIO = 1001 }
}

data class BenchmarkResult(val transcript: String, val audioMs: Long, val processingMs: Long) {
    fun realtimeFactor() = if (audioMs <= 0) 0.0 else processingMs.toDouble() / audioMs
}

data class LoggedSample(
    val id: String,
    val wavFile: File,
    val metaFile: File,
    val modelTranscript: String,
    val audioMs: Long,
    val processingMs: Long,
    val source: String = "local",
    val engine: String = "phone_local",
    val serverElapsedMs: Long = -1L,
    val engineRtf: Double = if (audioMs <= 0) 0.0 else processingMs.toDouble() / audioMs,
    val chunkSec: Int = 0,
    val chunkCount: Int = 0,
    val chunkingEnabled: Boolean = false,
    val createdMs: Long = System.currentTimeMillis(),
) {
    val wordCount: Int get() {
        val trimmed = modelTranscript.trim()
        return if (trimmed.isBlank()) 0 else trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val roundtripRtf: Double get() = if (audioMs <= 0) 0.0 else processingMs.toDouble() / audioMs
}

interface LocalAsrEngine { fun transcribe(wavFile: File, audioMs: Long): BenchmarkResult }

class StubAsrEngine : LocalAsrEngine {
    override fun transcribe(wavFile: File, audioMs: Long): BenchmarkResult {
        val start = System.currentTimeMillis()
        Thread.sleep(min(600L, max(80L, audioMs / 20)))
        val elapsed = System.currentTimeMillis() - start
        return BenchmarkResult("Stub ASR only: recorded $audioMs ms to ${wavFile.name}", audioMs, elapsed)
    }
}

class WavRecorder {
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var outputFile: File? = null
    private var startMs = 0L
    private var stopMs = 0L

    @Throws(IOException::class)
    fun start(file: File) {
        if (running.get()) return
        outputFile = file
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = max(minBuffer, SAMPLE_RATE * 2)
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord failed to initialize")
        recorder = audioRecord
        writeEmptyHeader(file)
        running.set(true)
        startMs = System.currentTimeMillis()
        audioRecord.startRecording()
        thread = Thread({ writeLoop(file, bufferSize) }, "wav-recorder").also { it.start() }
    }

    fun stop(): Recording {
        if (!running.getAndSet(false)) return Recording(outputFile ?: File(""), 0)
        stopMs = System.currentTimeMillis()
        recorder?.stop()
        thread?.join(1500)
        recorder?.release()
        recorder = null
        val file = outputFile ?: File("")
        fixHeader(file)
        return Recording(file, max(0L, stopMs - startMs))
    }

    private fun writeLoop(file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            FileOutputStream(file, true).use { out ->
                while (running.get()) {
                    val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
        } catch (_: IOException) {}
    }

    data class Recording(val file: File, val durationMs: Long)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private fun writeEmptyHeader(file: File) = FileOutputStream(file, false).use { it.write(ByteArray(44)) }

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
            write(value and 0xff); write((value shr 8) and 0xff); write((value shr 16) and 0xff); write((value shr 24) and 0xff)
        }
        private fun RandomAccessFile.writeLeShort(value: Int) {
            write(value and 0xff); write((value shr 8) and 0xff)
        }
    }
}
