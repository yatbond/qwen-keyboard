package ai.qwenkeyboard.benchmark

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QwenVoiceInputMethodService : InputMethodService() {
    private val running = AtomicBoolean(false)
    private var recorder: LiveChunkRecorder? = null
    private var worker: Thread? = null
    private var queue = LinkedBlockingQueue<LiveChunk>()
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var micButton: Button
    private var selectedChunkSec = 5
    private val buffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        status = TextView(this).apply {
            text = "Qwen Voice Keyboard — PC Qwen 1.7B"
            textSize = 13f
            setTextColor(0xFF222222.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        transcript = TextView(this).apply {
            text = "Tap Start Dictation, then speak. Text is inserted into Telegram as chunks return."
            textSize = 15f
            setTextColor(0xFF111111.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val transcriptScroll = ScrollView(this).apply {
            setBackgroundColor(0x11000000)
            addView(transcript, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(transcriptScroll, LinearLayout.LayoutParams(-1, dp(96)).apply { topMargin = dp(6) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        micButton = Button(this).apply {
            text = "Start Dictation"
            textSize = 16f
            setOnClickListener { if (running.get()) stopDictation() else startDictation() }
        }
        row.addView(micButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(Button(this).apply {
            text = "⌫"
            textSize = 20f
            setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        }, LinearLayout.LayoutParams(dp(64), dp(48)).apply { leftMargin = dp(6) })
        row.addView(Button(this).apply {
            text = "⏎"
            textSize = 18f
            setOnClickListener { handleEnterKey() }
        }, LinearLayout.LayoutParams(dp(64), dp(48)).apply { leftMargin = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val chunkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (sec in listOf(2, 3, 5, 7)) {
            chunkRow.addView(Button(this).apply {
                text = "${sec}s"
                textSize = 12f
                setOnClickListener {
                    if (!running.get()) {
                        selectedChunkSec = sec
                        getSharedPreferences("pc_asr", MODE_PRIVATE).edit().putString("chunk_sec", sec.toString()).apply()
                        status.text = "Qwen Voice Keyboard ready — ${sec}s chunks"
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (sec != 2) leftMargin = dp(4) })
        }
        root.addView(chunkRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(this).apply {
            text = "Space"
            setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        row2.addView(Button(this).apply {
            text = "Clear Preview"
            setOnClickListener { buffer.clear(); transcript.text = "" }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
        root.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (::status.isInitialized && !running.get()) {
            selectedChunkSec = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("chunk_sec", "5")?.toIntOrNull() ?: 5
            status.text = "Qwen Voice Keyboard ready — PC Qwen 1.7B, ${selectedChunkSec}s chunks"
        }
    }

    private fun startDictation() {
        if (running.get()) return
        queue = LinkedBlockingQueue()
        buffer.clear()
        transcript.text = ""
        val dir = File(cacheDir, "ime_live_chunks").apply { deleteRecursively(); mkdirs() }
        running.set(true)
        micButton.text = "Stop Dictation"
        selectedChunkSec = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("chunk_sec", selectedChunkSec.toString())?.toIntOrNull() ?: selectedChunkSec
        status.text = "Recording… sending every ${selectedChunkSec}s to PC Qwen 1.7B"
        recorder = LiveChunkRecorder(dir, queue, chunkMs = selectedChunkSec * 1000L).also { it.start() }
        worker = Thread({ transcribeLoop(queue) }, "qwen-ime-pc-asr").also { it.start() }
    }

    private fun stopDictation() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder = null
        micButton.text = "Start Dictation"
        status.text = "Stopping… finishing queued chunks"
    }

    private fun transcribeLoop(runQueue: LinkedBlockingQueue<LiveChunk>) {
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val baseUrl = "https://voice.dee-photography.com"
        val token = prefs.getString("token", "") ?: ""
        val client = PcAsrClient(baseUrl = baseUrl, token = token, engine = "qwen_1_7b", chunkSec = 0)
        val df = DecimalFormat("0.0")
        while (running.get() || runQueue.isNotEmpty()) {
            val chunk = runQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val started = System.currentTimeMillis()
            postUi { status.text = "Transcribing chunk ${chunk.index}…" }
            try {
                val result = client.transcribe(chunk.file)
                val elapsed = System.currentTimeMillis() - started
                val text = result.text.trim()
                if (text.isNotBlank()) {
                    currentInputConnection?.commitText(text + " ", 1)
                    buffer.append(text).append(' ')
                }
                postUi {
                    transcript.text = buffer.toString().ifBlank { "(No speech detected yet)" }
                    status.text = "Inserted chunk ${chunk.index} — server ${result.serverElapsedMs} ms, total ${df.format(elapsed / 1000.0)}s"
                }
            } catch (e: Exception) {
                postUi {
                    status.text = "PC ASR error: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                }
            } finally {
                chunk.file.delete()
            }
        }
        postUi {
            micButton.text = "Start Dictation"
            status.text = "Dictation stopped"
        }
    }

    private fun postUi(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun handleEnterKey() {
        val ic = currentInputConnection ?: return
        val editor = currentInputEditorInfo
        val action = editor?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val inputType = editor?.inputType ?: 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val shouldRunAction = when (action) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO -> true
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE -> !isMultiLine
            else -> false
        }
        if (shouldRunAction && ic.performEditorAction(action)) return
        ic.commitText("\n", 1)
    }

    override fun onFinishInput() {
        if (running.get()) stopDictation()
        super.onFinishInput()
    }

    override fun onDestroy() {
        if (running.get()) stopDictation()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
