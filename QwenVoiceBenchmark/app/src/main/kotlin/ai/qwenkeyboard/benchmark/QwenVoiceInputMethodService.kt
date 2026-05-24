package ai.qwenkeyboard.benchmark

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.icu.text.Transliterator
import android.os.Handler
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.util.AbstractMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class QwenVoiceInputMethodService : InputMethodService() {
    private val running = AtomicBoolean(false)
    private var recorder: LiveChunkRecorder? = null
    private var worker: Thread? = null
    private var queue = LinkedBlockingQueue<LiveChunk>()
    private lateinit var inputRoot: LinearLayout
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var previewInput: EditText
    private lateinit var previewAiButton: Button
    private lateinit var previewAiCandidateInput: EditText
    private lateinit var micButton: Button
    private lateinit var recordButton: Button
    private lateinit var voicePanel: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var handwritingPanel: LinearLayout
    private lateinit var handwritingCandidateRow: LinearLayout
    private lateinit var handwritingPad: HandwritingPadView
    private lateinit var suggestionLeft: Button
    private lateinit var suggestionCenter: Button
    private lateinit var suggestionRight: Button
    private var selectedChunkSec = 5
    private var voiceMode = false
    private var settingsMode = false
    private var handwritingMode = false
    private var handwritingAutoInsert = true
    private var handwritingAutoInsertDelayMs = 900L
    private var lastAutoInsertedHandwriting: String? = null
    private var handwritingRecognizer: DigitalInkRecognizer? = null
    private var handwritingModelTag = ""
    private var handwritingRecognizeRunnable: Runnable? = null
    private var shift = false
    private var symbols = false
    private var showEmoji = false
    private var keyboardLanguageMode = "en" // en, pinyin, jiufang, sucheng
    private var zhMode = "orig"
    private var engineName = "qwen_1_7b"
    private var verboseMode = false
    private var swipeDeleteEnabled = true
    private var holdRepeatEnabled = true
    private var doubleSpaceEnabled = true
    private var autoCorrectEnabled = true
    private var suggestionModeEnabled = true
    private var voiceCleanupEnabled = true
    private var voiceAutoPunctuation = false
    private var voicePunctuationMode = "off" // off, rules, cloud_ai
    private var voiceAiTextCorrectionEnabled = false
    private var alibabaApiKey = ""
    private var alibabaModel = "qwen3.6-plus"
    private var previewModeEnabled = false
    private var displayAwareMode = false
    private var keyboardSizeMode = "normal" // compact, normal, tall
    private var keySpacingMode = "normal" // tight, normal, comfortable
    private var suggestionSizeMode = "normal" // small, normal, large
    private var flowInputEnabled = false
    private var displayBottomBufferDp = 2
    private var splitKeyboardMode = "auto" // off, on, auto
    private var featureGuideExpanded = false
    private var previewAiFixMode = "manual" // off, manual, auto
    private var previewAiFixModel = "rules" // rules or fix_<asr-engine> full-clip re-transcription
    private var repeatDelayMs = 420L
    private var lastSpaceAt = 0L
    private val buffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var didSwipeDelete = false
    private var holdVoiceFromKeyboard = false
    private val wordFreq = HashMap<String, Int>()
    private val learnedFreq = HashMap<String, Int>()
    private val learnedCorrections = HashMap<String, MutableMap<String, Int>>()
    private val learnedNextWords = HashMap<String, MutableMap<String, Int>>()
    private val forgottenWords = HashSet<String>()
    private var combinedWordEntriesCache: List<Map.Entry<String, Int>>? = null
    private var learnedWordsSavePending = false
    private val learnedWordsSaveRunnable = Runnable {
        learnedWordsSavePending = false
        saveLearningPrefsNow()
    }
    private var pendingForgetWord: String? = null
    private var pendingMistakeWord: String? = null
    private var localAsrEngine: LocalAsrEngine? = null
    private var localAsrEngineName: String? = null
    private var voiceStatusText: CharSequence
        get() = if (::status.isInitialized) status.text else ""
        set(value) { updateVoiceStatusPanel(value.toString()) }
    private var dictionaryLoaded = false
    private var clipboardManager: ClipboardManager? = null
    private val clipboardItems = mutableListOf<ClipEntry>()
    private var suppressClipboardCapture = false
    private val previewRawText = StringBuilder()
    private var previewAiFixedText = ""
    private var previewLastInsertedText = ""
    private var previewAiCandidateText = ""
    private var previewEditActive = false
    private val previewAudioChunks = mutableListOf<File>()
    private var previewAudioDir: File? = null

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        SenseVoiceModelManager.cleanupLegacyModelFiles(filesDir)
        loadPrefs()
        setupClipboardWatcher()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), keyboardBottomInsetPx())
            setBackgroundColor(0xFF20242C.toInt())
        }
        inputRoot = root

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            text = ""
            textSize = 8.5f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFFECEFF4.toInt())
            setPadding(dp(2), 0, dp(2), 0)
        }
        val topH = topButtonHeightPx()
        val topButtonSize = topButtonSizePx()
        val topGap = topButtonGapPx()
        topRow.addView(status, LinearLayout.LayoutParams(0, topH, 1f))
        recordButton = recordToggleButton()
        topRow.addView(recordButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(modeButton("⌨", false), LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(modeButton("🎙", true), LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(Button(this).apply {
            text = "✍"
            textSize = 22f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { showHandwriting() }
        }, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(Button(this).apply {
            text = "中"
            textSize = 20f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleChineseKeyboardMode() }
        }, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(Button(this).apply {
            text = "📋"
            textSize = 20f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { showClipboard() }
        }, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { rightMargin = topGap })
        topRow.addView(Button(this).apply {
            text = "⚙"
            textSize = 24f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
        root.addView(topRow, LinearLayout.LayoutParams(-1, if (useLandscapeSplitCenterControls()) 0 else -2))

        voicePanel = buildVoicePanel()
        keyboardPanel = buildKeyboardPanel()
        settingsPanel = buildSettingsPanel()
        clipboardPanel = buildClipboardPanel()
        handwritingPanel = buildHandwritingPanel()
        root.addView(voicePanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(settingsPanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(clipboardPanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(handwritingPanel, LinearLayout.LayoutParams(-1, -2))
        setVoiceMode(false)
        return root
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("pc_asr", MODE_PRIVATE)
        keyboardLanguageMode = p.getString("keyboard_language_mode", "en") ?: "en"
        if (keyboardLanguageMode !in listOf("en", "pinyin", "jiufang", "sucheng")) keyboardLanguageMode = "en"
        zhMode = p.getString("zh_mode", "orig") ?: "orig"
        engineName = p.getString("engine", "qwen_1_7b") ?: "qwen_1_7b"
        selectedChunkSec = (p.getString("chunk_sec", "5")?.toIntOrNull() ?: 5).let { if (it in listOf(3, 5, 7, 10)) it else 5 }
        verboseMode = p.getBoolean("verbose_mode", false)
        swipeDeleteEnabled = p.getBoolean("swipe_delete", true)
        holdRepeatEnabled = p.getBoolean("hold_repeat", true)
        doubleSpaceEnabled = p.getBoolean("double_space_period", true)
        autoCorrectEnabled = p.getBoolean("auto_correct", true)
        suggestionModeEnabled = p.getBoolean("suggestion_mode", true)
        voiceCleanupEnabled = p.getBoolean("voice_cleanup", true)
        voiceAutoPunctuation = p.getBoolean("voice_auto_punctuation", false)
        voicePunctuationMode = p.getString("voice_punctuation_mode", if (voiceAutoPunctuation) "rules" else "off") ?: "off"
        if (voicePunctuationMode == "pc_ai") voicePunctuationMode = "cloud_ai"
        if (voicePunctuationMode !in listOf("off", "rules", "cloud_ai")) voicePunctuationMode = if (voiceAutoPunctuation) "rules" else "off"
        voiceAutoPunctuation = voicePunctuationMode != "off"
        voiceAiTextCorrectionEnabled = p.getBoolean("voice_ai_text_correction", false)
        alibabaApiKey = p.getString("alibaba_modelstudio_api_key", "") ?: ""
        alibabaModel = p.getString("alibaba_modelstudio_model", "qwen3.6-plus") ?: "qwen3.6-plus"
        if (alibabaModel !in listOf("qwen3.6-plus", "qwen3.6-flash")) alibabaModel = "qwen3.6-plus"
        handwritingAutoInsert = p.getBoolean("handwriting_auto_insert", true)
        handwritingAutoInsertDelayMs = p.getLong("handwriting_auto_insert_delay_ms", 900L).coerceIn(300L, 2000L)
        previewModeEnabled = p.getBoolean("preview_mode", false)
        displayAwareMode = p.getBoolean("display_aware_mode", false)
        keyboardSizeMode = p.getString("keyboard_size_mode", "normal") ?: "normal"
        if (keyboardSizeMode !in listOf("compact", "normal", "tall")) keyboardSizeMode = "normal"
        keySpacingMode = p.getString("key_spacing_mode", "normal") ?: "normal"
        if (keySpacingMode !in listOf("tight", "normal", "comfortable")) keySpacingMode = "normal"
        suggestionSizeMode = p.getString("suggestion_size_mode", "normal") ?: "normal"
        if (suggestionSizeMode !in listOf("small", "normal", "large")) suggestionSizeMode = "normal"
        flowInputEnabled = p.getBoolean("flow_input_enabled", false)
        displayBottomBufferDp = p.getInt("display_bottom_buffer_dp", 2).coerceIn(0, 96)
        splitKeyboardMode = p.getString("split_keyboard_mode", "auto") ?: "auto"
        if (splitKeyboardMode !in listOf("off", "on", "auto")) splitKeyboardMode = "auto"
        previewAiFixMode = p.getString("preview_ai_fix_mode", "manual") ?: "manual"
        if (previewAiFixMode !in listOf("off", "manual", "auto")) previewAiFixMode = "manual"
        previewAiFixModel = p.getString("preview_ai_fix_model", "rules") ?: "rules"
        if (previewAiFixModel in listOf("qwen_1_7b", "qwen", "pc_qwen", "pc_qwen_text")) previewAiFixModel = "fix_qwen_1_7b"
        if (previewAiFixModel !in listOf("rules", "fix_qwen_1_7b", "fix_sensevoice_yue_2025", "fix_phone_sensevoice_yue")) previewAiFixModel = "rules"
        repeatDelayMs = p.getLong("repeat_delay_ms", 420L).coerceIn(200L, 1000L)
        if (engineName == "phone_qwen_0_6b" && !QwenModelManager.isInstalled(filesDir)) engineName = "qwen_1_7b"
        if (engineName == SenseVoiceModelManager.ENGINE_NAME && !SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME)) engineName = "qwen_1_7b"
        if (engineName == SenseVoiceModelManager.ENGINE_NAME_2024 && !SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024)) engineName = "qwen_1_7b"
        if (engineName == MoonshineModelManager.ENGINE_BASE && !MoonshineModelManager.isInstalled(filesDir, MoonshineModelManager.ENGINE_BASE)) engineName = "qwen_1_7b"
        if (engineName == ParakeetModelManager.ENGINE_TDT_V3 && !ParakeetModelManager.isInstalled(filesDir, ParakeetModelManager.ENGINE_TDT_V3)) engineName = "qwen_1_7b"
        if (engineName.startsWith("phone_whisper_")) engineName = if (SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME)) SenseVoiceModelManager.ENGINE_NAME else "qwen_1_7b"
        applyBundledLearningSeedIfNeeded()
        loadForgottenWords()
        loadLearnedWords()
        loadLearnedCorrections()
        loadLearnedNextWords()
        loadDictionaryIfNeeded()
    }


    private fun applyBundledLearningSeedIfNeeded() {
        try {
            val json = JSONObject(assets.open("dee_learning_seed.json").bufferedReader().use { it.readText() })
            val version = json.optString("version")
            if (version.isBlank()) return
            val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
            if (prefs.getString("dee_learning_seed_version", "") == version) return
            val seedPrefs = json.optJSONObject("prefs") ?: JSONObject()
            val edit = prefs.edit()
            for (key in listOf("learned_words", "forgotten_words", "learned_corrections", "preview_phrase_corrections")) {
                val seeded = seedPrefs.optString(key, "")
                if (seeded.isBlank()) continue
                val merged = mergePipeList(prefs.getString(key, "").orEmpty(), seeded, if (key == "learned_corrections") 500 else 1000)
                edit.putString(key, merged)
            }
            edit.putString("dee_learning_seed_version", version).apply()
        } catch (_: Throwable) {
            // Asset is optional in developer builds.
        }
    }

    private fun mergePipeList(current: String, seed: String, limit: Int): String {
        val out = LinkedHashSet<String>()
        current.split('|').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
        seed.split('|').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
        return out.toList().takeLast(limit).joinToString("|")
    }

    private fun savePrefs() {
        getSharedPreferences("pc_asr", MODE_PRIVATE).edit()
            .putString("keyboard_language_mode", keyboardLanguageMode)
            .putString("zh_mode", zhMode)
            .putString("engine", engineName)
            .putString("chunk_sec", selectedChunkSec.toString())
            .putBoolean("verbose_mode", verboseMode)
            .putBoolean("swipe_delete", swipeDeleteEnabled)
            .putBoolean("hold_repeat", holdRepeatEnabled)
            .putBoolean("double_space_period", doubleSpaceEnabled)
            .putBoolean("auto_correct", autoCorrectEnabled)
            .putBoolean("suggestion_mode", suggestionModeEnabled)
            .putBoolean("voice_cleanup", voiceCleanupEnabled)
            .putBoolean("voice_auto_punctuation", voiceAutoPunctuation)
            .putString("voice_punctuation_mode", voicePunctuationMode)
            .putBoolean("voice_ai_text_correction", voiceAiTextCorrectionEnabled)
            .putString("alibaba_modelstudio_api_key", alibabaApiKey)
            .putString("alibaba_modelstudio_model", alibabaModel)
            .putBoolean("handwriting_auto_insert", handwritingAutoInsert)
            .putLong("handwriting_auto_insert_delay_ms", handwritingAutoInsertDelayMs.coerceIn(300L, 2000L))
            .putBoolean("preview_mode", previewModeEnabled)
            .putBoolean("display_aware_mode", displayAwareMode)
            .putString("keyboard_size_mode", keyboardSizeMode)
            .putString("key_spacing_mode", keySpacingMode)
            .putString("suggestion_size_mode", suggestionSizeMode)
            .putBoolean("flow_input_enabled", flowInputEnabled)
            .putInt("display_bottom_buffer_dp", displayBottomBufferDp.coerceIn(0, 96))
            .putString("split_keyboard_mode", splitKeyboardMode)
            .putString("preview_ai_fix_mode", previewAiFixMode)
            .putString("preview_ai_fix_model", previewAiFixModel)
            .putLong("repeat_delay_ms", repeatDelayMs)
            .apply()
    }

    private fun modeButton(label: String, targetVoice: Boolean) = Button(this).apply {
        text = label
        textSize = if (displayAwareMode && resources.displayMetrics.widthPixels < dp(390)) 11f else 12f
        isAllCaps = false
        setOnClickListener { setVoiceMode(targetVoice) }
    }

    private fun recordToggleButton() = Button(this).apply {
        text = "●"
        textSize = 22f
        minWidth = 0
        minHeight = 0
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        isAllCaps = false
        setTextColor(0xFFFF3333.toInt())
        setBackgroundColor(0xFF3B414D.toInt())
        setPadding(0, 0, 0, 0)
        setOnClickListener { if (running.get()) stopDictation() else startDictation() }
    }

    private fun showSettings() {
        settingsMode = true
        handwritingMode = false
        voiceMode = false
        updateRootPadding()
        if (::voicePanel.isInitialized) voicePanel.visibility = View.GONE
        if (::keyboardPanel.isInitialized) keyboardPanel.visibility = View.GONE
        if (::clipboardPanel.isInitialized) clipboardPanel.visibility = View.GONE
        if (::handwritingPanel.isInitialized) handwritingPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) {
            val parent = settingsPanel.parent as? LinearLayout
            val index = parent?.indexOfChild(settingsPanel) ?: -1
            parent?.removeView(settingsPanel)
            settingsPanel = buildSettingsPanel()
            if (parent != null && index >= 0) parent.addView(settingsPanel, index, LinearLayout.LayoutParams(-1, -2))
            settingsPanel.visibility = View.VISIBLE
        }
        voiceStatusText = "Keyboard settings"
    }

    private fun setupClipboardWatcher() {
        if (clipboardManager != null) return
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        loadClipboardPrefs()
        clipboardManager?.addPrimaryClipChangedListener {
            if (suppressClipboardCapture) return@addPrimaryClipChangedListener
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) addClipboardText(text, pinned = false)
        }
    }

    private fun showClipboard() {
        settingsMode = true
        handwritingMode = false
        voiceMode = false
        updateRootPadding()
        if (::voicePanel.isInitialized) voicePanel.visibility = View.GONE
        if (::keyboardPanel.isInitialized) keyboardPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        if (::handwritingPanel.isInitialized) handwritingPanel.visibility = View.GONE
        if (::clipboardPanel.isInitialized) {
            val parent = clipboardPanel.parent as? LinearLayout
            val index = parent?.indexOfChild(clipboardPanel) ?: -1
            parent?.removeView(clipboardPanel)
            clipboardPanel = buildClipboardPanel()
            if (parent != null && index >= 0) parent.addView(clipboardPanel, index, LinearLayout.LayoutParams(-1, -2))
            clipboardPanel.visibility = View.VISIBLE
        }
        voiceStatusText = "Clipboard"
    }

    private fun setVoiceMode(enabled: Boolean) {
        settingsMode = false
        handwritingMode = false
        if (!enabled && running.get() && !holdVoiceFromKeyboard) stopDictation()
        voiceMode = enabled
        updateRootPadding()
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        if (::clipboardPanel.isInitialized) clipboardPanel.visibility = View.GONE
        if (::handwritingPanel.isInitialized) handwritingPanel.visibility = View.GONE
        if (::voicePanel.isInitialized) voicePanel.visibility = if (enabled) View.VISIBLE else View.GONE
        if (::keyboardPanel.isInitialized) keyboardPanel.visibility = if (enabled) View.GONE else View.VISIBLE
        setIdleStatus(if (enabled) engineLabel() else if (verboseMode) "Qwen Keyboard" else "")
    }


    private fun toggleChineseKeyboardMode() {
        keyboardLanguageMode = when (keyboardLanguageMode) {
            "en" -> "pinyin"
            "pinyin" -> "jiufang"
            "jiufang" -> "sucheng"
            else -> "en"
        }
        savePrefs()
        settingsMode = false
        handwritingMode = false
        if (running.get() && !holdVoiceFromKeyboard) stopDictation()
        voiceMode = false
        updateRootPadding()
        if (::voicePanel.isInitialized) voicePanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        if (::clipboardPanel.isInitialized) clipboardPanel.visibility = View.GONE
        if (::handwritingPanel.isInitialized) handwritingPanel.visibility = View.GONE
        if (::keyboardPanel.isInitialized) keyboardPanel.visibility = View.VISIBLE
        refreshKeyboardPanel()
        voiceStatusText = when (keyboardLanguageMode) {
            "pinyin" -> "Chinese keyboard: Pinyin (${zhLabel()})"
            "jiufang" -> "Chinese keyboard: 九方 (${zhLabel()})"
            "sucheng" -> "Chinese keyboard: 速成 (${zhLabel()})"
            else -> "English keyboard"
        }
    }

    private fun showHandwriting() {
        settingsMode = false
        handwritingMode = true
        if (running.get() && !holdVoiceFromKeyboard) stopDictation()
        voiceMode = false
        updateRootPadding()
        if (::voicePanel.isInitialized) voicePanel.visibility = View.GONE
        if (::keyboardPanel.isInitialized) keyboardPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        if (::clipboardPanel.isInitialized) clipboardPanel.visibility = View.GONE
        if (::handwritingPanel.isInitialized) handwritingPanel.visibility = View.VISIBLE
        voiceStatusText = "Handwriting: write one Chinese character"
        ensureHandwritingModel()
    }

    private fun buildHandwritingPanel(): LinearLayout {
        val candH = handwritingCandidateHeightPx()
        val candRowH = candH + dp(4)
        val padH = handwritingPadHeightPx()
        val controlH = handwritingControlHeightPx()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        handwritingCandidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 5) {
            handwritingCandidateRow.addView(Button(this).apply {
                text = ""
                textSize = 25f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(2))
                setSingleLine(true)
                setOnClickListener { val t = text.toString(); if (t.isNotBlank()) insertHandwritingCandidate(t) }
            }, LinearLayout.LayoutParams(0, candH, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        root.addView(handwritingCandidateRow, LinearLayout.LayoutParams(-1, candRowH))
        handwritingPad = HandwritingPadView(this) { scheduleHandwritingRecognition() }
        val padRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        padRow.addView(handwritingPad, LinearLayout.LayoutParams(0, padH, 1f).apply { rightMargin = dp(4) })
        val sideControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideControls.addView(handSideButton("⌫", repeatable = true) { deleteOneCharRememberingWord() }, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(2) })
        sideControls.addView(handSideButton("␣", repeatable = true) { handleSpace() }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(2) })
        sideControls.addView(handSideButton("⏎") { handleEnterKey() }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(2) })
        sideControls.addView(handSideButton("。?!") { showPunctuationCandidates() }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        padRow.addView(sideControls, LinearLayout.LayoutParams(dp(62), padH))
        root.addView(padRow, LinearLayout.LayoutParams(-1, padH + dp(4)).apply { topMargin = dp(3) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val padControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        padControls.addView(handBottomButton("⌨", 18f) { setVoiceMode(false) }, handBottomPadParams())
        padControls.addView(handBottomButton("Undo", 12f) { handwritingPad.undoStroke(); scheduleHandwritingRecognition() }, handBottomPadParams())
        controls.addView(padControls, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(4) })
        controls.addView(handBottomButton("Clear", 12f) { clearHandwriting() }, LinearLayout.LayoutParams(dp(62), -1))
        root.addView(controls, LinearLayout.LayoutParams(-1, controlH).apply { topMargin = dp(3) })
        root.visibility = View.GONE
        return root
    }


    private fun handBottomPadParams() = LinearLayout.LayoutParams(0, -1, 1f).apply {
        leftMargin = dp(3)
        rightMargin = dp(3)
    }

    private fun handBottomButton(label: String, size: Float, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = size
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun handSideButton(label: String, repeatable: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 11f else 18f
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        if (repeatable) {
            setOnTouchListener { _, event -> handleRepeatTouch(event, action) }
        } else {
            setOnClickListener { action() }
        }
    }

    private fun showPunctuationCandidates() {
        val punct = if (zhMode == "simp" || zhMode == "trad") listOf("，", "。", "？", "！", "、") else listOf(",", ".", "?", "!", "…")
        setHandwritingCandidates(punct)
        voiceStatusText = "Tap punctuation to insert"
    }

    private fun handwritingLanguageTag(): String = if (zhMode == "simp") "zh-Hans" else "zh-Hant"

    private fun ensureHandwritingModel() {
        val tag = handwritingLanguageTag()
        if (handwritingRecognizer != null && handwritingModelTag == tag) return
        handwritingRecognizer?.close()
        handwritingRecognizer = null
        handwritingModelTag = tag
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        if (identifier == null) {
            voiceStatusText = "Handwriting model unavailable for $tag"
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()
        manager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                handwritingRecognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                if (handwritingMode) voiceStatusText = "Handwriting ready (${if (tag == "zh-Hans") "简中" else "繁中"})"
            }
            .addOnFailureListener { e -> if (handwritingMode) voiceStatusText = "Handwriting model download failed: ${(e.message ?: e.javaClass.simpleName).take(80)}" }
    }

    private fun scheduleHandwritingRecognition() {
        handwritingRecognizeRunnable?.let { mainHandler.removeCallbacks(it) }
        handwritingRecognizeRunnable = Runnable { recognizeHandwriting() }
        mainHandler.postDelayed(handwritingRecognizeRunnable!!, if (handwritingAutoInsert) handwritingAutoInsertDelayMs else 700L)
    }

    private fun recognizeHandwriting() {
        if (!handwritingMode || !::handwritingPad.isInitialized || handwritingPad.isEmpty()) return
        ensureHandwritingModel()
        val recognizer = handwritingRecognizer ?: run { voiceStatusText = "Downloading handwriting model…"; return }
        recognizer.recognize(handwritingPad.toInk())
            .addOnSuccessListener { result ->
                val candidates = result.candidates.map { convertChinese(it.text).take(4) }.filter { it.isNotBlank() }.distinct().take(5)
                setHandwritingCandidates(candidates)
                if (candidates.isNotEmpty() && handwritingAutoInsert) {
                    autoInsertHandwritingCandidate(candidates.first())
                } else if (candidates.isNotEmpty()) {
                    voiceStatusText = "Tap candidate to insert"
                } else {
                    voiceStatusText = "No handwriting candidate"
                }
            }
            .addOnFailureListener { e -> voiceStatusText = "Handwriting failed: ${(e.message ?: e.javaClass.simpleName).take(80)}" }
    }

    private fun setHandwritingCandidates(candidates: List<String>) {
        if (!::handwritingCandidateRow.isInitialized) return
        for (i in 0 until handwritingCandidateRow.childCount) {
            val b = handwritingCandidateRow.getChildAt(i) as Button
            b.text = candidates.getOrNull(i).orEmpty()
        }
    }

    private fun autoInsertHandwritingCandidate(candidate: String) {
        val text = convertChinese(candidate)
        commitText(text)
        lastAutoInsertedHandwriting = text
        handwritingRecognizeRunnable?.let { mainHandler.removeCallbacks(it) }
        handwritingPad.clearPad()
        voiceStatusText = "Auto-inserted: $candidate — tap another candidate to replace"
    }

    private fun insertHandwritingCandidate(candidate: String) {
        val text = convertChinese(candidate)
        val autoText = lastAutoInsertedHandwriting
        val ic = currentInputConnection
        if (autoText != null && ic != null) {
            ic.deleteSurroundingText(autoText.length, 0)
            ic.commitText(text, 1)
            voiceStatusText = "Replaced handwriting: $autoText → $text"
        } else {
            commitText(text)
            voiceStatusText = "Inserted handwriting: $candidate"
        }
        clearHandwriting()
    }

    private fun clearHandwriting() {
        handwritingRecognizeRunnable?.let { mainHandler.removeCallbacks(it) }
        handwritingPad.clearPad()
        setHandwritingCandidates(emptyList())
        lastAutoInsertedHandwriting = null
    }

    private class HandwritingPadView(context: Context, private val onChanged: () -> Unit) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 7f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF; strokeWidth = 1.5f; style = Paint.Style.STROKE }
        private val strokes = mutableListOf<MutableList<Ink.Point>>()
        private var current: MutableList<Ink.Point>? = null
        init { setBackgroundColor(0xFF2B303A.toInt()) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guidePaint)
            canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guidePaint)
            val all = strokes + listOfNotNull(current)
            for (stroke in all) {
                for (i in 1 until stroke.size) canvas.drawLine(stroke[i - 1].x, stroke[i - 1].y, stroke[i].x, stroke[i].y, strokePaint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { current = mutableListOf(point(event)); invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { current?.add(point(event)); invalidate(); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    current?.add(point(event)); current?.takeIf { it.size > 1 }?.let { strokes.add(it) }; current = null; invalidate(); onChanged(); return true
                }
            }
            return true
        }
        private fun point(e: MotionEvent): Ink.Point = Ink.Point.create(e.x, e.y, e.eventTime)
        fun toInk(): Ink {
            val builder = Ink.builder()
            for (stroke in strokes) {
                val sb = Ink.Stroke.builder()
                stroke.forEach { sb.addPoint(it) }
                builder.addStroke(sb.build())
            }
            return builder.build()
        }
        fun isEmpty(): Boolean = strokes.isEmpty()
        fun clearPad() { strokes.clear(); current = null; invalidate() }
        fun undoStroke() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    }

    private fun buildClipboardPanel(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        root.addView(TextView(this).apply {
            text = "Clipboard — pinned + latest 10"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFECEFF4.toInt())
        }, LinearLayout.LayoutParams(-1, dp(34)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = clipboardItems.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.time })
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Copy text from any app, then open this clipboard."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFECEFF4.toInt())
            }, LinearLayout.LayoutParams(-1, dp(46)))
        } else {
            for (entry in items) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(Button(this).apply {
                    text = if (entry.pinned) "★" else "☆"
                    textSize = 16f
                    isAllCaps = false
                    setOnClickListener {
                        entry.pinned = !entry.pinned
                        saveClipboardPrefs()
                        showClipboard()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(42)))
                row.addView(Button(this).apply {
                    text = entry.text.take(40)
                    textSize = 13f
                    isAllCaps = false
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        currentInputConnection?.commitText(entry.text, 1)
                        setVoiceMode(false)
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                list.addView(row)
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, clipboardScrollHeightPx()))
        root.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { setVoiceMode(false) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        return root
    }

    private fun addClipboardText(text: String, pinned: Boolean) {
        val existing = clipboardItems.firstOrNull { it.text == text }
        if (existing != null) {
            existing.time = System.currentTimeMillis()
            if (pinned) existing.pinned = true
        } else {
            clipboardItems.add(ClipEntry(text, pinned, System.currentTimeMillis()))
        }
        trimClipboardItems()
        saveClipboardPrefs()
    }

    private fun trimClipboardItems() {
        val unpinned = clipboardItems.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > 10) {
            val remove = unpinned.drop(10).toSet()
            clipboardItems.removeAll(remove)
        }
    }

    private fun saveClipboardPrefs() {
        trimClipboardItems()
        val encoded = clipboardItems.joinToString("\n") { "${if (it.pinned) 1 else 0}\t${it.time}\t${it.text.replace("\n", " ").replace("\t", " ")}" }
        getSharedPreferences("pc_asr", MODE_PRIVATE).edit().putString("clipboard_items", encoded).apply()
    }

    private fun loadClipboardPrefs() {
        clipboardItems.clear()
        val saved = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("clipboard_items", "") ?: ""
        for (line in saved.lines()) {
            val parts = line.split("\t", limit = 3)
            if (parts.size == 3 && parts[2].isNotBlank()) clipboardItems.add(ClipEntry(parts[2], parts[0] == "1", parts[1].toLongOrNull() ?: 0L))
        }
        trimClipboardItems()
    }

    private data class ClipEntry(var text: String, var pinned: Boolean, var time: Long)

    private fun buildSettingsPanel(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFECEFF4.toInt())
        }, LinearLayout.LayoutParams(-1, dp(34)))

        addSettingsHeader(root, "Help")
        root.addView(Button(this).apply {
            text = if (featureGuideExpanded) "Hide full feature guide" else "Show full feature guide"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { featureGuideExpanded = !featureGuideExpanded; refreshSettingsPanel() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(2); bottomMargin = dp(4) })
        if (featureGuideExpanded) addFeatureGuide(root)

        addSettingsHeader(root, "Chunk length")
        for (rowSecs in listOf(listOf(3, 5, 7, 10))) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (sec in rowSecs) {
                row.addView(Button(this).apply {
                    text = if (selectedChunkSec == sec) "✓ ${sec}s" else "${sec}s"
                    textSize = 12f
                    isAllCaps = false
                    setOnClickListener {
                        selectedChunkSec = sec
                        savePrefs()
                        refreshSettingsPanel()
                        refreshVoicePanel()
                        voiceStatusText = "Chunk length: ${sec}s"
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
            }
            root.addView(row)
        }

        root.addView(settingToggle("Verbose status", verboseMode) { verboseMode = !verboseMode })
        root.addView(settingToggle("Swipe left deletes word", swipeDeleteEnabled) { swipeDeleteEnabled = !swipeDeleteEnabled })
        root.addView(settingToggle("Hold key auto-repeat", holdRepeatEnabled) { holdRepeatEnabled = !holdRepeatEnabled })
        root.addView(settingToggle("Double-space inserts period", doubleSpaceEnabled) { doubleSpaceEnabled = !doubleSpaceEnabled })
        root.addView(settingToggle("Autocorrect closest dictionary word", autoCorrectEnabled) { autoCorrectEnabled = !autoCorrectEnabled })
        root.addView(settingToggle("Show top 3 word suggestions", suggestionModeEnabled) { suggestionModeEnabled = !suggestionModeEnabled })
        root.addView(settingToggle("Voice cleanup", voiceCleanupEnabled) { voiceCleanupEnabled = !voiceCleanupEnabled })
        root.addView(choiceRow("Auto correct: punctuation", listOf(
            "off" to "Off", "rules" to "Offline\nrules", "cloud_ai" to "Cloud AI"
        ), voicePunctuationMode) {
            voicePunctuationMode = it
            voiceAutoPunctuation = it != "off"
        })
        root.addView(choiceRow("Auto correct: text", listOf(
            "off" to "Off", "cloud_ai" to "Cloud AI"
        ), if (voiceAiTextCorrectionEnabled) "cloud_ai" else "off") {
            voiceAiTextCorrectionEnabled = it == "cloud_ai"
        })
        root.addView(choiceRow("Cloud AI model", listOf(
            "qwen3.6-plus" to "Qwen3.6\nPlus",
            "qwen3.6-flash" to "Qwen3.6\nFlash"
        ), alibabaModel) { alibabaModel = it })
        root.addView(Button(this).apply {
            text = if (alibabaApiKey.isBlank()) "Set Alibaba Model Studio key from clipboard" else "Alibaba Model Studio key set ✓ (tap to replace)"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@QwenVoiceInputMethodService)?.toString()?.trim().orEmpty()
                if (clip.length > 20) {
                    alibabaApiKey = clip
                    savePrefs()
                    voiceStatusText = "Alibaba Model Studio key saved"
                    refreshSettingsPanel()
                } else {
                    voiceStatusText = "Copy Alibaba Model Studio API key first"
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(3) })
        root.addView(settingToggle("Handwriting auto-insert best match", handwritingAutoInsert) { handwritingAutoInsert = !handwritingAutoInsert })
        root.addView(choiceRow("Handwrite insert delay", listOf(
            "500" to "500ms", "700" to "700ms", "900" to "900ms", "1200" to "1200ms"
        ), handwritingAutoInsertDelayMs.toString()) {
            handwritingAutoInsertDelayMs = it.toLongOrNull()?.coerceIn(300L, 2000L) ?: 900L
        })

        addSettingsHeader(root, "Display")
        root.addView(settingToggle("Display aware mode", displayAwareMode) { displayAwareMode = !displayAwareMode })
        root.addView(choiceRow("Keyboard height", listOf(
            "compact" to "Compact", "normal" to "Normal", "tall" to "Tall"
        ), keyboardSizeMode) {
            keyboardSizeMode = it
        })
        root.addView(choiceRow("Key spacing", listOf(
            "tight" to "Tight", "normal" to "Normal", "comfortable" to "Comfort"
        ), keySpacingMode) {
            keySpacingMode = it
        })
        root.addView(choiceRow("Suggestion row", listOf(
            "small" to "Small", "normal" to "Normal", "large" to "Large"
        ), suggestionSizeMode) {
            suggestionSizeMode = it
        })
        root.addView(settingToggle("Flow swipe input prototype", flowInputEnabled) { flowInputEnabled = !flowInputEnabled })
        root.addView(choiceRow("Bottom buffer", listOf(
            "2" to "2dp", "12" to "12dp", "24" to "24dp", "36" to "36dp", "48" to "48dp", "64" to "64dp"
        ), displayBottomBufferDp.toString()) {
            displayBottomBufferDp = it.toIntOrNull()?.coerceIn(0, 96) ?: 2
        })
        root.addView(choiceRow("Split keys", listOf(
            "off" to "Off", "on" to "On", "auto" to "Auto\nwide"
        ), splitKeyboardMode) {
            splitKeyboardMode = it
        })
        root.addView(TextView(this).apply {
            text = "Default mode keeps the old fixed layout. Display aware mode uses screen width/height to compact keys and lets this buffer lift the keyboard above navigation bars."
            textSize = 11f
            setTextColor(0xFFB8C0CC.toInt())
            setPadding(dp(8), 0, dp(8), dp(4))
        })

        addSettingsHeader(root, "Preview dictation")
        root.addView(settingToggle("Preview mode (do not insert until Insert)", previewModeEnabled) {
            previewModeEnabled = !previewModeEnabled
            if (!previewModeEnabled) previewAiFixedText = ""
        })
        root.addView(choiceRow("AI Fix", listOf("off" to "Off", "manual" to "Manual", "auto" to "Auto"), previewAiFixMode) {
            previewAiFixMode = it
            if (previewAiFixMode == "off") previewAiFixedText = ""
        })
        root.addView(choiceRow("Fix source", listOf(
            "rules" to "Rules",
            "fix_qwen_1_7b" to "PC Qwen\n1.7",
            "fix_sensevoice_yue_2025" to "PC Sense\nYue",
            "fix_phone_sensevoice_yue" to "Local Sense\nYue"
        ), previewAiFixModel) {
            previewAiFixModel = it
        })

        addSettingsHeader(root, "Learning library")
        root.addView(Button(this).apply {
            text = "Upload learning to Dee library"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { uploadLearningLibrary() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(4); bottomMargin = dp(4) })
        root.addView(TextView(this).apply {
            text = "Manual opt-in: sends learned corrections + Preview correction logs to your private PC tunnel for future builds."
            textSize = 11f
            setTextColor(0xFFB8C0CC.toInt())
            setPadding(dp(8), 0, dp(8), dp(4))
        })

        val delayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        delayRow.addView(TextView(this).apply {
            text = "Repeat delay: ${repeatDelayMs}ms"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFFECEFF4.toInt())
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        for (ms in listOf(300L, 420L, 600L)) {
            delayRow.addView(Button(this).apply {
                text = "${ms}ms"
                textSize = 11f
                isAllCaps = false
                setOnClickListener { repeatDelayMs = ms; savePrefs(); refreshSettingsPanel() }
            }, LinearLayout.LayoutParams(dp(72), dp(38)).apply { leftMargin = dp(4) })
        }
        root.addView(delayRow)

        addSettingsHeader(root, "Installed local models (tap Delete to remove)")
        val installedLocal = availableLocalEngineNames()
        if (installedLocal.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No local model installed yet. Download one below before selecting phone-local dictation."
                textSize = 12f
                setTextColor(0xFFB8C0CC.toInt())
                setPadding(dp(6), 0, dp(6), dp(4))
            }, LinearLayout.LayoutParams(-1, dp(44)))
        } else {
            for (model in installedLocal) root.addView(localModelManageRow(model))
        }

        addSettingsHeader(root, "Download local models")
        root.addView(Button(this).apply {
            text = if (SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME)) "✓ Local: SenseVoice Yue 2025 downloaded (${formatBytes(SenseVoiceModelManager.installedBytes(filesDir, SenseVoiceModelManager.ENGINE_NAME))})" else "Download Local: SenseVoice Yue 2025 (~227 MB)"
            textSize = 12f
            isSingleLine = false
            isAllCaps = false
            setOnClickListener { downloadSenseVoiceModelFromKeyboard(SenseVoiceModelManager.ENGINE_NAME) }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        root.addView(Button(this).apply {
            text = if (SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024)) "✓ Local: SenseVoice 2024 downloaded (${formatBytes(SenseVoiceModelManager.installedBytes(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024))})" else "Download Local: SenseVoice 2024 (~228 MB)"
            textSize = 12f
            isSingleLine = false
            isAllCaps = false
            setOnClickListener { downloadSenseVoiceModelFromKeyboard(SenseVoiceModelManager.ENGINE_NAME_2024) }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        root.addView(Button(this).apply {
            text = if (MoonshineModelManager.isInstalled(filesDir, MoonshineModelManager.ENGINE_BASE)) "✓ Local: Moonshine Base EN downloaded (${formatBytes(MoonshineModelManager.installedBytes(filesDir, MoonshineModelManager.ENGINE_BASE))})" else "Download Local: Moonshine Base EN (~288 MB)"
            textSize = 12f
            isSingleLine = false
            isAllCaps = false
            setOnClickListener { downloadMoonshineModelFromKeyboard(MoonshineModelManager.ENGINE_BASE) }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        root.addView(Button(this).apply {
            text = if (ParakeetModelManager.isInstalled(filesDir, ParakeetModelManager.ENGINE_TDT_V3)) "✓ Local: Parakeet TDT downloaded (${formatBytes(ParakeetModelManager.installedBytes(filesDir, ParakeetModelManager.ENGINE_TDT_V3))})" else "Download Local: Parakeet TDT 0.6B (~671 MB)"
            textSize = 12f
            isSingleLine = false
            isAllCaps = false
            setOnClickListener { downloadParakeetModelFromKeyboard(ParakeetModelManager.ENGINE_TDT_V3) }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        root.addView(Button(this).apply {
            text = if (QwenModelManager.isInstalled(filesDir)) "✓ Local: Qwen 0.6B downloaded (${formatBytes(QwenModelManager.installedBytes(filesDir))})" else "Download Local: Qwen 0.6B (~2.4 GB)"
            textSize = 12f
            isSingleLine = false
            isAllCaps = false
            setOnClickListener { downloadQwenModelFromKeyboard() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })

        addSettingsHeader(root, "PC/server models")
        for (rowModels in listOf(listOf("sensevoice_yue_2025"), listOf("qwen_0_6b", "qwen_1_7b"))) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (model in rowModels) {
                row.addView(Button(this).apply {
                    text = if (engineName == model) "✓ ${modelDisplay(model)}" else modelDisplay(model)
                    textSize = 11f
                    isAllCaps = false
                    setOnClickListener { engineName = model; savePrefs(); refreshSettingsPanel(); refreshVoicePanel() }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
            }
            root.addView(row)
        }

        root.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { savePrefs(); setVoiceMode(voiceMode) }
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        outer.addView(scroll, LinearLayout.LayoutParams(-1, settingsPanelHeightPx()))
        return outer
    }

    private fun addSettingsHeader(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFFECEFF4.toInt())
            setPadding(dp(4), dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(30)))
    }

    private fun addFeatureGuide(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = fullFeatureGuideText()
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setTextColor(0xFFDCE3EE.toInt())
            setBackgroundColor(0xFF2B303A.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(6) })
    }

    private fun fullFeatureGuideText(): String = """
Dee Keyboard full feature guide

1) Main buttons and modes
• ⌨ Keyboard: returns to the normal typing keyboard.
• 🎙 Voice: opens dictation controls.
• ✍ Handwriting: opens the Chinese handwriting pad.
• 📋 Clipboard: opens recent/pinned clipboard text.
• ⚙ Settings: opens all options and this guide.
• Red record button: quick voice record toggle from the top row.

2) Normal keyboard typing
• Tap letters normally to type.
• ⇧ toggles capital letters.
• ?!, switches between letters and symbol layout.
• 😊 opens emoji layout.
• Space inserts a space and also finalizes/learns the current word.
• ⌫ deletes one character. If text is selected, it tries to delete the selected text first.
• Enter is smart: in YouTube/browser/search boxes it runs Search/Go; in WhatsApp/Telegram multiline message boxes it inserts a new line.

3) Word suggestions / recommendations
• The top suggestion row shows up to 3 recommendations based on what you are typing.
• Current-word suggestions come from built-in dictionary words, learned words, learned corrections, contractions, typo-distance matching and prefix matching.
• Tap a current-word correction to replace the current word. The keyboard adds a trailing space after inserting the suggestion.
• Next-word predictions also appear in the same recommendation bar. Example: type “how” and the bar can show “are / to / is”.
• Tapping a next-word prediction appends it after the current word instead of replacing the current word. Example: “how” + tap “are” → “how are ”.
• Predictions are never inserted automatically. You must tap the recommendation yourself.
• When you tap a correction, the keyboard learns “typed word → chosen word”. Example: type “teh”, tap “the”; later “teh” strongly recommends “the”.

4) Autocorrect closest dictionary word
• If enabled, pressing Space checks the current word before inserting the space.
• The keyboard first checks common contractions/corrections, then learned corrections, then close dictionary matches.
• If it finds a strong match, it replaces the typed word before the space is inserted.
• It tries to preserve capitalization. Example: “Teh” can become “The”.
• If the word is already known or learned, it normally leaves it alone.
• If autocorrect annoys you, turn off “Autocorrect closest dictionary word”. Suggestions will still be available if enabled.

5) Learning library — how words are learned
• A new typed word is learned when you finish it with Space.
• A new typed word is also learned when you press Enter after it.
• A word must be at least 2 characters and contain a letter to be learned.
• Learned words are boosted heavily in suggestions, so names, project terms and your common vocabulary appear more often.
• Selecting a suggestion also learns the selected word.
• Voice/preview inserted text can learn multiple words from the final inserted text.
• Next-word prediction learns local word pairs and short phrases, such as “thank → you” and “how are → you”.
• Next-word learning is stored locally in app preferences as small frequency counts.
• Learning saves are debounced for responsiveness, then flushed when the keyboard closes.
• The app keeps the strongest recent learned words/pairs, not an unlimited forever list.

6) Learning corrections — how correction memory works
• When you replace the current word by tapping a suggestion, the app records “wrong word → chosen word”.
• Those correction pairs are used before generic dictionary suggestions.
• Example: if you type “qwenkeybord” and tap “qwenkeyboard”, the app remembers that correction.
• Correction learning also boosts the correct word in the learned-word library.
• Some built-in dictionary words are protected from being overwritten as “wrong” unless they were personally learned.

7) Forgetting suggestions / words
• To forget a suggested word, long-press that word in the top suggestion row.
• The keyboard asks for confirmation first: “Forget?” / “Yes: word” / “Cancel”.
• Tap Yes to remove the word from learned words and learned corrections.
• The word is also added to the forgotten list, so it should stop appearing in recommendations/autocorrect.
• Forgotten words are saved locally and remembered across restarts.
• Use this when the keyboard keeps recommending a wrong name, typo, or word you do not want.
• Currently forgetting is done from the suggestion row; there is no separate forgotten-word manager screen yet.

8) Swipe / repeat / punctuation typing
• Swipe left deletes word: swipe left across the keyboard to delete the previous word quickly.
• Hold key auto-repeat: long-press letters, numbers, Space or Backspace to repeat them.
• Repeat delay controls how long you must hold before repeat starts: 300ms, 420ms, or 600ms.
• Double-space inserts period: pressing Space twice quickly changes the previous space into “. ” unless the sentence already ends with punctuation.
• Long-press symbol hints: some letter keys show a small symbol hint; hold the key to enter that symbol.

9) Voice dictation modes
• Direct mode: transcribed text is inserted directly into the target app as each audio chunk returns.
• Preview mode: transcribed text stays inside a preview editor first. You can inspect/fix/edit before inserting.
• Start Dictation begins recording. Stop Dictation stops recording and finishes queued chunks.
• The red ● button can also start/stop dictation quickly from the keyboard controls.
• Chunk length controls how often audio is sent for transcription: 3s, 5s, 7s, or 10s.
• Shorter chunks feel faster but may have less context. Longer chunks can improve context but feel slower.
• Voice text is cleaned, converted and optionally punctuated according to the selected voice cleanup, punctuation and Chinese mode settings.
• The status box shows source/model/chunk, e.g. “PC / Qwen_1.7B / 7s”; in landscape split mode this becomes a compact one-line status.

10) Voice cleanup, punctuation and Cloud AI
• Voice cleanup removes obvious duplicate fragments, repeated chunk overlap and common ASR cleanup artifacts.
• Auto correct: punctuation has Off, Offline rules and Cloud AI.
• Offline rules are local and always available, but only do lightweight punctuation.
• Cloud AI now uses Alibaba Cloud Model Studio / Qwen directly from the phone.
• Paste your Alibaba Model Studio API key into the clipboard, then tap “Set Alibaba Model Studio key from clipboard”. The key stays on the phone in app preferences.
• Cloud AI model choices are Qwen3.6 Plus and Qwen3.6 Flash for the Hong Kong workspace. Plus is better quality; Flash is faster/cheaper.
• Auto correct: text uses Cloud AI to fix obvious ASR word mistakes, capitalization and Chinese character mistakes after dictation stops. It should preserve spoken fillers such as um/uh/ah/ar rather than making the sentence overly clean.
• If Alibaba Cloud AI fails or no key is set, the keyboard shows a status message and falls back to offline cleanup/punctuation instead of silently doing nothing.
• If you want exact raw ASR output, turn punctuation/text correction off.
• If dictated text feels too messy or repetitive, keep Voice cleanup on.

11) Preview dictation and AI Fix
• Preview mode means dictation does not immediately type into WhatsApp/Telegram/etc.
• You can edit preview text manually before inserting.
• AI Fix Off: no fix is applied.
• AI Fix Manual: you decide when to run a cleanup/fix.
• AI Fix Auto: the keyboard tries to fix the preview automatically after transcription.
• Fix source “Rules” uses lightweight local rules.
• Fix source “PC Qwen 1.7” uses the private PC/server Qwen route when available.
• Fix source “PC Sense Yue” uses the PC/server SenseVoice Yue route when available.
• Fix source “Local Sense Yue” uses the phone-local SenseVoice route when installed/available.
• Preview correction logs can be used for future learning upload if you press the upload button.

12) ASR model choices
• PC/server models use Dee’s private server/tunnel and require network access.
• qwen_1_7b / qwen_0_6b are Qwen ASR routes.
• sensevoice_yue_2025 is tuned for Cantonese/Yue-style speech.
• Local models run on the phone after downloading the model files.
• Local Moonshine Base is the recommended fast local English dictation mode.
• Local Parakeet TDT is the heavier/more accurate local English dictation mode.
• Local SenseVoice models are smaller Cantonese/Chinese-capable local options.
• Local Qwen 0.6B is available after downloading a larger model, but is still experimental/heavy.
• If a selected local model is missing or fails, the app falls back where possible instead of leaving dictation blank.

13) Downloading / deleting local models
• “Installed local models” shows models already on the phone and their approximate size.
• Tap a model row to select it.
• Tap Delete to remove that local model from phone storage.
• If you delete the selected model, the keyboard switches back to PC Qwen 1.7B.
• Download buttons fetch local model files; keep the app open and use Wi‑Fi for large downloads.

14) Handwriting mode
• Write one Chinese character on the pad.
• After a short pause, ML Kit returns candidate characters in the candidate row.
• Tap a candidate to insert it.
• “Handwriting auto-insert best match” inserts the top candidate automatically after a short pause.
• “Handwrite insert delay” controls that pause: 500ms, 700ms, 900ms or 1200ms. Use shorter for speed, longer if you need more time to finish strokes.
• If auto-insert is wrong, tap another visible candidate; it replaces the auto-inserted character instead of adding a duplicate.
• Undo removes the last stroke.
• Clear clears the pad and candidate row.
• ⌫ deletes one character in the target app.
• ␣ inserts a space.
• ⏎ uses the same smart Enter behavior as the keyboard.
• 。?! shows punctuation choices in the candidate row.
• The handwriting model follows Chinese mode: 简中 uses zh-Hans; 原文/繁中 use zh-Hant.

15) Clipboard panel
• Clipboard shows pinned items first, then latest copied items.
• Copy text from any app, then open 📋 to see it.
• Tap an item to paste it into the current text field.
• Tap ☆ / ★ to pin or unpin an item.
• The clipboard keeps a limited recent list, not every clipboard forever.

16) Display aware mode, sizing and split keyboard
• Default mode keeps the older fixed layout.
• Display aware mode adapts keyboard/menu heights for different screen sizes.
• Keyboard height has Compact, Normal and Tall. Normal is the baseline; Compact is visibly shorter and Tall is visibly taller.
• Key rows scale to fill the selected keyboard height, so custom sizing should not create a large dead black area at the bottom.
• Key spacing changes the gap between keys: Tight, Normal or Comfortable.
• Suggestion bar size changes recommendation row height: Small, Normal or Large.
• It also reserves safe bottom space for gesture/3-button navigation bars.
• Bottom buffer manually lifts the UI higher. Increase it if bottom buttons are covered; lower it if there is too much space below the keys.
• Try 0dp, 12dp, 24dp, 36dp or 48dp depending on the phone/navigation bar.
• Split keys controls split keyboard behavior: Off, On, or Auto wide.
• Split keyboard applies only to the normal typing keyboard, not handwriting, voice, clipboard or settings panels.
• In landscape split mode, the keyboard disables Android fullscreen extract mode so the target app remains visible.
• In landscape split mode, the voice status and buttons move into the middle gap between the split keys to save vertical space.
• Landscape suggestion/top rows are thinner and key labels are smaller to keep the app visible.

17) Chinese text mode
• 原文 keeps text mostly as produced by typing/ASR.
• 繁中 converts supported Chinese text toward Traditional Chinese, then applies Cantonese-style Traditional post-processing for common phrases.
• Cantonese Traditional examples: “不是” → “唔係”, “沒有” → “冇”, “為甚麼” → “點解”, “這個” → “呢個”, “的” → “嘅”, sentence-final “了” → “咗”.
• 简中 converts supported Chinese text toward Simplified Chinese only. It does not apply the Cantonese Traditional translation.
• This affects dictated/committed text, preview AI candidates and handwriting candidate conversion where supported.

18) Flow swipe input prototype
• Flow swipe input is experimental and English keyboard only.
• Enable “Flow swipe input prototype” in Settings.
• Swipe across letter keys to draw a visible cyan trail.
• High-confidence words commit automatically.
• Uncertain matches show candidates in the recommendation bar instead of inserting a bad short fragment.
• Flow uses both the crossed-letter sequence and geometry/path scoring, so diagonal swipes are judged by distance to intended key centers.
• If there is no good match, it shows a “Flow: no match” status and does not type the first random key.

19) Learning upload
• “Upload learning to Dee library” is manual opt-in.
• It sends learned words, learned corrections and preview correction logs to Dee’s private PC tunnel/library.
• Purpose: future builds can include better personalized correction seeds.
• It does not run just because learning is enabled; you must press the upload button.
• Do not press it if you do not want your learned correction data exported.

20) Status / debugging
• Verbose status shows more detail about model, mode, chunks and processing.
• Short status keeps the top status area quieter.
• The idle status box shows voice source, model and chunk duration.
• Short transient messages such as “Confirm forget”, “Forgot”, download errors or ASR errors can temporarily replace the idle status.
• Error messages are shown in the status line when ASR/model/download operations fail.

21) Practical tips
• For normal chat typing, keep suggestions + learning on, and autocorrect on only if it helps you.
• For names/project terms, type the word then press Space or Enter once; it will be learned.
• For next-word prediction, type naturally for a while; the keyboard learns your common phrase flow locally.
• If a bad word appears, long-press it in suggestions and confirm Yes to forget it.
• For search bars, Enter should search. For chat boxes, Enter should create a new line.
• If UI is covered, enable Display aware mode and increase Bottom buffer.
• For landscape, use Split keys Auto wide or On for the compact split layout with center controls.
• If handwriting auto-insert feels too fast/wrong, turn off auto-insert and choose candidates manually.
""".trimIndent()

    private fun localModelManageRow(model: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "${if (engineName == model) "✓ " else ""}${modelDisplay(model)}\n${localModelSizeLabel(model)}"
            textSize = 10f
            isAllCaps = false
            setOnClickListener {
                engineName = model
                localAsrEngine = null
                localAsrEngineName = null
                savePrefs()
                refreshSettingsPanel()
                refreshVoicePanel()
                voiceStatusText = "Selected ${modelDisplay(model)}"
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        row.addView(Button(this).apply {
            text = "Delete"
            textSize = 11f
            isAllCaps = false
            setTextColor(0xFFFFCCCC.toInt())
            setOnClickListener { deleteLocalModel(model) }
        }, LinearLayout.LayoutParams(dp(86), dp(48)))
        return row
    }

    private fun localModelSizeLabel(model: String): String = when {
        model == SenseVoiceModelManager.ENGINE_NAME || model == SenseVoiceModelManager.ENGINE_NAME_2024 -> formatBytes(SenseVoiceModelManager.installedBytes(filesDir, model))
        model == MoonshineModelManager.ENGINE_BASE -> formatBytes(MoonshineModelManager.installedBytes(filesDir, model))
        model == ParakeetModelManager.ENGINE_TDT_V3 -> formatBytes(ParakeetModelManager.installedBytes(filesDir, model))
        model == "phone_qwen_0_6b" -> formatBytes(QwenModelManager.installedBytes(filesDir))
        else -> "installed"
    }

    private fun deleteLocalModel(model: String) {
        if (running.get()) {
            voiceStatusText = "Stop dictation before deleting models."
            return
        }
        val ok = when {
            model == SenseVoiceModelManager.ENGINE_NAME || model == SenseVoiceModelManager.ENGINE_NAME_2024 -> SenseVoiceModelManager.delete(filesDir, model)
            model == MoonshineModelManager.ENGINE_BASE -> MoonshineModelManager.delete(filesDir, model)
            model == ParakeetModelManager.ENGINE_TDT_V3 -> ParakeetModelManager.delete(filesDir, model)
            model == "phone_qwen_0_6b" -> QwenModelManager.delete(filesDir)
            else -> false
        }
        if (engineName == model) engineName = "qwen_1_7b"
        localAsrEngine = null
        localAsrEngineName = null
        savePrefs()
        voiceStatusText = if (ok) "Deleted ${modelDisplay(model)}. Switched to PC:Qwen1.7b." else "Could not delete ${modelDisplay(model)}."
        refreshSettingsPanel()
        refreshVoicePanel()
    }

    private fun refreshSettingsPanel() {
        if (!::settingsPanel.isInitialized) return
        val parent = settingsPanel.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(settingsPanel)
        val oldScrollY = settingsScrollView(settingsPanel)?.scrollY ?: 0
        parent.removeView(settingsPanel)
        settingsPanel = buildSettingsPanel()
        parent.addView(settingsPanel, index, LinearLayout.LayoutParams(-1, -2))
        settingsPanel.visibility = View.VISIBLE
        settingsScrollView(settingsPanel)?.post { settingsScrollView(settingsPanel)?.scrollTo(0, oldScrollY) }
    }

    private fun settingsScrollView(panel: LinearLayout): ScrollView? = panel.getChildAt(0) as? ScrollView

    private fun settingToggle(label: String, enabled: Boolean, flip: () -> Unit): View {
        return Button(this).apply {
            text = "${if (enabled) "☑" else "☐"} $label"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            isAllCaps = false
            setOnClickListener {
                flip()
                savePrefs()
                refreshSettingsPanel()
                refreshVoicePanel()
                refreshKeyboardPanel()
                updateRootPadding()
            }
        }
    }

    private fun choiceRow(label: String, options: List<Pair<String, String>>, selected: String, choose: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFFECEFF4.toInt())
            setPadding(dp(2), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(dp(72), dp(54)))
        for ((value, title) in options) {
            row.addView(Button(this).apply {
                val twoLineTitle = if (title.contains('\n')) title else "$title\n "
                text = if (selected == value) "✓ $twoLineTitle" else "  $twoLineTitle"
                textSize = if (title.length >= 8 || title.contains('\n')) 9.0f else 10.2f
                maxLines = 2
                isSingleLine = false
                includeFontPadding = false
                minHeight = 0
                minWidth = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                isAllCaps = false
                setOnClickListener {
                    choose(value)
                    savePrefs()
                    refreshSettingsPanel()
                    refreshVoicePanel()
                    refreshKeyboardPanel()
                    updateRootPadding()
                }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        return row
    }

    private fun downloadSenseVoiceModelFromKeyboard(model: String = SenseVoiceModelManager.ENGINE_NAME) {
        val label = SenseVoiceModelManager.spec(model).label
        voiceStatusText = "Downloading $label local model (~228 MB)… keep keyboard open."
        Thread {
            try {
                SenseVoiceModelManager.download(filesDir, model) { name, index, count, done, total ->
                    mainHandler.post {
                        val progress = if (total > 0L) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
                        voiceStatusText = "$label file $index/$count: $name — $progress"
                    }
                }
                mainHandler.post {
                    localAsrEngine = null
                    localAsrEngineName = null
                    engineName = model
                    savePrefs()
                    voiceStatusText = "Downloaded and selected local $label."
                    refreshSettingsPanel()
                    refreshVoicePanel()
                }
            } catch (e: Exception) {
                mainHandler.post { voiceStatusText = "$label download failed: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun downloadMoonshineModelFromKeyboard(model: String = MoonshineModelManager.ENGINE_BASE) {
        val spec = MoonshineModelManager.spec(model)
        voiceStatusText = "Downloading ${spec.label} local model (~288 MB)… keep keyboard open."
        Thread {
            try {
                MoonshineModelManager.download(filesDir, model) { name, index, count, done, total ->
                    mainHandler.post {
                        val progress = if (total > 0L) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
                        voiceStatusText = "Moonshine file $index/$count: $name — $progress"
                    }
                }
                mainHandler.post {
                    localAsrEngine = null
                    localAsrEngineName = null
                    engineName = model
                    savePrefs()
                    voiceStatusText = "Downloaded and selected local ${spec.label}."
                    refreshSettingsPanel()
                    refreshVoicePanel()
                }
            } catch (e: Exception) {
                mainHandler.post { voiceStatusText = "Moonshine download failed: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun downloadParakeetModelFromKeyboard(model: String = ParakeetModelManager.ENGINE_TDT_V3) {
        val spec = ParakeetModelManager.spec(model)
        voiceStatusText = "Downloading ${spec.label} local model (~671 MB)… keep keyboard open and on Wi‑Fi."
        Thread {
            try {
                ParakeetModelManager.download(filesDir, model) { name, index, count, done, total ->
                    mainHandler.post {
                        val progress = if (total > 0L) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
                        voiceStatusText = "Parakeet file $index/$count: $name — $progress"
                    }
                }
                mainHandler.post {
                    localAsrEngine = null
                    localAsrEngineName = null
                    engineName = model
                    savePrefs()
                    voiceStatusText = "Downloaded and selected local ${spec.label}."
                    refreshSettingsPanel()
                    refreshVoicePanel()
                }
            } catch (e: Exception) {
                mainHandler.post { voiceStatusText = "Parakeet download failed: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun downloadQwenModelFromKeyboard() {
        voiceStatusText = "Downloading Qwen 0.6B local model (~2.4 GB)… keep app open and on Wi‑Fi."
        Thread {
            try {
                QwenModelManager.download(filesDir) { name, index, count, done, total ->
                    mainHandler.post {
                        val progress = if (total > 0L) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
                        voiceStatusText = "Qwen 0.6B file $index/$count: $name — $progress"
                    }
                }
                mainHandler.post {
                    localAsrEngine = null
                    localAsrEngineName = null
                    engineName = "phone_qwen_0_6b"
                    savePrefs()
                    voiceStatusText = "Downloaded and selected local Qwen 0.6B."
                    refreshSettingsPanel()
                    refreshVoicePanel()
                }
            } catch (e: Exception) {
                mainHandler.post { voiceStatusText = "Qwen download failed: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun buildVoicePanel(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(4)) }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        if (::status.isInitialized && !running.get()) voiceStatusText = engineLabel()

        root.addView(TextView(this).apply {
            text = "${if (previewModeEnabled) "Preview" else "Direct"} • ${shortModelDisplay(engineName)} • ${selectedChunkSec}s"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFFECEFF4.toInt())
            setPadding(dp(8), dp(2), dp(8), 0)
        }, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(2) })

        if (previewModeEnabled) addPreviewEditor(root)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        micButton = Button(this).apply {
            text = "Start Dictation"
            textSize = 16f
            isAllCaps = false
            setOnClickListener { if (running.get()) stopDictation() else startDictation() }
        }
        row.addView(micButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        row.addView(keyButton("⌫", weight = 0f, width = dp(54), repeatable = true) { deleteOneCharRememberingWord() })
        row.addView(keyButton("⏎", weight = 0f, width = dp(54)) { handleEnterKey() })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val modelScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val voiceModels = availableLocalEngineNames() + listOf("sensevoice_yue_2025", "qwen_0_6b", "qwen_1_7b")
        for (model in voiceModels) {
            modelRow.addView(engineButton(model), LinearLayout.LayoutParams(dp(112), dp(48)).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        modelScroll.addView(modelRow, ViewGroup.LayoutParams(-2, -2))
        root.addView(modelScroll, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(3) })

        val chunkScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chunkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (sec in listOf(3, 5, 7, 10)) {
            chunkRow.addView(Button(this).apply {
                text = if (selectedChunkSec == sec) "✓ ${sec}s" else "${sec}s"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    if (!running.get()) {
                        selectedChunkSec = sec
                        savePrefs()
                        refreshVoicePanel()
                        setIdleStatus(engineLabel())
                    }
                }
            }, LinearLayout.LayoutParams(dp(60), dp(34)).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        chunkScroll.addView(chunkRow, ViewGroup.LayoutParams(-2, -2))
        root.addView(chunkScroll, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(3) })

        val zhRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zhRow.addView(optionButton("原文", "orig"), LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        zhRow.addView(optionButton("繁中", "trad"), LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        zhRow.addView(optionButton("简中", "simp"), LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        root.addView(zhRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        transcript = TextView(this).apply {
            text = "Tap Start Dictation, then speak."
            textSize = 13f
            setTextColor(0xFFECEFF4.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        // Do not add a duplicate transcript/preview box here. The dictated text
        // is visible in the target app (Direct mode) or the main Preview editor.
        outer.addView(scroll, LinearLayout.LayoutParams(-1, voicePanelHeightPx()))
        return outer
    }

    private fun addCompactPreviewEditor(root: LinearLayout) {
        previewInput = EditText(this).apply {
            setText(previewLastInsertedText)
            hint = "Main preview — edit then Insert"
            textSize = 12f
            minLines = 1
            maxLines = 2
            setSingleLine(false)
            setTextColor(0xFFF7F7F7.toInt())
            setHintTextColor(0xFFB7C0CC.toInt())
            setBackgroundColor(0xFF46505E.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnFocusChangeListener { _, hasFocus -> previewEditActive = hasFocus; if (hasFocus) voiceStatusText = "Editing main preview" }
        }
        root.addView(previewInput, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })
        addAiCandidateBox(root, compact = true)

        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewAiButton = Button(this).apply {
            text = "AI"
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            includeFontPadding = false
            isEnabled = previewAiFixMode != "off"
            setOnClickListener { applyPreviewAiFix(manual = true) }
        }
        previewRow.addView(previewAiButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Use AI"
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            includeFontPadding = false
            setOnClickListener { useAllAiCandidate() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Use Sel"
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            includeFontPadding = false
            setOnClickListener { useSelectedAiCandidate() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Insert"
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            includeFontPadding = false
            setOnClickListener { insertPreviewText() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Clear"
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            includeFontPadding = false
            setOnClickListener { clearPreviewText() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        root.addView(previewRow, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })
    }

    private fun addPreviewEditor(root: LinearLayout) {
        previewInput = EditText(this).apply {
            setText(previewLastInsertedText)
            hint = "Main preview — edit then Insert"
            textSize = 13f
            minLines = 2
            maxLines = 3
            setSingleLine(false)
            setTextColor(0xFFF7F7F7.toInt())
            setHintTextColor(0xFFB7C0CC.toInt())
            setBackgroundColor(0xFF46505E.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnFocusChangeListener { _, hasFocus -> previewEditActive = hasFocus; if (hasFocus) voiceStatusText = "Editing main preview" }
        }
        root.addView(previewInput, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(3) })
        addAiCandidateBox(root, compact = false)

        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewAiButton = Button(this).apply {
            text = "AI Fix"
            textSize = 11f
            isAllCaps = false
            isEnabled = previewAiFixMode != "off"
            setOnClickListener { applyPreviewAiFix(manual = true) }
        }
        previewRow.addView(previewAiButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Use AI"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { useAllAiCandidate() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Use Sel"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { useSelectedAiCandidate() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Insert"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { insertPreviewText() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        previewRow.addView(Button(this).apply {
            text = "Clear"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { clearPreviewText() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        root.addView(previewRow, LinearLayout.LayoutParams(-1, dp(39)).apply { topMargin = dp(3) })
    }

    private fun addAiCandidateBox(root: LinearLayout, compact: Boolean) {
        previewAiCandidateInput = EditText(this).apply {
            setText(previewAiCandidateText)
            hint = "AI candidate appears here"
            textSize = if (compact) 12f else 13f
            minLines = 1
            maxLines = if (compact) 2 else 3
            setSingleLine(false)
            setTextColor(0xFFFFE0E0.toInt())
            setHintTextColor(0xFFB7C0CC.toInt())
            setBackgroundColor(0xFF3A3035.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextIsSelectable(true)
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) voiceStatusText = "Select AI text, then Use Sel" }
        }
        root.addView(previewAiCandidateInput, LinearLayout.LayoutParams(-1, dp(if (compact) 36 else 52)).apply { topMargin = dp(2) })
    }

    private fun availableLocalEngineNames(): List<String> {
        val out = mutableListOf<String>()
        if (MoonshineModelManager.isInstalled(filesDir, MoonshineModelManager.ENGINE_BASE)) out.add(MoonshineModelManager.ENGINE_BASE)
        if (ParakeetModelManager.isInstalled(filesDir, ParakeetModelManager.ENGINE_TDT_V3)) out.add(ParakeetModelManager.ENGINE_TDT_V3)
        if (SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME)) out.add(SenseVoiceModelManager.ENGINE_NAME)
        if (SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024)) out.add(SenseVoiceModelManager.ENGINE_NAME_2024)
        if (QwenModelManager.isInstalled(filesDir)) out.add("phone_qwen_0_6b")
        return out
    }

    private fun engineButton(model: String) = Button(this).apply {
        text = if (engineName == model) "✓ ${modelButtonDisplay(model)}" else modelButtonDisplay(model)
        textSize = 8.8f
        maxLines = 2
        isSingleLine = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        isAllCaps = false
        setOnClickListener {
            if (!running.get()) {
                engineName = model
                savePrefs()
                refreshVoicePanel()
                setIdleStatus(engineLabel())
            }
        }
    }

    private fun optionButton(label: String, mode: String) = Button(this).apply {
        text = if (zhMode == mode) "✓ $label" else label
        textSize = 12f
        isAllCaps = false
        setOnClickListener {
            zhMode = mode
            savePrefs()
            refreshVoicePanel()
            setIdleStatus(if (verboseMode) "Voice output: ${zhLabel()}" else "")
        }
    }

    private fun refreshVoicePanel() {
        if (!::voicePanel.isInitialized) return
        val parent = voicePanel.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(voicePanel)
        parent.removeView(voicePanel)
        voicePanel = buildVoicePanel()
        parent.addView(voicePanel, index, LinearLayout.LayoutParams(-1, -2))
        voicePanel.visibility = if (voiceMode && !settingsMode) View.VISIBLE else View.GONE
        refreshKeyboardPanel()
    }

    private fun buildKeyboardPanel(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildSuggestionRow(), LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(2) })
        if (previewModeEnabled) addCompactPreviewEditor(root)
        addKeyboardRows(root)
        refreshSuggestions()
        return root
    }

    private fun buildSuggestionRow(): LinearLayout {
        fun suggestionButton(): Button = Button(this).apply {
            text = ""
            textSize = 18f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            setTextColor(0xFFECEFF4.toInt())
            setBackgroundColor(0xFF343A46.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestionLeft = suggestionButton()
        suggestionCenter = suggestionButton()
        suggestionRight = suggestionButton()
        row.addView(suggestionLeft, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(suggestionCenter, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(suggestionRight, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    private fun addKeyboardRows(root: LinearLayout) {
        if (showEmoji) root.addView(emojiPanel(), LinearLayout.LayoutParams(-1, dp(132)).apply { topMargin = dp(2) })
        root.addView(CanvasKeyboardView(), LinearLayout.LayoutParams(-1, keyboardCanvasHeightPx()).apply { topMargin = dp(2) })
    }

    private data class DrawKey(val label: String, val rect: RectF, val action: String, val longPress: String? = null)

    private inner class CanvasKeyboardView : View(this) {
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B414D.toInt() }
        private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF566071.toInt() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7F7F7.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA3AF.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val ctrlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4A5261.toInt()
        }
        private val keys = mutableListOf<DrawKey>()
        private var lastKeyWidth = -1
        private var lastKeyHeight = -1
        private var lastKeySymbols = false
        private var lastKeyPreviewMode = false
        private var lastKeySplitMode = ""
        private var downKey: DrawKey? = null
        private var repeatFired = false
        private val flowKeys = mutableListOf<String>()
        private var flowDistance = 0f
        private var lastFlowX = 0f
        private var lastFlowY = 0f
        private var flowGestureActive = false
        private val flowPoints = mutableListOf<PointF>()
        private val flowTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7DD3FC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = 210
        }
        private val flowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBAE6FD.toInt()
            style = Paint.Style.FILL
            alpha = 235
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ensureKeys()
            for (key in keys) {
                val isCtrl = key.action.startsWith("CTRL_")
                val bg = when {
                    key == downKey -> downPaint
                    isCtrl -> ctrlPaint
                    else -> keyPaint
                }
                canvas.drawRoundRect(key.rect, dp(7).toFloat(), dp(7).toFloat(), bg)
                val landscape = isLandscapeLayout()
                if (key.action == "CTRL_STATUS") {
                    textPaint.textSize = dp(8).toFloat()
                    canvas.drawText(voiceIdleLabel(), key.rect.centerX(), key.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    continue
                }
                key.longPress?.let { hint ->
                    hintPaint.textSize = dp(if (landscape) 7 else 10).toFloat()
                    canvas.drawText(hint, key.rect.centerX(), key.rect.top + dp(if (landscape) 8 else 14).toFloat(), hintPaint)
                }
                textPaint.textSize = if (landscape) {
                    dp(if (key.label.length > 3) 11 else 15).toFloat()
                } else {
                    dp(if (key.label.length > 3) 15 else 21).toFloat()
                }
                val labelYOffset = if (key.longPress != null) dp(if (landscape) 3 else 6).toFloat() else 0f
                val y = key.rect.centerY() + labelYOffset - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(key.label, key.rect.centerX(), y, textPaint)
            }
            drawFlowTrail(canvas)
        }

        private fun drawFlowTrail(canvas: Canvas) {
            if (!flowInputEnabled || flowPoints.size < 2 || symbols || keyboardLanguageMode != "en") return
            flowTrailPaint.strokeWidth = dp(if (isLandscapeLayout()) 4 else 5).toFloat()
            val path = Path().apply {
                moveTo(flowPoints.first().x, flowPoints.first().y)
                for (i in 1 until flowPoints.size) lineTo(flowPoints[i].x, flowPoints[i].y)
            }
            canvas.drawPath(path, flowTrailPaint)
            for (p in flowPoints) canvas.drawCircle(p.x, p.y, dp(4).toFloat(), flowDotPaint)
            flowPoints.lastOrNull()?.let { canvas.drawCircle(it.x, it.y, dp(8).toFloat(), flowDotPaint) }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            lastKeyWidth = -1
            lastKeyHeight = -1
        }

        private fun ensureKeys(force: Boolean = false) {
            if (width <= 0 || height <= 0) return
            if (!force && keys.isNotEmpty() && lastKeyWidth == width && lastKeyHeight == height && lastKeySymbols == symbols && lastKeyPreviewMode == previewModeEnabled && lastKeySplitMode == splitKeyboardMode) return
            buildKeys(width.toFloat())
            lastKeyWidth = width
            lastKeyHeight = height
            lastKeySymbols = symbols
            lastKeyPreviewMode = previewModeEnabled
            lastKeySplitMode = splitKeyboardMode
        }

        private fun buildKeys(w: Float) {
            keys.clear()
            val sy = keyboardScaleY()
            var y = 0f
            fun vy(v: Float) = v * sy
            if (!symbols && keyboardLanguageMode == "jiufang") {
                addJiufangPad(vy(y), w); y += dp(210)
                addBottomRow(vy(y), w, vy(dp(66).toFloat()))
                return
            }
            if (!symbols && keyboardLanguageMode == "sucheng") {
                addSuchengRows(vy(y), w); y += dp(210)
                addBottomRow(vy(y), w, vy(dp(66).toFloat()))
                return
            }
            val split = shouldSplitKeyboard(w) && !symbols && !previewModeEnabled
            if (split) {
                addSplitRow(listOf("1", "2", "3", "4", "5"), listOf("6", "7", "8", "9", "0"), vy(y), w, vy(dp(42).toFloat())); y += dp(44)
                addSplitRow("qwert".map { it.toString() }, "yuiop".map { it.toString() }, vy(y), w, vy(dp(52).toFloat())); y += dp(54)
                addSplitRow("asdfg".map { it.toString() }, "hjkl".map { it.toString() }, vy(y), w, vy(dp(52).toFloat())); y += dp(54)
                addSplitRow(listOf("⇧") + "zxcv".map { it.toString() }, "bnm".map { it.toString() } + "⌫", vy(y), w, vy(dp(52).toFloat()), fixedWidths = mapOf("⇧" to scaledKeyWidth(48), "⌫" to scaledKeyWidth(54))); y += dp(56)
                addSplitBottomRow(vy(y), w, vy(dp(66).toFloat()))
                if (useLandscapeSplitCenterControls()) addSplitCenterControls(w)
                return
            }
            addRow("1234567890".map { it.toString() }, vy(y), w, vy(dp(42).toFloat())); y += dp(44)
            val rows = if (symbols) listOf(listOf(".", ",", "?", "!", "'", "\"", ":", ";"), listOf("@", "#", "&", "*", "-", "+", "=", "(", ")"), listOf("/", "\\", "_", "€", "£", "$", "¥")) else listOf("qwertyuiop".map { it.toString() }, "asdfghjkl".map { it.toString() }, "zxcvbnm".map { it.toString() })
            addRow(rows[0], vy(y), w, vy(dp(52).toFloat())); y += dp(54)
            addRow(rows[1], vy(y), w, vy(dp(52).toFloat()), sideInset = if (symbols) 0f else dp((14 * keyboardScaleX()).toInt()).toFloat()); y += dp(54)
            val third = mutableListOf("⇧") + rows[2] + "⌫"
            addRow(third, vy(y), w, vy(dp(52).toFloat()), fixedWidths = mapOf("⇧" to scaledKeyWidth(48), "⌫" to scaledKeyWidth(54))); y += dp(56)
            addBottomRow(vy(y), w, vy(dp(66).toFloat()))
        }

        private fun keyboardScaleY(): Float {
            if (height <= 0) return 1f
            val designHeight = dp(278).toFloat()
            val minScale = when {
                isLandscapeLayout() -> 0.42f
                keyboardSizeMode == "compact" -> 0.70f
                else -> 0.68f
            }
            val maxScale = when (keyboardSizeMode) {
                "tall" -> 1.36f
                "compact" -> 0.92f
                else -> 1.08f
            }
            return (height / designHeight).coerceIn(minScale, maxScale)
        }

        private fun keyboardScaleX(): Float {
            if (!displayAwareMode) return 1f
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            return (widthDp / 390f).coerceIn(0.76f, 1.12f)
        }

        private fun scaledKeyWidth(baseDp: Int): Float = dp((baseDp * keyboardScaleX()).toInt()).toFloat()

        private fun shouldSplitKeyboard(w: Float): Boolean {
            val widthDp = w / resources.displayMetrics.density
            return when (splitKeyboardMode) {
                "on" -> true
                "auto" -> widthDp >= 600f
                else -> false
            }
        }

        private fun splitGapPx(w: Float): Float {
            val widthDp = w / resources.displayMetrics.density
            return dp(when {
                widthDp >= 820f -> 150
                widthDp >= 700f -> 116
                else -> 82
            }).toFloat()
        }

        private fun addJiufangPad(y0: Float, w: Float) {
            val gap = keyGapPx().toFloat()
            val side = dp(10).toFloat()
            val cellW = (w - side * 2 - gap * 2) / 3f
            val cellH = dp(50).toFloat() * keyboardScaleY()
            val rows = listOf(
                listOf(Triple("一", "h", "橫/1"), Triple("丨", "s", "豎/2"), Triple("丿", "p", "撇/3")),
                listOf(Triple("丶", "n", "點/4"), Triple("乙", "z", "折/5"), Triple("口", "k", "口/6")),
                listOf(Triple("十", "hs", "部件"), Triple("乂", "pn", "交叉"), Triple("冂", "sk", "框"))
            )
            for ((r, row) in rows.withIndex()) {
                for ((c, item) in row.withIndex()) {
                    val left = side + c * (cellW + gap)
                    val top = y0 + r * (cellH + gap)
                    keys.add(DrawKey(item.first, RectF(left, top, left + cellW, top + cellH), item.second, item.third))
                }
            }
            val top = y0 + 3 * (cellH + gap)
            val w1 = (w - side * 2 - gap * 2) / 3f
            keys.add(DrawKey("標點", RectF(side, top, side + w1, top + dp(48) * keyboardScaleY()), "."))
            keys.add(DrawKey("空格", RectF(side + w1 + gap, top, side + (w1 + gap) * 2, top + dp(48) * keyboardScaleY()), "SPACE"))
            keys.add(DrawKey("⌫", RectF(side + (w1 + gap) * 2, top, side + (w1 + gap) * 2 + w1, top + dp(48) * keyboardScaleY()), "⌫"))
        }

        private fun addSuchengRows(y0: Float, w: Float) {
            val rows = listOf(
                listOf("日A:a", "月B:b", "金C:c", "木D:d", "水E:e", "火F:f", "土G:g"),
                listOf("竹H:h", "戈I:i", "十J:j", "大K:k", "中L:l", "一M:m", "弓N:n"),
                listOf("人O:o", "心P:p", "手Q:q", "口R:r", "尸S:s", "廿T:t"),
                listOf("山U:u", "女V:v", "田W:w", "難X:x", "卜Y:y", "⌫:⌫")
            )
            var y = y0
            for (row in rows) {
                val parts = row.map { token -> token.substringBefore(":") to token.substringAfter(":") }
                val gap = keyGapPx().toFloat()
                val totalGap = gap * (parts.size - 1).coerceAtLeast(0)
                val cellW = (w - totalGap) / parts.size
                var x = 0f
                for ((label, action) in parts) {
                    keys.add(DrawKey(label, RectF(x, y, x + cellW, y + dp(48) * keyboardScaleY()), action))
                    x += cellW + gap
                }
                y += dp(51) * keyboardScaleY()
            }
        }

        private fun addSplitCenterControls(w: Float) {
            val gap = splitGapPx(w)
            val halfW = ((w - gap) / 2f).coerceAtLeast(dp(120).toFloat())
            val margin = dp(4).toFloat()
            val colW = (gap - margin * 2).coerceAtLeast(dp(66).toFloat())
            val x = halfW + margin
            val statusH = dp(18).toFloat()
            val rowH = dp(24).toFloat()
            val g = dp(2).toFloat()
            keys.add(DrawKey("", RectF(x, 0f, x + colW, statusH), "CTRL_STATUS"))
            val cellW = (colW - g) / 2f
            fun ctrl(row: Int, col: Int, label: String, action: String) {
                val left = x + col * (cellW + g)
                val top = statusH + g + row * (rowH + g)
                keys.add(DrawKey(label, RectF(left, top, left + cellW, top + rowH), action))
            }
            ctrl(0, 0, "●", "CTRL_REC")
            ctrl(0, 1, "⌨", "CTRL_KEYBOARD")
            ctrl(1, 0, "🎙", "CTRL_VOICE")
            ctrl(1, 1, "✍", "CTRL_HANDWRITE")
            ctrl(2, 0, "📋", "CTRL_CLIP")
            ctrl(2, 1, "⚙", "CTRL_SETTINGS")
        }

        private fun addSplitRow(left: List<String>, right: List<String>, y: Float, w: Float, h: Float, fixedWidths: Map<String, Float> = emptyMap()) {
            val gap = splitGapPx(w)
            val halfW = ((w - gap) / 2f).coerceAtLeast(dp(120).toFloat())
            addRow(left, y, halfW, h, fixedWidths = fixedWidths)
            addRow(right, y, halfW, h, fixedWidths = fixedWidths, xOffset = halfW + gap)
        }

        private fun addRow(labels: List<String>, y: Float, w: Float, h: Float, sideInset: Float = 0f, fixedWidths: Map<String, Float> = emptyMap(), xOffset: Float = 0f) {
            val gap = keyGapPx().toFloat()
            val totalGap = gap * (labels.size - 1).coerceAtLeast(0)
            val totalFixed = labels.sumOf { (fixedWidths[it] ?: 0f).toDouble() }.toFloat()
            val flexCount = labels.count { !fixedWidths.containsKey(it) }.coerceAtLeast(1)
            val flexW = (w - sideInset * 2 - totalFixed - totalGap) / flexCount
            var x = xOffset + sideInset
            for (label in labels) {
                val kw = fixedWidths[label] ?: flexW
                keys.add(DrawKey(drawLabel(label), RectF(x, y, x + kw, y + h), label, secondarySymbol(label)))
                x += kw + gap
            }
        }

        private fun addSplitBottomRow(y: Float, w: Float, h: Float) {
            val gap = splitGapPx(w)
            val halfW = ((w - gap) / 2f).coerceAtLeast(dp(120).toFloat())
            fun addSegment(x0: Float, labels: List<Triple<String, Float, String>>) {
                val fixed = labels.sumOf { scaledKeyWidth(it.second.toInt()).toDouble() }.toFloat()
                val spaceCount = labels.count { it.third == "SPACE" }.coerceAtLeast(0)
                val flexW = if (spaceCount > 0) (halfW - fixed).coerceAtLeast(dp(70).toFloat()) / spaceCount else 0f
                var x = x0
                for ((label, widthDp, action) in labels) {
                    val width = if (action == "SPACE") flexW else scaledKeyWidth(widthDp.toInt())
                    keys.add(DrawKey(label, RectF(x, y, x + width, y + h), action, secondarySymbol(action)))
                    x += width
                }
            }
            addSegment(0f, listOf(Triple("?!,", 42f, "SYM"), Triple("😊", 38f, "EMOJI"), Triple(",", 42f, ","), Triple("Space", 0f, "SPACE")))
            addSegment(halfW + gap, listOf(Triple("Space", 0f, "SPACE"), Triple(".", 44f, "."), Triple("⏎", 58f, "ENTER")))
        }

        private fun addBottomRow(y: Float, w: Float, h: Float) {
            val fixed = listOf(
                Triple(if (symbols) "ABC" else "?!,", 37f, "SYM"),
                Triple("😊", 33f, "EMOJI"),
                Triple(",", 41f, ","),
                Triple(".", 44f, "."),
                Triple("⏎", 58f, "ENTER")
            )
            val fixedPx = fixed.sumOf { scaledKeyWidth(it.second.toInt()).toDouble() }.toFloat()
            val spaceW = (w - fixedPx).coerceAtLeast(dp(92).toFloat())
            var x = 0f
            fun add(label: String, width: Float, action: String) { keys.add(DrawKey(label, RectF(x, y, x + width, y + h), action, secondarySymbol(action))); x += width }
            fixed.take(3).forEach { add(it.first, scaledKeyWidth(it.second.toInt()), it.third) }
            add(keyboardSpaceLabel(), spaceW, "SPACE")
            fixed.drop(3).forEach { add(it.first, scaledKeyWidth(it.second.toInt()), it.third) }
        }

        private fun drawLabel(label: String) = if (!symbols && shift && label.length == 1 && label[0].isLetter()) label.uppercase() else label

        private fun secondarySymbol(label: String): String? {
            if (symbols) return null
            return when (label.lowercase()) {
                "q" -> "%"; "w" -> "^"; "e" -> "~"; "r" -> "|"; "t" -> "["; "y" -> "]"; "u" -> "<"; "i" -> ">"; "o" -> "{"; "p" -> "}"
                "a" -> "@"; "s" -> "#"; "d" -> "&"; "f" -> "*"; "g" -> "-"; "h" -> "+"; "j" -> "="; "k" -> "("; "l" -> ")"
                "z" -> "_"; "x" -> "\$"; "c" -> "\""; "v" -> "'"; "b" -> ":"; "n" -> ";"; "m" -> "/"
                "." -> "?"
                "," -> "!"
                else -> null
            }
        }

        private fun nearestKey(x: Float, y: Float): DrawKey? {
            keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
            val sameRow = keys.filter { y >= it.rect.top - dp(12) && y <= it.rect.bottom + dp(12) }
            val candidates = if (sameRow.isNotEmpty()) sameRow else keys
            return candidates.minByOrNull { val dx = x - it.rect.centerX(); val dy = y - it.rect.centerY(); dx * dx + dy * dy }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ensureKeys()
                    touchDownX = event.x; touchDownY = event.y; didSwipeDelete = false; repeatFired = false
                    downKey = nearestKey(event.x, event.y)
                    flowKeys.clear()
                    flowPoints.clear()
                    flowDistance = 0f
                    flowGestureActive = false
                    lastFlowX = event.x
                    lastFlowY = event.y
                    downKey?.action?.let { if (flowInputEnabled && !symbols && keyboardLanguageMode == "en" && isFlowLetterKey(it)) { flowKeys.add(it.lowercase()); flowPoints.add(PointF(event.x, event.y)) } }
                    if (downKey?.action == "VOICE_HOLD") {
                        holdVoiceFromKeyboard = true
                        if (!running.get()) startDictation()
                        voiceStatusText = if (verboseMode) "Hold-to-dictate…" else "Transcribing…"
                    } else if (downKey?.longPress != null) {
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        repeatRunnable = Runnable {
                            val key = downKey ?: return@Runnable
                            val symbol = key.longPress ?: return@Runnable
                            repeatFired = true
                            commitText(symbol)
                        }
                        repeatHandler.postDelayed(repeatRunnable!!, 360L)
                    } else if (holdRepeatEnabled && downKey?.let { isAutoRepeatKey(it.action) } == true) {
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        repeatRunnable = object : Runnable {
                            override fun run() {
                                downKey?.let { performCanvasKey(it) }
                                repeatFired = true
                                repeatHandler.postDelayed(this, 55L)
                            }
                        }
                        repeatHandler.postDelayed(repeatRunnable!!, repeatDelayMs)
                    }
                    invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (flowInputEnabled && flowKeys.isNotEmpty() && !symbols && keyboardLanguageMode == "en") {
                        val dx = event.x - lastFlowX
                        val dy = event.y - lastFlowY
                        flowDistance += kotlin.math.sqrt(dx * dx + dy * dy)
                        lastFlowX = event.x
                        lastFlowY = event.y
                        if (flowDistance > dp(18)) {
                            flowGestureActive = true
                            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                            repeatRunnable = null
                        }
                        if (flowPoints.isEmpty() || kotlin.math.hypot((event.x - flowPoints.last().x).toDouble(), (event.y - flowPoints.last().y).toDouble()) > dp(8)) {
                            flowPoints.add(PointF(event.x, event.y))
                            if (flowPoints.size > 96) flowPoints.removeAt(0)
                            invalidate()
                        }
                        nearestKey(event.x, event.y)?.action?.let { action ->
                            if (isFlowLetterKey(action)) {
                                val k = action.lowercase()
                                if (flowKeys.lastOrNull() != k) flowKeys.add(k)
                            }
                        }
                    }
                    if (!flowGestureActive && swipeDeleteEnabled && !didSwipeDelete && touchDownX - event.x > dp(70) && kotlin.math.abs(event.y - touchDownY) < dp(85)) {
                        didSwipeDelete = true
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        repeatRunnable = null
                        deleteLastWord()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null
                    val key = downKey
                    if (key?.action == "VOICE_HOLD") {
                        if (running.get()) stopDictation()
                        holdVoiceFromKeyboard = false
                        setIdleStatus(if (verboseMode) "Qwen Keyboard" else "")
                    } else if (!didSwipeDelete && key != null) {
                        val flowCandidate = flowCandidateFromPath()
                        if (flowCandidate != null) {
                            commitText(flowCandidate)
                            commitText(" ")
                            voiceStatusText = "Flow: $flowCandidate"
                        } else if (flowGestureActive) {
                            val flowSuggestions = flowGuessesFromGeometry(3, strictAutoCommit = false).ifEmpty { flowGuesses(flowKeys.joinToString(""), 3) }
                            if (flowSuggestions.isNotEmpty()) {
                                setSuggestionTexts(flowSuggestions)
                                voiceStatusText = "Flow suggestions"
                            } else {
                                voiceStatusText = "Flow: no match (${flowKeys.joinToString("").take(12)})"
                            }
                        } else if (!repeatFired) {
                            performCanvasKey(key)
                        }
                    }
                    flowKeys.clear(); flowPoints.clear(); flowDistance = 0f; flowGestureActive = false
                    downKey = null; invalidate(); return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let { repeatHandler.removeCallbacks(it) }; repeatRunnable = null
                    if (downKey?.action == "VOICE_HOLD") { if (running.get()) stopDictation(); holdVoiceFromKeyboard = false }
                    flowKeys.clear(); flowPoints.clear(); flowDistance = 0f; flowGestureActive = false
                    downKey = null; invalidate(); return true
                }
            }
            return true
        }

        private fun isFlowLetterKey(action: String): Boolean = action.length == 1 && action[0].isLetter()

        private fun flowCandidateFromPath(): String? {
            if (!flowInputEnabled || keyboardLanguageMode != "en" || symbols) return null
            if (flowDistance < dp(35) || flowKeys.size < 2) return null
            val geometry = flowGuessesFromGeometry(2, strictAutoCommit = true)
            if (geometry.isNotEmpty()) return geometry.first()
            val signature = flowKeys.joinToString("")
            val compact = signature.replace(Regex("(.)\\1+"), "$1")
            val candidates = flowWordMap[compact].orEmpty() + flowWordMap[signature].orEmpty()
            return candidates.firstOrNull() ?: bestFlowGuess(compact)
        }

        private fun flowGuessesFromGeometry(limit: Int, strictAutoCommit: Boolean): List<String> {
            if (flowPoints.size < 2) return emptyList()
            val first = flowKeys.firstOrNull()?.firstOrNull() ?: return emptyList()
            val pool = (wordFreq.keys + learnedFreq.keys + flowWordMap.values.flatten())
                .asSequence()
                .map { it.trim() }
                .filter { it.length in 2..18 && it.firstOrNull()?.lowercaseChar() == first }
                .distinctBy { it.lowercase() }
                .filterNot { forgottenWords.contains(normalizeLearnedWord(it)) }
                .toList()
            data class GeoRank(val word: String, val score: Float, val avgDistance: Float)
            val ranked = pool.mapNotNull { candidate ->
                val word = compactFlowToken(candidate.lowercase())
                if (word.length < 2) return@mapNotNull null
                val score = flowGeometryScore(word) ?: return@mapNotNull null
                val avg = score / word.length.coerceAtLeast(1)
                val allowed = if (strictAutoCommit) avg <= dp(30).toFloat() else avg <= dp(58).toFloat()
                if (allowed) GeoRank(candidate, score - word.length.coerceAtMost(8) * dp(3), avg) else null
            }.sortedWith(compareBy<GeoRank> { it.score }.thenBy { it.avgDistance }.thenByDescending { it.word.length })
            if (strictAutoCommit) {
                val best = ranked.firstOrNull() ?: return emptyList()
                val second = ranked.getOrNull(1)
                val clearMargin = second == null || second.score - best.score > dp(18)
                return if (clearMargin) listOf(best.word) else emptyList()
            }
            return ranked.take(limit).map { it.word }
        }

        private fun flowGeometryScore(word: String): Float? {
            val centers = keys.filter { isFlowLetterKey(it.action) }.associate { it.action.lowercase()[0] to PointF(it.rect.centerX(), it.rect.centerY()) }
            var lastIndex = -1
            var orderPenalty = 0f
            var total = 0f
            val compactWord = compactFlowToken(word)
            for (ch in compactWord) {
                val center = centers[ch] ?: return null
                var bestDist = Float.MAX_VALUE
                var bestIndex = -1
                for (i in flowPoints.indices) {
                    val p = flowPoints[i]
                    val d = kotlin.math.hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble()).toFloat()
                    if (d < bestDist) { bestDist = d; bestIndex = i }
                }
                total += bestDist
                if (bestIndex < lastIndex) orderPenalty += dp(22).toFloat() else lastIndex = bestIndex
            }
            val firstCenter = centers[compactWord.firstOrNull()] ?: return null
            val lastCenter = centers[compactWord.lastOrNull()] ?: return null
            total += kotlin.math.hypot((flowPoints.first().x - firstCenter.x).toDouble(), (flowPoints.first().y - firstCenter.y).toDouble()).toFloat() * 0.8f
            total += kotlin.math.hypot((flowPoints.last().x - lastCenter.x).toDouble(), (flowPoints.last().y - lastCenter.y).toDouble()).toFloat() * 0.6f
            return total + orderPenalty
        }
    }

    private fun isAutoRepeatKey(action: String): Boolean {
        return action == "⌫" || action == "SPACE" || (action.length == 1 && action[0].isLetterOrDigit())
    }

    private fun handleEnterKey(learnCurrentWord: Boolean = true) {
        if (previewIsFocused()) {
            insertIntoPreview("\n")
            return
        }
        if (learnCurrentWord) {
            val word = currentWord()
            learnWord(word)
            learnCorrectionIfPending(word)
        }
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
            // Messaging apps often expose SEND while still allowing multi-line text.
            // Keep Enter as newline there; app send buttons remain available separately.
            else -> false
        }
        if (shouldRunAction && ic.performEditorAction(action)) return
        ic.commitText("\n", 1)
    }

    private fun performCanvasKey(key: DrawKey) {
        when (key.action) {
            "CTRL_STATUS" -> Unit
            "CTRL_REC" -> if (running.get()) stopDictation() else startDictation()
            "CTRL_KEYBOARD" -> setVoiceMode(false)
            "CTRL_VOICE" -> setVoiceMode(true)
            "CTRL_HANDWRITE" -> showHandwriting()
            "CTRL_CLIP" -> showClipboard()
            "CTRL_SETTINGS" -> showSettings()
            "SYM" -> { symbols = !symbols; shift = false; refreshKeyboardPanel() }
            "EMOJI" -> { showEmoji = !showEmoji; refreshKeyboardPanel() }
            "SPACE" -> handleSpace()
            "ENTER" -> {
                handleEnterKey()
                lastSpaceAt = 0L
                refreshSuggestions()
            }
            "⇧" -> { shift = !shift; refreshKeyboardPanel() }
            "⌫" -> deleteOneCharRememberingWord()
            else -> {
                val out = if (!symbols && shift && key.action.length == 1 && key.action[0].isLetter()) key.action.uppercase() else key.action
                commitText(out)
                if (shift && !symbols) { shift = false; refreshKeyboardPanel() }
            }
        }
    }

    private fun emojiPanel(): View {
        val outer = ScrollView(this).apply { setBackgroundColor(0x22343A46) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groups = listOf(
            listOf("😀","😃","😄","😁","😆","😂","🤣","😊","😇","🙂","😉","😍","😘","😋","😎","🤓"),
            listOf("😐","😑","🙄","😴","😪","😮‍💨","🤔","🤯","🥳","😤","😭","😱","😡","🤬","🤮","😷"),
            listOf("👍","👎","👌","✌️","🤞","👏","🙌","🙏","💪","👀","🧠","👑","💼","📌","✅","❌"),
            listOf("❤️","🧡","💛","💚","💙","💜","🔥","✨","🎉","🚀","⭐","⚡","☕","🍺","🍣","🍰")
        )
        for (g in groups) {
            val hsv = HorizontalScrollView(this)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (emoji in g) row.addView(keyButton(emoji, repeatable = false) { commitText(emoji) }, LinearLayout.LayoutParams(dp(44), dp(38)).apply { leftMargin = dp(1); rightMargin = dp(1) })
            hsv.addView(row)
            col.addView(hsv, LinearLayout.LayoutParams(-1, dp(38)))
        }
        outer.addView(col)
        return outer
    }

    private fun refreshKeyboardPanel() {
        if (!::keyboardPanel.isInitialized) return
        updateRootPadding()
        keyboardPanel.removeAllViews()
        keyboardPanel.addView(buildSuggestionRow(), LinearLayout.LayoutParams(-1, suggestionRowHeightPx()).apply { topMargin = dp(if (isLandscapeLayout()) 1 else 2) })
        if (previewModeEnabled) addCompactPreviewEditor(keyboardPanel)
        addKeyboardRows(keyboardPanel)
        refreshSuggestions()
    }

    private fun letterRow(chars: String, sidePad: Int = 0): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(sidePad, 0, sidePad, 0)
            for (ch in chars) addView(charButton(ch.toString()), keyParams())
        }
    }

    private fun charButton(label: String): Button {
        val out = if (!symbols && shift) label.uppercase() else label
        return keyButton(out, repeatable = true) {
            commitText(out)
            if (shift && !symbols) { shift = false; refreshKeyboardPanel() }
        }
    }

    private fun keyButton(label: String, weight: Float = 1f, width: Int = 0, repeatable: Boolean = false, keyHeight: Int = dp(48), onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = if (label.length > 3) 12f else 20f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            isAllCaps = false
            setTextColor(0xFFF7F7F7.toInt())
            setBackgroundColor(0xFF3B414D.toInt())
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (!repeatable) onClick() }
            setOnTouchListener { _, event -> if (repeatable) handleRepeatTouch(event, onClick) else handleSwipeOnly(event) }
            layoutParams = LinearLayout.LayoutParams(if (width > 0) width else 0, keyHeight, weight).apply { leftMargin = 0; rightMargin = 0 }
        }
    }

    private fun spaceButton(): Button {
        return Button(this).apply {
            text = keyboardSpaceLabel()
            textSize = 12f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            isAllCaps = false
            setTextColor(0xFFF7F7F7.toInt())
            setBackgroundColor(0xFF3B414D.toInt())
            setPadding(0, 0, 0, 0)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.rawX
                        touchDownY = event.rawY
                        didSwipeDelete = false
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        if (holdRepeatEnabled) {
                            repeatRunnable = object : Runnable {
                                override fun run() {
                                    handleSpace()
                                    repeatHandler.postDelayed(this, 85L)
                                }
                            }
                            repeatHandler.postDelayed(repeatRunnable!!, repeatDelayMs)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (swipeDeleteEnabled && !didSwipeDelete && touchDownX - event.rawX > dp(70) && kotlin.math.abs(event.rawY - touchDownY) < dp(80)) {
                            didSwipeDelete = true
                            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                            repeatRunnable = null
                            deleteLastWord()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        repeatRunnable = null
                        if (!didSwipeDelete) handleSpace()
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun holdVoiceButton(): Button {
        return Button(this).apply {
            text = "🎙"
            textSize = 20f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            isAllCaps = false
            setTextColor(0xFFF7F7F7.toInt())
            setBackgroundColor(0xFF3B414D.toInt())
            setPadding(0, 0, 0, 0)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holdVoiceFromKeyboard = true
                        if (!running.get()) startDictation()
                        voiceStatusText = if (verboseMode) "Hold-to-dictate…" else "Transcribing…"
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (running.get()) stopDictation()
                        holdVoiceFromKeyboard = false
                        setIdleStatus(if (verboseMode) "Qwen Keyboard" else "")
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun handleRepeatTouch(event: MotionEvent, action: () -> Unit): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.rawX; touchDownY = event.rawY; didSwipeDelete = false
                repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                if (holdRepeatEnabled) {
                    repeatRunnable = object : Runnable { override fun run() { action(); repeatHandler.postDelayed(this, 55L) } }
                    repeatHandler.postDelayed(repeatRunnable!!, repeatDelayMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeDeleteEnabled && !didSwipeDelete && touchDownX - event.rawX > dp(90) && kotlin.math.abs(event.rawY - touchDownY) < dp(70)) {
                    didSwipeDelete = true
                    repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                    deleteLastWord()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                repeatRunnable = null
                if (!didSwipeDelete) action()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                repeatRunnable = null
                return true
            }
        }
        return true
    }

    private fun handleSwipeOnly(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchDownX = event.rawX; touchDownY = event.rawY; didSwipeDelete = false }
            MotionEvent.ACTION_MOVE -> if (swipeDeleteEnabled && !didSwipeDelete && touchDownX - event.rawX > dp(90) && kotlin.math.abs(event.rawY - touchDownY) < dp(70)) { didSwipeDelete = true; deleteLastWord(); return true }
        }
        return false
    }

    private fun commitText(text: String) {
        if (previewIsFocused()) {
            insertIntoPreview(text)
            return
        }
        val ic = currentInputConnection ?: return
        if (text.length == 1 && text[0] in ",.?!:;，。？！：；") {
            val before = ic.getTextBeforeCursor(8, 0)?.toString().orEmpty()
            val spaces = before.takeLastWhile { it == ' ' }.length
            if (spaces > 0) ic.deleteSurroundingText(spaces, 0)
            ic.commitText(text, 1)
        } else {
            ic.commitText(text, 1)
        }
        lastSpaceAt = 0L
        refreshSuggestions()
    }

    private fun previewIsFocused(): Boolean = previewModeEnabled && ::previewInput.isInitialized && previewInput.hasFocus() && previewEditActive

    private fun insertIntoPreview(text: String) {
        if (!::previewInput.isInitialized) return
        val start = previewInput.selectionStart.coerceAtLeast(0)
        val end = previewInput.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        previewInput.text.replace(lo, hi, text)
        previewInput.setSelection(lo + text.length)
        previewLastInsertedText = previewInput.text.toString()
        previewAiFixedText = ""
    }

    private fun deleteFromPreview(): Boolean {
        if (!previewIsFocused()) return false
        val start = previewInput.selectionStart.coerceAtLeast(0)
        val end = previewInput.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        if (hi > lo) {
            previewInput.text.delete(lo, hi)
            previewInput.setSelection(lo)
        } else if (lo > 0) {
            previewInput.text.delete(lo - 1, lo)
            previewInput.setSelection(lo - 1)
        }
        previewLastInsertedText = previewInput.text.toString()
        previewAiFixedText = ""
        return true
    }

    private fun handleSpace() {
        if (previewIsFocused()) {
            insertIntoPreview(" ")
            return
        }
        val now = System.currentTimeMillis()
        val ic = currentInputConnection ?: return
        if (autoCorrectEnabled) applyBasicAutoCorrect()
        val wordBeforeSpace = currentWord()
        learnWord(wordBeforeSpace)
        val beforeSpaceContext = ic.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        val recentWords = Regex("[\\p{L}']{2,}").findAll(beforeSpaceContext).map { normalizeLearnedWord(it.value) }.filter { it.length >= 2 }.toList().takeLast(3)
        learnNextWordPatterns(recentWords)
        learnCorrectionIfPending(wordBeforeSpace)
        if (doubleSpaceEnabled && now - lastSpaceAt < 700L) {
            val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
            val spaces = before.takeLastWhile { it == ' ' }.length.coerceAtLeast(1)
            val trimmed = before.trimEnd()
            ic.deleteSurroundingText(spaces, 0)
            if (trimmed.lastOrNull() in listOf('.', '!', '?', '。', '！', '？')) {
                ic.commitText(" ", 1)
            } else {
                ic.commitText(". ", 1)
            }
            lastSpaceAt = 0L
        } else {
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        if (::suggestionCenter.isInitialized) setSuggestionTexts(nextWordSuggestions())
    }

    private fun applyBasicAutoCorrect() {
        val ic = currentInputConnection ?: return
        val word = currentWord()
        if (word.length < 2) return
        val lower = word.lowercase()
        val contraction = contractionSuggestions[lower]?.firstOrNull()
        if (contraction != null && !forgottenWords.contains(normalizeLearnedWord(contraction))) {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(matchCase(word, contraction), 1)
            return
        }
        if (wordFreq.containsKey(lower) || learnedFreq.containsKey(lower)) return
        val replacement = learnedCorrectionSuggestions(lower).firstOrNull() ?: bestCorrections(lower, maxDistance = if (lower.length <= 4) 1 else 2).firstOrNull() ?: autoCorrect[lower] ?: return
        if (replacement != lower) {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(matchCase(word, replacement), 1)
        }
    }

    private fun refreshSuggestions() {
        if (!::suggestionCenter.isInitialized || !suggestionModeEnabled) {
            if (::suggestionCenter.isInitialized) setSuggestionTexts(emptyList())
            return
        }
        if (keyboardLanguageMode == "pinyin" || keyboardLanguageMode == "jiufang" || keyboardLanguageMode == "sucheng") {
            refreshChineseSuggestions()
            return
        }
        val word = currentWord().lowercase()
        if (word.isBlank()) {
            setSuggestionTexts(nextWordSuggestions())
            return
        }
        val global = globalSuggestions(word)
        val next = nextWordSuggestionsForContext(listOf(word))
        val suggestions = if ((wordFreq.containsKey(word) || learnedFreq.containsKey(word)) && global.isEmpty()) {
            (next + listOf(word) + learnedCorrectionSuggestions(word) + specialSuggestions[word].orEmpty() + prefixSuggestions(word).filter { it != word }).distinct().filterNotForgotten().take(3)
        } else {
            (global + next + learnedCorrectionSuggestions(word) + specialSuggestions[word].orEmpty() + bestCorrections(word, maxDistance = if (word.length <= 4) 2 else 3) + prefixSuggestions(word)).distinct().filterNotForgotten().take(3)
        }
        setSuggestionTexts(suggestions)
    }

    private fun refreshChineseSuggestions() {
        val code = currentChineseCode()
        val candidates = when (keyboardLanguageMode) {
            "pinyin" -> pinyinCandidates(code)
            "jiufang" -> jiufangCandidates(code)
            "sucheng" -> suchengCandidates(code)
            else -> emptyList()
        }
        setChineseSuggestionTexts(candidates)
    }

    private fun setChineseSuggestionTexts(words: List<String>) {
        val left = words.getOrNull(1) ?: ""
        val center = words.getOrNull(0) ?: chineseModeLabel()
        val right = words.getOrNull(2) ?: ""
        fun bind(button: Button, word: String) {
            button.text = word
            button.setOnClickListener {
                if (word.isBlank() || word == chineseModeLabel()) toggleChineseKeyboardMode() else commitChineseCandidate(word)
            }
            button.setOnLongClickListener(null)
        }
        bind(suggestionLeft, left)
        bind(suggestionCenter, center)
        bind(suggestionRight, right)
    }

    private fun currentChineseCode(): String {
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetterOrDigit() }.lowercase()
    }

    private fun commitChineseCandidate(candidate: String) {
        val ic = currentInputConnection ?: return
        val code = currentChineseCode()
        if (code.isNotBlank()) ic.deleteSurroundingText(code.length, 0)
        ic.commitText(convertChinese(candidate), 1)
        learnWordsFromText(candidate)
        lastSpaceAt = 0L
        refreshSuggestions()
    }

    private fun chineseModeLabel(): String = when (keyboardLanguageMode) {
        "pinyin" -> "拼音"
        "jiufang" -> "九方"
        "sucheng" -> "速成"
        else -> "EN"
    }

    private fun keyboardSpaceLabel(): String = when (keyboardLanguageMode) {
        "pinyin" -> "拼音 ${zhLabel()}"
        "jiufang" -> "九方 ${zhLabel()}"
        "sucheng" -> "速成 ${zhLabel()}"
        else -> "English (US)"
    }

    private fun pinyinCandidates(rawCode: String): List<String> {
        val code = normalizePinyinCode(rawCode)
        if (code.isBlank()) return listOf("你", "我", "係")
        val exact = pinyinMap[code].orEmpty()
        val segmented = if (exact.size >= 3) emptyList() else pinyinSegmentCandidates(code)
        val prefix = if (exact.size + segmented.size >= 3) emptyList() else pinyinMap.entries
            .asSequence()
            .filter { it.key.startsWith(code) && it.key != code }
            .flatMap { it.value.asSequence() }
            .take(8)
            .toList()
        return (exact + segmented + prefix).distinct().take(3)
    }

    private fun normalizePinyinCode(code: String): String = code
        .lowercase()
        .replace("'", "")
        .replace("v", "u")
        .filter { it in 'a'..'z' }

    private fun pinyinSegmentCandidates(code: String): List<String> {
        if (code.length < 4 || code.length > 16) return emptyList()
        val memo = mutableMapOf<Int, List<String>>()
        fun build(index: Int): List<String> {
            if (index == code.length) return listOf("")
            memo[index]?.let { return it }
            val out = mutableListOf<String>()
            for (end in (index + 1)..minOf(code.length, index + 6)) {
                val part = code.substring(index, end)
                val chars = pinyinMap[part]?.take(2) ?: continue
                for (ch in chars) {
                    for (tail in build(end)) {
                        out += ch + tail
                        if (out.size >= 12) break
                    }
                    if (out.size >= 12) break
                }
                if (out.size >= 12) break
            }
            return out.distinct().take(12).also { memo[index] = it }
        }
        return build(0).filter { it.length >= 2 }.take(8)
    }

    private fun jiufangCandidates(code: String): List<String> {
        if (code.isBlank()) return listOf("日", "月", "口")
        val normalized = code.mapNotNull { jiufangAlias[it] ?: if (it in listOf('h', 's', 'p', 'n', 'z', 'k')) it else null }.joinToString("")
        if (normalized.isBlank()) return listOf("橫1", "豎2", "口6")
        val exact = jiufangMap[normalized].orEmpty()
        val prefix = if (exact.size >= 3) emptyList() else jiufangMap.entries
            .asSequence()
            .filter { it.key.startsWith(normalized) && it.key != normalized }
            .flatMap { it.value.asSequence() }
            .take(12)
            .toList()
        val fuzzy = if (exact.size + prefix.size >= 3) emptyList() else jiufangMap.entries
            .asSequence()
            .filter { it.key.length <= 3 && normalized.all { ch -> ch in it.key } }
            .flatMap { it.value.asSequence() }
            .take(6)
            .toList()
        return (exact + prefix + fuzzy).distinct().take(3)
    }

    private fun suchengCandidates(code: String): List<String> {
        val c = code.lowercase().filter { it in 'a'..'z' }.takeLast(2)
        if (c.isBlank()) return listOf("日", "月", "金")
        val exact = suchengMap[c].orEmpty()
        val prefix = if (exact.size >= 3) emptyList() else suchengMap.entries
            .asSequence()
            .filter { it.key.startsWith(c) && it.key != c }
            .flatMap { it.value.asSequence() }
            .take(12)
            .toList()
        return (exact + prefix).distinct().take(3)
    }

    private fun setSuggestionTexts(words: List<String>) {
        pendingForgetWord?.let { word ->
            bindForgetConfirmation(word)
            return
        }
        val left = words.getOrNull(1) ?: ""
        val center = words.getOrNull(0) ?: ""
        val right = words.getOrNull(2) ?: ""
        fun bind(button: Button, word: String) {
            button.text = word
            button.setOnClickListener { if (word.isNotBlank()) replaceCurrentWord(word) }
            button.setOnLongClickListener {
                if (word.isNotBlank()) requestForgetConfirmation(word)
                true
            }
        }
        bind(suggestionLeft, left)
        bind(suggestionCenter, center)
        bind(suggestionRight, right)
    }

    private fun requestForgetConfirmation(word: String) {
        pendingForgetWord = word
        bindForgetConfirmation(word)
        voiceStatusText = "Confirm forget"
    }

    private fun bindForgetConfirmation(word: String) {
        if (!::suggestionLeft.isInitialized) return
        suggestionLeft.text = "Forget?"
        suggestionLeft.setOnClickListener { }
        suggestionLeft.setOnLongClickListener(null)
        suggestionCenter.text = "Yes: $word"
        suggestionCenter.setOnClickListener {
            pendingForgetWord = null
            forgetSuggestedWord(word)
        }
        suggestionCenter.setOnLongClickListener(null)
        suggestionRight.text = "Cancel"
        suggestionRight.setOnClickListener {
            pendingForgetWord = null
            refreshSuggestions()
            voiceStatusText = "Cancelled"
        }
        suggestionRight.setOnLongClickListener(null)
    }

    private fun replaceCurrentWord(word: String) {
        val ic = currentInputConnection ?: return
        val current = currentWord()
        if (current.isBlank()) {
            ic.commitText(word + " ", 1)
            learnWordsFromText(word)
            lastSpaceAt = 0L
            refreshSuggestions()
            return
        }
        val currentLower = normalizeLearnedWord(current)
        val selectedLower = normalizeLearnedWord(word)
        val isNextWordPrediction = nextWordSuggestionsForContext(listOf(currentLower)).map { normalizeLearnedWord(it) }.contains(selectedLower)
        if (isNextWordPrediction) {
            ic.commitText(" $word ", 1)
            learnWordsFromText("$current $word")
            lastSpaceAt = 0L
            refreshSuggestions()
            return
        }
        ic.deleteSurroundingText(current.length, 0)
        ic.commitText(matchCase(current, word) + " ", 1)
        learnWord(word)
        learnCorrection(current, word)
        // Suggestion selection already adds one space. Keep double-space logic waiting
        // for two explicit spacebar taps after this, rather than firing on the next tap.
        lastSpaceAt = 0L
        refreshSuggestions()
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
    }

    private fun globalSuggestions(word: String): List<String> {
        val out = mutableListOf<String>()
        contractionSuggestions[word]?.let { out.addAll(it) }
        globalCorrectionSuggestions[word]?.let { out.addAll(it) }
        return out.filterNot { forgottenWords.contains(normalizeLearnedWord(it)) }
    }

    private fun nextWordSuggestions(): List<String> {
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        if (before.isBlank() || before.lastOrNull()?.let { it.isLetterOrDigit() || it == '\'' } == true) return emptyList()
        val words = Regex("[\\p{L}']{2,}").findAll(before).map { normalizeLearnedWord(it.value) }.filter { it.length >= 2 }.toList()
        return nextWordSuggestionsForContext(words)
    }

    private fun nextWordSuggestionsForContext(words: List<String>): List<String> {
        val keys = listOfNotNull(
            words.takeLast(2).takeIf { it.size == 2 }?.joinToString(" "),
            words.lastOrNull()
        )
        val learned = keys.asSequence()
            .flatMap { learnedNextWords[it].orEmpty().entries.asSequence() }
            .filter { !forgottenWords.contains(normalizeLearnedWord(it.key)) }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .toList()
        val fallback = keys.asSequence().flatMap { commonNextWords[it].orEmpty().asSequence() }.toList()
        return (learned + fallback).distinct().filterNotForgotten().take(3)
    }

    private fun List<String>.filterNotForgotten(): List<String> = filterNot { forgottenWords.contains(normalizeLearnedWord(it)) }

    private fun forgetSuggestedWord(word: String) {
        val normalized = normalizeLearnedWord(word)
        if (normalized.length < 2) return
        learnedFreq.remove(normalized)
        learnedCorrections.remove(normalized)
        learnedCorrections.values.forEach { it.remove(normalized) }
        forgottenWords.add(normalized)
        combinedWordEntriesCache = null
        saveLearnedWords()
        saveLearnedCorrections()
        saveForgottenWords()
        voiceStatusText = "Forgot: $word"
        refreshSuggestions()
    }

    private fun prefixSuggestions(prefix: String): List<String> {
        if (prefix.length < 2) return emptyList()
        return combinedWordEntries()
            .filter { it.key.startsWith(prefix) && it.key != prefix && !forgottenWords.contains(normalizeLearnedWord(it.key)) }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(8)
            .toList()
    }

    private fun bestCorrections(word: String, maxDistance: Int): List<String> {
        if (word.length < 2) return emptyList()
        val typoLimit = when {
            word.length <= 4 -> 4
            word.length <= 6 -> 6
            else -> 8
        }
        return combinedWordEntries()
            .filter { (candidate, _) ->
                candidate.length >= 2 && !forgottenWords.contains(normalizeLearnedWord(candidate)) && kotlin.math.abs(candidate.length - word.length) <= maxOf(2, maxDistance)
            }
            .map { (candidate, freq) -> Correction(candidate, editDistanceAtMost(word, candidate, maxDistance + 1), keyboardTypoDistance(word, candidate, typoLimit), freq) }
            .filter { it.keyboardScore <= typoLimit || it.distance <= maxDistance }
            .sortedWith(compareBy<Correction> { it.keyboardScore }.thenBy { it.distance }.thenByDescending { it.frequency })
            .map { it.word }
            .take(5)
            .toList()
    }

    private data class Correction(val word: String, val distance: Int, val keyboardScore: Int, val frequency: Int)

    private fun combinedWordEntries(): Sequence<Map.Entry<String, Int>> {
        val cached = combinedWordEntriesCache
        if (cached != null) return cached.asSequence()
        val merged = HashMap<String, Int>(wordFreq.size + learnedFreq.size)
        wordFreq.forEach { (word, count) -> merged[word] = count }
        learnedFreq.forEach { (word, count) ->
            val boosted = 1_000_000 + count * 20_000
            if ((merged[word] ?: 0) < boosted) merged[word] = boosted
        }
        return merged.entries
            .map { AbstractMap.SimpleEntry(it.key, it.value) as Map.Entry<String, Int> }
            .also { combinedWordEntriesCache = it }
            .asSequence()
    }

    private fun learnWord(raw: String) {
        val word = normalizeLearnedWord(raw)
        if (word.length < 2 || !word.any { it.isLetter() } || forgottenWords.contains(word)) return
        learnedFreq[word] = (learnedFreq[word] ?: 0) + 1
        combinedWordEntriesCache = null
        saveLearnedWords()
    }

    private fun learnWordsFromText(text: String) {
        val words = Regex("[\\p{L}']{2,}").findAll(text).take(40).map { normalizeLearnedWord(it.value) }.filter { it.length >= 2 && it.any { ch -> ch.isLetter() } }.toList()
        words.forEach { learnWord(it) }
        learnNextWordPatterns(words)
    }

    private fun learnNextWordPatterns(words: List<String>) {
        if (words.size < 2) return
        fun bump(context: String, next: String) {
            if (context.isBlank() || next.length < 2 || forgottenWords.contains(next)) return
            val bucket = learnedNextWords.getOrPut(context) { HashMap() }
            bucket[next] = (bucket[next] ?: 0) + 1
        }
        for (i in 0 until words.lastIndex) {
            bump(words[i], words[i + 1])
            if (i + 2 < words.size) bump("${words[i]} ${words[i + 1]}", words[i + 2])
        }
        saveLearnedWords()
    }

    private fun loadLearnedWords() {
        learnedFreq.clear()
        combinedWordEntriesCache = null
        val saved = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("learned_words", "").orEmpty()
        saved.split('|').forEach { item ->
            val parts = item.split(':')
            val word = parts.getOrNull(0)?.lowercase().orEmpty()
            val count = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (word.length >= 2 && count > 0) learnedFreq[word] = count.coerceAtMost(999)
        }
    }

    private fun saveLearnedWords() {
        if (learnedWordsSavePending) return
        learnedWordsSavePending = true
        mainHandler.postDelayed(learnedWordsSaveRunnable, 750L)
    }

    private fun saveLearningPrefsNow() {
        val wordsSaved = learnedFreq.entries
            .sortedByDescending { it.value }
            .take(300)
            .joinToString("|") { "${it.key}:${it.value.coerceAtMost(999)}" }
        val nextSaved = learnedNextWords.entries
            .flatMap { (context, bucket) -> bucket.map { (next, count) -> Triple(context, next, count) } }
            .sortedByDescending { it.third }
            .take(500)
            .joinToString("|") { "${it.first}:${it.second}:${it.third.coerceAtMost(999)}" }
        getSharedPreferences("pc_asr", MODE_PRIVATE).edit()
            .putString("learned_words", wordsSaved)
            .putString("learned_next_words", nextSaved)
            .apply()
    }

    private fun flushPendingLearningSaves() {
        if (!learnedWordsSavePending) return
        mainHandler.removeCallbacks(learnedWordsSaveRunnable)
        learnedWordsSavePending = false
        saveLearningPrefsNow()
    }

    private fun loadForgottenWords() {
        forgottenWords.clear()
        combinedWordEntriesCache = null
        getSharedPreferences("pc_asr", MODE_PRIVATE).getString("forgotten_words", "").orEmpty()
            .split('|')
            .map { normalizeLearnedWord(it) }
            .filter { it.length >= 2 }
            .forEach { forgottenWords.add(it) }
    }

    private fun saveForgottenWords() {
        getSharedPreferences("pc_asr", MODE_PRIVATE).edit()
            .putString("forgotten_words", forgottenWords.take(500).joinToString("|"))
            .apply()
    }

    private fun loadLearnedNextWords() {
        learnedNextWords.clear()
        val saved = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("learned_next_words", "").orEmpty()
        saved.split('|').forEach { item ->
            val parts = item.split(':')
            val context = parts.getOrNull(0)?.lowercase().orEmpty()
            val next = parts.getOrNull(1)?.lowercase().orEmpty()
            val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (context.isNotBlank() && next.length >= 2 && count > 0) learnedNextWords.getOrPut(context) { HashMap() }[next] = count.coerceAtMost(999)
        }
    }

    private fun learnCorrectionIfPending(correctRaw: String) {
        val wrong = pendingMistakeWord ?: return
        pendingMistakeWord = null
        learnCorrection(wrong, correctRaw)
    }

    private fun learnCorrection(wrongRaw: String, correctRaw: String) {
        val wrong = normalizeLearnedWord(wrongRaw)
        val correct = normalizeLearnedWord(correctRaw)
        if (wrong.length < 2 || correct.length < 2 || wrong == correct) return
        if (wordFreq.containsKey(wrong) && !learnedFreq.containsKey(wrong)) return
        val bucket = learnedCorrections.getOrPut(wrong) { HashMap() }
        bucket[correct] = (bucket[correct] ?: 0) + 1
        learnWord(correct)
        saveLearnedCorrections()
        if (verboseMode) voiceStatusText = "Learned: $wrong → $correct"
    }

    private fun learnedCorrectionSuggestions(word: String): List<String> {
        return learnedCorrections[word.lowercase()].orEmpty()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { learnedFreq[it.key] ?: 0 })
            .map { it.key }
            .take(3)
    }

    private fun normalizeLearnedWord(raw: String): String = raw.lowercase().trim(' ', '.', ',', '?', '!', ':', ';', '\n', '\t')

    private fun loadLearnedCorrections() {
        learnedCorrections.clear()
        val saved = getSharedPreferences("pc_asr", MODE_PRIVATE).getString("learned_corrections", "").orEmpty()
        saved.split('|').forEach { item ->
            val parts = item.split(':')
            val wrong = parts.getOrNull(0)?.lowercase().orEmpty()
            val correct = parts.getOrNull(1)?.lowercase().orEmpty()
            val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (wrong.length >= 2 && correct.length >= 2 && count > 0 && wrong != correct) {
                learnedCorrections.getOrPut(wrong) { HashMap() }[correct] = count.coerceAtMost(999)
            }
        }
    }

    private fun saveLearnedCorrections() {
        val saved = learnedCorrections.entries
            .flatMap { (wrong, bucket) -> bucket.map { (correct, count) -> Triple(wrong, correct, count) } }
            .sortedByDescending { it.third }
            .take(300)
            .joinToString("|") { "${it.first}:${it.second}:${it.third.coerceAtMost(999)}" }
        getSharedPreferences("pc_asr", MODE_PRIVATE).edit().putString("learned_corrections", saved).apply()
    }

    private fun keyboardTypoScore(input: String, candidate: String): Int = keyboardTypoDistance(input, candidate, 99)

    private fun keyboardTypoDistance(input: String, candidate: String, limit: Int): Int {
        val prev = IntArray(candidate.length + 1) { it * 3 }
        val curr = IntArray(candidate.length + 1)
        for (i in 1..input.length) {
            curr[0] = i * 3
            var rowMin = curr[0]
            for (j in 1..candidate.length) {
                val subCost = keySubstitutionCost(input[i - 1], candidate[j - 1])
                curr[j] = minOf(
                    prev[j] + 3,       // delete extra typed key
                    curr[j - 1] + 3,   // missing intended key
                    prev[j - 1] + subCost
                )
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > limit + 4) return limit + 1
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[candidate.length]
    }

    private fun keySubstitutionCost(typed: Char, intended: Char): Int {
        val t = typed.lowercaseChar()
        val i = intended.lowercaseChar()
        return when {
            t == i -> 0
            qwertyNeighbors[t]?.contains(i) == true -> 1
            qwertySecondNeighbors[t]?.contains(i) == true -> 2
            numberKeyNeighbors[t]?.contains(i) == true -> 1
            numberKeySecondNeighbors[t]?.contains(i) == true -> 2
            else -> 4
        }
    }

    private fun editDistanceAtMost(a: String, b: String, limit: Int): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > limit) return limit + 1
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    private fun matchCase(original: String, replacement: String): String {
        return if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercase() } else replacement
    }

    private fun loadDictionaryIfNeeded() {
        if (dictionaryLoaded) return
        dictionaryLoaded = true
        try {
            assets.open("english_words_freq.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    val word = parts.getOrNull(0)?.lowercase() ?: return@forEach
                    val freq = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    if (word.length >= 2) wordFreq[word] = freq
                }
            }
        } catch (_: Exception) {
            wordFreq.putAll(autoCorrect.values.associateWith { 1000 })
        }
    }

    private fun deleteSelectedTextIfAny(ic: android.view.inputmethod.InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            // commitText replaces the active selection. This is the behavior users
            // expect after Android's normal long-press / drag text selection handles.
            ic.commitText("", 1)
            lastSpaceAt = 0L
            refreshSuggestions()
            return true
        }
        return false
    }

    private fun sendBackspace(ic: android.view.inputmethod.InputConnection) {
        if (!ic.deleteSurroundingText(1, 0)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun deleteOneCharRememberingWord() {
        if (deleteFromPreview()) return
        val ic = currentInputConnection ?: return
        if (deleteSelectedTextIfAny(ic)) return
        val word = normalizeLearnedWord(currentWord())
        if (word.length >= 2 && !wordFreq.containsKey(word)) pendingMistakeWord = word
        sendBackspace(ic)
    }

    private fun deleteLastWord() {
        if (previewIsFocused()) {
            val text = previewInput.text
            val cursor = previewInput.selectionStart.coerceAtLeast(0)
            if (cursor <= 0) return
            var start = cursor
            while (start > 0 && text[start - 1].isWhitespace()) start--
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            text.delete(start, cursor)
            previewInput.setSelection(start)
            previewLastInsertedText = text.toString()
            previewAiFixedText = ""
            return
        }
        val ic = currentInputConnection ?: return
        if (deleteSelectedTextIfAny(ic)) return
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
        val trimmed = before.trimEnd()
        val trailingSpaces = before.length - trimmed.length
        val deletedWord = trimmed.takeLastWhile { !it.isWhitespace() }
        val wordLen = deletedWord.length
        val normalizedDeleted = normalizeLearnedWord(deletedWord)
        if (normalizedDeleted.length >= 2 && !wordFreq.containsKey(normalizedDeleted)) pendingMistakeWord = normalizedDeleted
        ic.deleteSurroundingText(maxOf(1, trailingSpaces + wordLen), 0)
        if (verboseMode) voiceStatusText = "Deleted last word"
    }

    private fun keyParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = 0; rightMargin = 0 }
    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(2) }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        loadPrefs()
        if (::status.isInitialized && !running.get()) setIdleStatus(if (voiceMode) engineLabel() else if (verboseMode) "Qwen Keyboard" else "")
    }

    private fun startDictation() {
        if (running.get()) return
        loadPrefs()
        queue = LinkedBlockingQueue()
        buffer.clear()
        previewRawText.clear()
        previewAiFixedText = ""
        previewLastInsertedText = ""
        previewAiCandidateText = ""
        clearPreviewAudioFiles()
        previewAudioDir = File(cacheDir, "ime_preview_audio").apply { deleteRecursively(); mkdirs() }
        transcript.text = ""
        if (::previewInput.isInitialized) previewInput.setText("")
        if (::previewAiCandidateInput.isInitialized) previewAiCandidateInput.setText("")
        if (previewModeEnabled && !voiceMode) refreshKeyboardPanel()
        val dir = File(cacheDir, "ime_live_chunks").apply { deleteRecursively(); mkdirs() }
        running.set(true)
        micButton.text = "Stop Dictation"
        if (::recordButton.isInitialized) recordButton.text = "■"
        setIdleStatus("Recording")
        // VAD flushes on short pauses for faster perceived latency; overlap protects boundary words.
        // Short utterances like “how about” need lower VAD threshold/min speech and ASR padding in LiveChunkRecorder.
        val liveChunkMs = selectedChunkSec * 1000L
        val liveOverlapMs = 500L
        recorder = LiveChunkRecorder(dir, queue, chunkMs = liveChunkMs, overlapMs = liveOverlapMs, vadEnabled = true).also { it.start() }
        worker = Thread({ transcribeLoop(queue) }, "qwen-ime-pc-asr").also { it.start() }
    }

    private fun stopDictation() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder = null
        micButton.text = "Start Dictation"
        if (::recordButton.isInitialized) recordButton.text = "●"
        setIdleStatus(if (verboseMode) "Stopping… finishing queued chunks" else "")
    }


    private fun collectLearningPayload(): JSONObject {
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val prefJson = JSONObject()
        for (key in listOf(
            "learned_words",
            "forgotten_words",
            "learned_corrections",
            "preview_phrase_corrections",
            "zh_mode",
            "engine",
            "preview_ai_fix_mode",
            "preview_ai_fix_model"
        )) {
            val value = prefs.all[key]
            if (value != null) prefJson.put(key, value.toString())
        }
        val previewFile = File(filesDir, "preview_learning/preview_corrections.jsonl")
        return JSONObject()
            .put("schema", "qwen_keyboard_learning_upload.v1")
            .put("created_ts", System.currentTimeMillis())
            .put("source", "dee-keyboard")
            .put("package", packageName)
            .put("version", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
            .put("prefs", prefJson)
            .put("preview_corrections_jsonl", if (previewFile.isFile) previewFile.readText().takeLast(900_000) else "")
    }

    private fun uploadLearningLibrary() {
        voiceStatusText = "Uploading learning…"
        Thread({
            try {
                val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
                val baseUrl = prefs.getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com"
                val token = prefs.getString("token", "") ?: ""
                if (token.isBlank()) throw IllegalStateException("PC ASR token is empty. Set token before uploading learning.")
                val result = PcAsrClient(baseUrl = baseUrl, token = token, engine = engineName, chunkSec = 0)
                    .uploadLearning(collectLearningPayload())
                postUi { voiceStatusText = "Learning uploaded: ${result.previewLines} preview rows, ${formatBytes(result.bytes)}" }
            } catch (t: Throwable) {
                Log.e("QwenKeyboard", "Learning upload failed", t)
                postUi { voiceStatusText = "Learning upload failed: ${(t.message ?: t.javaClass.simpleName).take(140)}" }
            }
        }, "qwen-learning-upload").start()
    }

    private fun transcribeLoop(runQueue: LinkedBlockingQueue<LiveChunk>) {
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val usePhoneLocal = engineName == "phone_qwen_0_6b" || engineName == SenseVoiceModelManager.ENGINE_NAME || engineName == SenseVoiceModelManager.ENGINE_NAME_2024 || engineName == MoonshineModelManager.ENGINE_BASE || engineName == ParakeetModelManager.ENGINE_TDT_V3
        val baseUrl = prefs.getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com"
        val token = prefs.getString("token", "") ?: ""
        val client = if (usePhoneLocal) null else PcAsrClient(baseUrl = baseUrl, token = token, engine = engineName, chunkSec = 0)
        val textFixClient = PcAsrClient(baseUrl = baseUrl, token = token, engine = engineName, chunkSec = 0)
        val df = DecimalFormat("0.0")
        while (running.get() || runQueue.isNotEmpty()) {
            val chunk = runQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val started = System.currentTimeMillis()
            postUi { voiceStatusText = "Transcribing" }
            try {
                if (previewModeEnabled) preservePreviewAudioChunk(chunk)
                val rawText: String
                val detail: String
                if (usePhoneLocal) {
                    val result = transcribePhoneLocalWithGuard(chunk)
                    rawText = result.transcript
                    detail = "phone ${df.format(result.processingMs / 1000.0)}s"
                } else {
                    val result = client!!.transcribe(chunk.file)
                    rawText = result.text
                    detail = "server ${result.serverElapsedMs} ms"
                }
                var finalRawText = rawText
                var finalDetail = detail
                if (engineName == "phone_qwen_0_6b" && finalRawText.isBlank()) {
                    val fallback = recoverBlankLocalQwen(chunk)
                    finalRawText = fallback.first
                    finalDetail = fallback.second
                }
                var cleanedText = postProcessVoiceText(finalRawText.trim())
                cleanedText = convertChinese(cleanedText)
                if (engineName == "phone_qwen_0_6b" && cleanedText.isBlank()) {
                    val fallback = recoverBlankLocalQwen(chunk)
                    finalRawText = fallback.first
                    finalDetail = fallback.second
                    cleanedText = postProcessVoiceText(finalRawText.trim())
                    cleanedText = convertChinese(cleanedText)
                }
                val elapsed = System.currentTimeMillis() - started
                val text = trimOverlappingChunkPrefix(buffer.toString(), cleanedText)
                if (text.isNotBlank()) {
                    if (previewModeEnabled) {
                        appendPreviewText(text, rawText)
                    } else {
                        currentInputConnection?.commitText(text + " ", 1)
                        buffer.append(text).append(' ')
                        learnWordsFromText(text)
                    }
                }
                postUi {
                    transcript.text = buffer.toString().ifBlank { "(No speech detected yet)" }
                    voiceStatusText = if (verboseMode) { if (previewModeEnabled) "Preview updated ($finalDetail)" else "Inserted ($finalDetail)" } else ""
                }
            } catch (t: Throwable) {
                postUi { voiceStatusText = "ASR error: ${friendlyAsrError(t).take(220)}" }
                Log.e("QwenKeyboard", "ASR loop failed", t)
            } finally {
                chunk.file.delete()
            }
        }
        finalizeVoicePunctuationAfterChunks(textFixClient)
        postUi {
            micButton.text = "Start Dictation"
            if (::recordButton.isInitialized) recordButton.text = "●"
            setIdleStatus(if (verboseMode) "Dictation stopped" else "")
        }
    }

    private fun finalizeVoicePunctuationAfterChunks(client: PcAsrClient?) {
        val originalWithSpace = buffer.toString()
        val original = originalWithSpace.trim()
        if (original.isBlank() || (voicePunctuationMode == "off" && !voiceAiTextCorrectionEnabled)) return
        val fixed = applyFinalVoicePunctuation(original, client).trim()
        if (fixed.isBlank() || fixed == original) return
        if (previewModeEnabled) {
            buffer.clear(); buffer.append(fixed).append(' ')
            previewLastInsertedText = fixed
            postUi {
                if (::previewInput.isInitialized) {
                    previewInput.setText(fixed)
                    previewInput.setSelection(previewInput.text.length)
                }
                transcript.text = fixed
                voiceStatusText = if (voicePunctuationMode == "cloud_ai" || voiceAiTextCorrectionEnabled) "AI correction applied" else "Punctuation applied"
            }
        } else {
            val deleteChars = originalWithSpace.length
            buffer.clear(); buffer.append(fixed).append(' ')
            postUi {
                currentInputConnection?.apply {
                    deleteSurroundingText(deleteChars, 0)
                    commitText(fixed + " ", 1)
                }
                transcript.text = fixed
                voiceStatusText = if (voicePunctuationMode == "cloud_ai" || voiceAiTextCorrectionEnabled) "AI correction applied" else "Punctuation applied"
            }
        }
    }

    private fun applyFinalVoicePunctuation(text: String, client: PcAsrClient?): String {
        val wantsPunctuation = voicePunctuationMode != "off"
        val wantsTextCorrection = voiceAiTextCorrectionEnabled
        return when {
            voicePunctuationMode == "cloud_ai" || wantsTextCorrection -> applyAlibabaVoiceCorrection(text, punctuate = wantsPunctuation, correctText = wantsTextCorrection)
            voicePunctuationMode == "rules" -> addRulePunctuationFallback(text)
            else -> text
        }
    }

    private fun applyAlibabaVoiceCorrection(text: String, punctuate: Boolean, correctText: Boolean): String {
        val key = alibabaApiKey.trim()
        if (key.isBlank()) {
            postUi { voiceStatusText = "Alibaba key missing; offline fallback" }
            return offlineVoiceCorrectionFallback(text, punctuate, correctText)
        }
        val languageHint = when {
            zhMode == "trad" -> "Chinese output rule: use Traditional Chinese. If the speech is Cantonese, keep it Cantonese-focused and colloquial: prefer 係咪, 唔係, 冇, 嘅, 咗, 嚟, 啲, etc. Do not convert Cantonese into Mandarin-style wording."
            zhMode == "simp" -> "Chinese output rule: use Simplified Chinese characters. Preserve Cantonese wording if that is what was spoken, but write it in Simplified form where applicable."
            else -> "Chinese output rule: as spoken / standard. Preserve the spoken language and original script as much as possible. Use standard/common Chinese characters only for obvious ASR character mistakes. Do not force Traditional or Simplified conversion."
        }
        val preservationRules = """
Preservation rules:
- Keep the transcript sounding like the person who spoke it.
- Keep spoken fillers and discourse particles such as um, uh, er, ah, ar, 嗯, 啊, 呀, 啦, 喇, 咧.
- Do not remove fillers, false starts, or casual phrasing unless there is an obvious duplicated ASR glitch.
- Do not paraphrase, summarize, translate, or make the speaker sound more formal.
- Fix only obvious ASR spelling/capitalization/character mistakes.
""".trimIndent()
        val punctuationRules = if (punctuate) """
Punctuation rules:
- Add punctuation at proper places by meaning, not by ASR chunks.
- Prefer commas for continuing thoughts; use a full stop only when the thought is complete.
- Use ? only for real questions. In Cantonese, question patterns include 未, 係咪, 有冇, 去唔去, 可唔可以, 點解, 幾時, 邊個, 邊度.
- Do not add random question marks just because the sentence ends with 啊/呀/喇/啦/呢/咧.
- Mixed Chinese/English dictation is allowed; punctuate each language naturally without translating.
""".trimIndent() else ""
        val task = when {
            punctuate && correctText -> "Format this voice dictation transcript: preserve spoken wording/fillers, add punctuation/capitalization, and fix obvious ASR spelling or Chinese character mistakes only."
            punctuate -> "Add punctuation and capitalization only while preserving every spoken word/filler."
            correctText -> "Fix obvious ASR spelling/capitalization/Chinese character mistakes only while preserving spoken wording/fillers."
            else -> "Return the text unchanged."
        }
        val examples = """
Examples:
Input: um I think ah we should go tomorrow and then maybe after lunch we can meet them uh and then we can go back home and chill
Output: Um, I think ah, we should go tomorrow, and then maybe after lunch we can meet them, uh, and then we can go back home and chill.
Input: 食咗飯未啊 聽日去唔去街啊 跟住之後系咪去踢波咧
Traditional Cantonese output: 食咗飯未啊？聽日去唔去街啊？跟住之後係咪去踢波咧？
Simplified output: 食咗饭未啊？听日去唔去街啊？跟住之后系咪去踢波咧？
""".trimIndent()
        val userPrompt = listOf(task, preservationRules, languageHint, punctuationRules, examples, "Return only the final transcript text. No explanations.", "Text:\n$text")
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return try {
            postUi { voiceStatusText = "Alibaba AI correcting… ${alibabaModelDisplay(alibabaModel)}" }
            val fixed = callAlibabaModelStudioCorrection(key, alibabaModel, userPrompt).trim().removeSurrounding("\"").trim()
            fixed.ifBlank { offlineVoiceCorrectionFallback(text, punctuate, correctText) }
        } catch (t: Throwable) {
            Log.w("QwenKeyboard", "Alibaba Model Studio correction failed", t)
            postUi { voiceStatusText = "Alibaba AI failed; offline fallback: ${t.message.orEmpty().take(60)}" }
            offlineVoiceCorrectionFallback(text, punctuate, correctText)
        }
    }

    private fun alibabaModelDisplay(model: String): String = when (model) {
        "qwen3.6-flash" -> "Qwen3.6 Flash"
        else -> "Qwen3.6 Plus"
    }

    private fun callAlibabaModelStudioCorrection(key: String, model: String, userPrompt: String): String {
        val url = java.net.URL("https://cn-hongkong.dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        val conn = (url.openConnection() as java.net.HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.0)
            .put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a conservative voice dictation cleanup engine for Cantonese, Chinese, and English. Return only the corrected transcript. No explanations, no quotes, no markdown, no reasoning. Preserve meaning and language mix."))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299 || !resp.trimStart().startsWith("{")) throw RuntimeException("Alibaba HTTP $code")
        val json = JSONObject(resp)
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun offlineVoiceCorrectionFallback(input: String, punctuate: Boolean, correctText: Boolean): String {
        var t = input.trim()
        if (t.isBlank()) return t
        if (correctText) {
            t = t.replace(Regex("(?i)\\btt'?s\\b"), "it's")
                .replace(Regex("(?i)\\bpunctution\\b"), "punctuation")
                .replace(Regex("(?i)\\bpuncutation\\b"), "punctuation")
                .replace(Regex("(?i)\\blets\\b"), "let's")
                .replace("系咪", if (zhMode == "trad") "係咪" else "系咪")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (t == t.uppercase() && t.any { it.isLetter() }) t = t.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        if (punctuate) t = addRulePunctuationFallback(t)
        return t
    }

    private fun recoverBlankLocalQwen(chunk: LiveChunk): Pair<String, String> {
        Log.w("QwenKeyboard", "Local Qwen 0.6B returned blank; trying fallback for ${chunk.file.name} durationMs=${chunk.durationMs}")
        postUi { voiceStatusText = "Local Qwen returned blank; retrying fallback…" }
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val baseUrl = prefs.getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com"
        val token = prefs.getString("token", "") ?: ""
        if (token.isNotBlank()) {
            try {
                val pc = PcAsrClient(baseUrl = baseUrl, token = token, engine = "qwen_1_7b", chunkSec = 0).transcribe(chunk.file).text.trim()
                if (pc.isNotBlank()) return pc to "Local Qwen blank → PC Qwen 1.7"
            } catch (t: Throwable) {
                Log.w("QwenKeyboard", "PC Qwen fallback failed", t)
            }
        }
        val fallbackLocal = when {
            MoonshineModelManager.isInstalled(filesDir, MoonshineModelManager.ENGINE_BASE) -> MoonshineModelManager.ENGINE_BASE
            ParakeetModelManager.isInstalled(filesDir, ParakeetModelManager.ENGINE_TDT_V3) -> ParakeetModelManager.ENGINE_TDT_V3
            SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024) -> SenseVoiceModelManager.ENGINE_NAME_2024
            SenseVoiceModelManager.isInstalled(filesDir, SenseVoiceModelManager.ENGINE_NAME) -> SenseVoiceModelManager.ENGINE_NAME
            else -> null
        }
        if (fallbackLocal != null) {
            return try {
                val r = phoneLocalEngine(fallbackLocal).transcribe(chunk.file, chunk.durationMs).transcript.trim()
                if (r.isNotBlank()) r to "Local Qwen blank → ${shortModelDisplay(fallbackLocal)}"
                else throw IllegalStateException("fallback also returned blank")
            } catch (t: Throwable) {
                localAsrEngine = null
                localAsrEngineName = null
                throw t
            }
        }
        throw RuntimeException("Local Qwen 0.6B returned blank text and no fallback ASR is available.")
    }

    private fun transcribePhoneLocalWithGuard(chunk: LiveChunk): BenchmarkResult {
        if (engineName != "phone_qwen_0_6b") return phoneLocalEngine().transcribe(chunk.file, chunk.durationMs)
        val timeoutMs = when {
            chunk.durationMs <= 2_500L -> 60_000L
            chunk.durationMs <= 5_000L -> 90_000L
            else -> 120_000L
        }
        postUi { voiceStatusText = "Transcribing Local Qwen 0.6B… ${selectedChunkSec}s chunks" }
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit<BenchmarkResult> { phoneLocalEngine().transcribe(chunk.file, chunk.durationMs) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            localAsrEngine = null
            localAsrEngineName = null
            throw RuntimeException("Local Qwen 0.6B timed out after ${timeoutMs / 1000}s. Use Local SenseVoice or PC Qwen 1.7 for now.", e)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun appendPreviewText(cleanedText: String, rawText: String) {
        val old = buffer.toString()
        val incoming = cleanedText.trim()
        if (incoming.isBlank()) return
        buffer.append(incoming).append(' ')
        previewRawText.append(rawText.trim()).append(' ')
        previewLastInsertedText = buffer.toString().trim()
        postUi {
            if (::previewInput.isInitialized) {
                previewInput.setText(previewLastInsertedText)
                previewInput.setSelection(previewInput.text.length)
            }
            if (previewAiFixMode == "auto") applyPreviewAiFix(manual = false)
        }
        if (old.isBlank() && verboseMode) postUi { voiceStatusText = "Preview mode: edit then Insert" }
    }

    private fun applyPreviewAiFix(manual: Boolean) {
        if (previewAiFixMode == "off") {
            if (manual) voiceStatusText = "AI Fix is off. Enable Manual or Auto in settings."
            return
        }
        val before = if (::previewInput.isInitialized) previewInput.text.toString() else previewLastInsertedText
        if (before.isBlank()) {
            if (manual) voiceStatusText = "Nothing to fix yet."
            return
        }
        previewLastInsertedText = before
        if (previewAiFixModel.startsWith("fix_")) {
            val fixEngine = previewAiFixModel.removePrefix("fix_")
            voiceStatusText = "Re-transcribing full clip with ${shortModelDisplay(fixEngine)}…"
            Thread({
                try {
                    val fullClip = buildFullPreviewWav() ?: throw IllegalStateException("No saved preview audio yet")
                    val candidate = transcribePreviewFullClip(fullClip, fixEngine)
                    postUi { applyPreviewFixResult(before, candidate, "${shortModelDisplay(fixEngine)} full clip") }
                } catch (t: Throwable) {
                    val fallback = fixPreviewTextWithRules(before)
                    postUi {
                        if (fallback != before) applyPreviewFixResult(before, fallback, "Re-transcribe unavailable; used Rules")
                        else voiceStatusText = "Re-transcribe unavailable: ${(t.message ?: t.javaClass.simpleName).take(90)}"
                    }
                }
            }, "qwen-preview-asr-fix").start()
        } else {
            val fixed = fixPreviewTextWithRules(before)
            applyPreviewFixResult(before, fixed, "Rules")
        }
    }

    private fun applyPreviewFixResult(before: String, fixed: String, label: String) {
        val converted = convertChinese(fixed)
        previewAiFixedText = converted
        previewAiCandidateText = converted
        if (::previewAiCandidateInput.isInitialized) setAiCandidateTextWithHighlights(before, converted)
        voiceStatusText = if (converted == before) "$label: no change" else "$label: candidate in red"
    }

    private fun setAiCandidateTextWithHighlights(before: String, fixed: String) {
        val span = SpannableString(fixed)
        for ((start, end) in changedRanges(before, fixed)) {
            if (start < end && start in fixed.indices) span.setSpan(ForegroundColorSpan(0xFFFF4D4D.toInt()), start, end.coerceAtMost(fixed.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        previewAiCandidateInput.setText(span)
        previewAiCandidateInput.setSelection(0, previewAiCandidateInput.text.length)
    }

    private fun useAllAiCandidate() {
        val candidate = if (::previewAiCandidateInput.isInitialized) previewAiCandidateInput.text.toString().trim() else previewAiCandidateText.trim()
        if (candidate.isBlank()) {
            voiceStatusText = "No AI candidate yet. Tap AI first."
            return
        }
        if (::previewInput.isInitialized) {
            previewLastInsertedText = previewInput.text.toString()
            previewInput.setText(candidate)
            previewInput.setSelection(previewInput.text.length)
            previewInput.requestFocus()
        }
        voiceStatusText = "Applied full AI candidate"
    }

    private fun useSelectedAiCandidate() {
        if (!::previewAiCandidateInput.isInitialized || !::previewInput.isInitialized) return
        val start = previewAiCandidateInput.selectionStart.coerceAtLeast(0)
        val end = previewAiCandidateInput.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val selected = if (hi > lo) previewAiCandidateInput.text.subSequence(lo, hi).toString() else ""
        if (selected.isBlank()) {
            voiceStatusText = "Select text in AI candidate first."
            return
        }
        previewInput.requestFocus()
        insertIntoPreview(selected)
        voiceStatusText = "Inserted selected AI text"
    }

    private fun preservePreviewAudioChunk(chunk: LiveChunk) {
        try {
            val dir = previewAudioDir ?: File(cacheDir, "ime_preview_audio").apply { mkdirs() }.also { previewAudioDir = it }
            val out = File(dir, "chunk-${chunk.index}.wav")
            chunk.file.copyTo(out, overwrite = true)
            previewAudioChunks.add(out)
            while (previewAudioChunks.size > 60) previewAudioChunks.removeAt(0).delete()
        } catch (_: Exception) {}
    }

    private fun clearPreviewAudioFiles() {
        previewAudioChunks.forEach { try { it.delete() } catch (_: Exception) {} }
        previewAudioChunks.clear()
        previewAudioDir?.deleteRecursively()
        previewAudioDir = null
    }

    private fun buildFullPreviewWav(): File? {
        val chunks = previewAudioChunks.filter { it.isFile && it.length() > 44 }.sortedBy { it.name }
        if (chunks.isEmpty()) return null
        val dir = previewAudioDir ?: File(cacheDir, "ime_preview_audio").apply { mkdirs() }.also { previewAudioDir = it }
        val out = File(dir, "full-preview.wav")
        val dataSize = chunks.sumOf { maxOf(0L, it.length() - 44L) }
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            writeWavHeader(raf, dataSize)
            for (chunk in chunks) {
                chunk.inputStream().use { input ->
                    input.skip(44)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        raf.write(buffer, 0, n)
                    }
                }
            }
        }
        return out
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataSize: Long) {
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeLeInt((dataSize + 36).toInt())
        raf.writeBytes("WAVEfmt ")
        raf.writeLeInt(16)
        raf.writeLeShort(1)
        raf.writeLeShort(1)
        raf.writeLeInt(16_000)
        raf.writeLeInt(16_000 * 2)
        raf.writeLeShort(2)
        raf.writeLeShort(16)
        raf.writeBytes("data")
        raf.writeLeInt(dataSize.toInt())
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

    private fun transcribePreviewFullClip(wav: File, fixEngine: String): String {
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val localFixEngine = if (fixEngine == "phone_sensevoice_yue") SenseVoiceModelManager.ENGINE_NAME else fixEngine
        val raw = if (localFixEngine == "phone_qwen_0_6b" || localFixEngine == SenseVoiceModelManager.ENGINE_NAME || localFixEngine == SenseVoiceModelManager.ENGINE_NAME_2024 || localFixEngine == MoonshineModelManager.ENGINE_BASE || localFixEngine == ParakeetModelManager.ENGINE_TDT_V3) {
            phoneLocalEngine(localFixEngine).transcribe(wav, 0L).transcript
        } else {
            val baseUrl = prefs.getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com"
            val token = prefs.getString("token", "") ?: ""
            PcAsrClient(baseUrl = baseUrl, token = token, engine = localFixEngine, chunkSec = 0).transcribe(wav).text
        }
        return convertChinese(postProcessVoiceText(raw.trim()))
    }

    private fun setPreviewTextWithAiHighlights(before: String, fixed: String) {
        val span = SpannableString(fixed)
        val changed = changedRanges(before, fixed)
        for ((start, end) in changed) {
            if (start < end && start in fixed.indices) {
                span.setSpan(ForegroundColorSpan(0xFFFF4D4D.toInt()), start, end.coerceAtMost(fixed.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        previewInput.setText(span)
        previewInput.setSelection(previewInput.text.length)
    }

    private fun changedRanges(before: String, after: String): List<Pair<Int, Int>> {
        if (after.isBlank()) return emptyList()
        var prefix = 0
        val minLen = minOf(before.length, after.length)
        while (prefix < minLen && before[prefix] == after[prefix]) prefix++
        var beforeSuffix = before.length
        var afterSuffix = after.length
        while (beforeSuffix > prefix && afterSuffix > prefix && before[beforeSuffix - 1] == after[afterSuffix - 1]) {
            beforeSuffix--
            afterSuffix--
        }
        if (prefix == afterSuffix && before.length == after.length) return emptyList()
        return listOf(prefix to afterSuffix.coerceAtLeast(prefix))
    }

    private fun undoPreviewAiFix() {
        if (previewLastInsertedText.isBlank()) {
            voiceStatusText = "No previous preview text to restore."
            return
        }
        if (::previewInput.isInitialized) {
            previewInput.setText(previewLastInsertedText)
            previewInput.setSelection(previewInput.text.length)
        }
        previewAiFixedText = ""
        previewAiCandidateText = ""
        if (::previewAiCandidateInput.isInitialized) previewAiCandidateInput.setText("")
        voiceStatusText = "Restored pre-fix preview"
    }

    private fun insertPreviewText() {
        val finalText = if (::previewInput.isInitialized) previewInput.text.toString().trim() else previewLastInsertedText.trim()
        if (finalText.isBlank()) {
            voiceStatusText = "Preview is empty."
            return
        }
        currentInputConnection?.commitText(finalText + " ", 1)
        logPreviewLearning(finalText)
        learnWordsFromText(finalText)
        previewRawText.clear()
        previewAiFixedText = ""
        previewAiCandidateText = ""
        previewLastInsertedText = ""
        buffer.clear()
        clearPreviewAudioFiles()
        if (::previewInput.isInitialized) {
            previewInput.setText("")
            previewInput.clearFocus()
        }
        previewEditActive = false
        if (::previewAiCandidateInput.isInitialized) {
            previewAiCandidateInput.setText("")
            previewAiCandidateInput.clearFocus()
        }
        transcript.text = "Inserted preview."
        voiceStatusText = "Inserted — keyboard returned to app"
    }

    private fun clearPreviewText() {
        val current = if (::previewInput.isInitialized) previewInput.text.toString().trim() else previewLastInsertedText.trim()
        if (current.isNotBlank()) logPreviewLearning(finalText = "", action = "clear")
        previewRawText.clear()
        previewAiFixedText = ""
        previewAiCandidateText = ""
        previewLastInsertedText = ""
        buffer.clear()
        clearPreviewAudioFiles()
        if (::previewInput.isInitialized) {
            previewInput.setText("")
            previewInput.clearFocus()
        }
        previewEditActive = false
        if (::previewAiCandidateInput.isInitialized) {
            previewAiCandidateInput.setText("")
            previewAiCandidateInput.clearFocus()
        }
        transcript.text = "Preview cleared."
        voiceStatusText = "Cleared preview — keyboard returned to app"
    }

    private fun fixPreviewTextWithRules(input: String): String {
        var text = postProcessVoiceText(input)
        text = applyLearnedPhraseCorrections(text)
        text = preserveCommonVoiceTerms(text)
        text = convertChinese(text)
        if (voicePunctuationMode == "rules" && text.isNotBlank() && text.last() !in ".?!。？！") text += "。"
        return text.trim()
    }

    private fun applyLearnedPhraseCorrections(input: String): String {
        var out = input
        // Promote repeated preview/user corrections into conservative exact phrase replacements.
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        prefs.getString("preview_phrase_corrections", "").orEmpty().split('|').forEach { item ->
            val parts = item.split("=>")
            val wrong = parts.getOrNull(0).orEmpty()
            val correct = parts.getOrNull(1).orEmpty()
            if (wrong.length >= 2 && correct.isNotBlank()) out = out.replace(wrong, correct)
        }
        for ((wrong, bucket) in learnedCorrections) {
            val best = bucket.entries.maxByOrNull { it.value } ?: continue
            if (best.value >= 2 && wrong.length >= 2) {
                out = out.replace(Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE), best.key)
            }
        }
        return out
    }

    private fun preserveCommonVoiceTerms(input: String): String {
        val terms = mapOf(
            "q wen" to "Qwen", "qwen" to "Qwen", "sense voice" to "SenseVoice", "sensevoice" to "SenseVoice",
            "cloud flare" to "Cloudflare", "cloudflare" to "Cloudflare", "telegram" to "Telegram",
            "android" to "Android", "open claw" to "OpenClaw", "openclaw" to "OpenClaw"
        )
        var out = input
        for ((from, to) in terms) out = out.replace(Regex("(?i)${Regex.escape(from)}"), to)
        return out
    }

    private fun logPreviewLearning(finalText: String, action: String = "insert") {
        try {
            val raw = previewRawText.toString().trim()
            val aiFixed = previewAiFixedText.trim()
            val beforeFinal = previewLastInsertedText.trim()
            val json = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("action", action)
                .put("engine", engineName)
                .put("preview_ai_fix_mode", previewAiFixMode)
                .put("preview_ai_fix_model", previewAiFixModel)
                .put("raw_asr", raw)
                .put("pre_fix", beforeFinal)
                .put("ai_fixed", aiFixed)
                .put("ai_candidate", previewAiCandidateText.trim())
                .put("primary_engine", engineName)
                .put("fix_engine", previewAiFixModel)
                .put("user_final", finalText)
            val dir = File(filesDir, "preview_learning").apply { mkdirs() }
            File(dir, "preview_corrections.jsonl").appendText(json.toString() + "\n")
            learnPreviewPhraseCorrection(beforeFinal, finalText)
            if (aiFixed.isNotBlank()) learnPreviewPhraseCorrection(aiFixed, finalText)
        } catch (_: Exception) {}
    }

    private fun learnPreviewPhraseCorrection(from: String, to: String) {
        val wrong = from.trim()
        val correct = to.trim()
        if (wrong.length < 2 || correct.length < 2 || wrong == correct) return
        // Keep only short exact replacements here. Full sentence learning is logged
        // for future training, but not auto-applied to avoid dangerous rewrites.
        if (wrong.length > 32 || correct.length > 32) return
        val prefs = getSharedPreferences("pc_asr", MODE_PRIVATE)
        val existing = prefs.getString("preview_phrase_corrections", "").orEmpty()
            .split('|')
            .filter { it.contains("=>") }
            .toMutableList()
        val pair = "$wrong=>$correct"
        if (!existing.contains(pair)) existing.add(pair)
        prefs.edit().putString("preview_phrase_corrections", existing.takeLast(80).joinToString("|")).apply()
    }

    private fun phoneLocalEngine(requestedEngineName: String = engineName): LocalAsrEngine {
        localAsrEngine?.takeIf { localAsrEngineName == requestedEngineName }?.let { return it }
        val engine = when (requestedEngineName) {
            "phone_qwen_0_6b" -> {
                val modelRoot = File(filesDir, "models/qwen3-asr-0.6b-onnx-cpu")
                if (!modelRoot.exists()) {
                    throw IllegalStateException("Phone Qwen 0.6B model missing in keyboard app storage: ${modelRoot.absolutePath}. Copy/import the model into this package, or use PC 0.6B.")
                }
                QwenRealAsrEngine(filesDir)
            }
            SenseVoiceModelManager.ENGINE_NAME -> SenseVoiceAsrEngine(filesDir, modelEngineName = requestedEngineName)
            SenseVoiceModelManager.ENGINE_NAME_2024 -> SenseVoiceAsrEngine(filesDir, modelEngineName = requestedEngineName)
            MoonshineModelManager.ENGINE_BASE -> MoonshineAsrEngine(filesDir, modelEngineName = requestedEngineName)
            ParakeetModelManager.ENGINE_TDT_V3 -> ParakeetAsrEngine(filesDir, modelEngineName = requestedEngineName)
            else -> throw IllegalStateException("Unsupported phone-local engine: $requestedEngineName")
        }
        localAsrEngineName = requestedEngineName
        localAsrEngine = engine
        return engine
    }


    private fun friendlyAsrError(t: Throwable): String {
        val chain = generateSequence(t as Throwable?) { it.cause }
            .take(4)
            .joinToString(" | ") { e ->
                val msg = e.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (msg.isBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $msg"
            }
        val modelHint = when (engineName) {
            SenseVoiceModelManager.ENGINE_NAME -> " [Local SenseVoice 2025: ${SenseVoiceModelManager.modelRoot(filesDir, SenseVoiceModelManager.ENGINE_NAME).name}]"
            SenseVoiceModelManager.ENGINE_NAME_2024 -> " [Local SenseVoice 2024: ${SenseVoiceModelManager.modelRoot(filesDir, SenseVoiceModelManager.ENGINE_NAME_2024).name}]"
            MoonshineModelManager.ENGINE_BASE -> " [Local Moonshine Base: ${MoonshineModelManager.modelRoot(filesDir, MoonshineModelManager.ENGINE_BASE).name}]"
            ParakeetModelManager.ENGINE_TDT_V3 -> " [Local Parakeet TDT: ${ParakeetModelManager.modelRoot(filesDir, ParakeetModelManager.ENGINE_TDT_V3).name}]"
            "phone_qwen_0_6b" -> " [Local Qwen 0.6B: qwen3-asr-0.6b-onnx-cpu]"
            else -> ""
        }
        return chain + modelHint
    }

    private fun setIdleStatus(text: String) { updateVoiceStatusPanel(text) }

    private fun updateVoiceStatusPanel(requested: String = "") {
        if (!::status.isInitialized) return
        val normalized = requested.trim().lowercase()
        status.text = when {
            normalized.startsWith("recording") || normalized.contains("recording") -> "Recording"
            normalized.contains("transcribing") || normalized.contains("re-transcribing") || normalized.contains("stopping") -> "Transcribing"
            running.get() -> "Recording"
            requested.isNotBlank() -> requested
            else -> voiceIdleLabel()
        }
    }

    private fun voiceIdleLabel(): String = if (isLandscapeLayout()) {
        "${voiceSourceLabel()} · ${voiceModelNameLabel(engineName)} · ${selectedChunkSec}s"
    } else {
        "${voiceSourceLabel()}\n${voiceModelNameLabel(engineName)}\n${selectedChunkSec}s"
    }

    private fun voiceSourceLabel(): String = if (isLocalVoiceEngine(engineName)) "Local" else "PC"

    private fun isLocalVoiceEngine(model: String): Boolean = model == SenseVoiceModelManager.ENGINE_NAME || model == SenseVoiceModelManager.ENGINE_NAME_2024 || model == MoonshineModelManager.ENGINE_BASE || model == ParakeetModelManager.ENGINE_TDT_V3 || model == "phone_qwen_0_6b" || model.startsWith("phone_")

    private fun voiceModelNameLabel(model: String): String = when (model) {
        SenseVoiceModelManager.ENGINE_NAME -> "SenseVoice_Yue_2025"
        SenseVoiceModelManager.ENGINE_NAME_2024 -> "SenseVoice_2024"
        MoonshineModelManager.ENGINE_BASE -> "Moonshine_Base_EN"
        ParakeetModelManager.ENGINE_TDT_V3 -> "Parakeet_TDT_0.6B"
        "phone_qwen_0_6b" -> "Qwen_0.6B"
        "sensevoice_yue_2025" -> "SenseVoice_Yue_2025"
        "qwen_0_6b" -> "Qwen_0.6B"
        "qwen_1_7b" -> "Qwen_1.7B"
        "doubao_asr" -> "Doubao_ASR"
        else -> model.replace(' ', '_').replace(':', '_')
    }

    private fun zhLabel() = when (zhMode) { "trad" -> "繁中"; "simp" -> "简中"; else -> "原文" }
    private fun engineLabel() = voiceIdleLabel()
    private fun shortModelDisplay(model: String) = when (model) {
        MoonshineModelManager.ENGINE_BASE -> "Local:MoonBase"; ParakeetModelManager.ENGINE_TDT_V3 -> "Local:Parakeet"; SenseVoiceModelManager.ENGINE_NAME -> "Local:Sense25"; SenseVoiceModelManager.ENGINE_NAME_2024 -> "Local:Sense24"; "phone_qwen_0_6b" -> "Local:Qwen"; "sensevoice_yue_2025" -> "PC:Sense粵"; "qwen_0_6b" -> "PC:Qwen0.6b"; "qwen_1_7b" -> "PC:Qwen1.7b"; "doubao_asr" -> "Cloud:Doubao"; else -> modelDisplay(model)
    }
    private fun modelButtonDisplay(model: String) = when (model) {
        MoonshineModelManager.ENGINE_BASE -> "Local Moon\nBase"; ParakeetModelManager.ENGINE_TDT_V3 -> "Local Para\nkeet"; SenseVoiceModelManager.ENGINE_NAME -> "Local Sense\nYue"; "phone_qwen_0_6b" -> "Local Qwen\n0.6"; "sensevoice_yue_2025" -> "PC Sense\nYue"; "qwen_0_6b" -> "PC Qwen\n0.6"; "qwen_1_7b" -> "PC Qwen\n1.7"; "doubao_asr" -> "Cloud\nDoubao"; else -> modelDisplay(model)
    }
    private fun modelDisplay(model: String) = when (model) {
        MoonshineModelManager.ENGINE_BASE -> "Local: Moonshine Base EN"; ParakeetModelManager.ENGINE_TDT_V3 -> "Local: Parakeet TDT 0.6B"; SenseVoiceModelManager.ENGINE_NAME -> "Local: SenseVoice Yue 2025"; SenseVoiceModelManager.ENGINE_NAME_2024 -> "Local: SenseVoice 2024"; "phone_qwen_0_6b" -> "Local: Qwen 0.6B"; "sensevoice_yue_2025" -> "PC: SenseVoice 粵 2025"; "qwen_0_6b" -> "PC: Qwen 0.6B"; "qwen_1_7b" -> "PC: Qwen 1.7B"; "doubao_asr" -> "Cloud: Doubao ASR 2.0"; else -> model
    }

    private val simpToTradTransliterator by lazy { Transliterator.getInstance("Simplified-Traditional") }
    private val tradToSimpTransliterator by lazy { Transliterator.getInstance("Traditional-Simplified") }

    private fun convertChinese(text: String): String = when (zhMode) {
        "trad" -> runCatching { simpToTradTransliterator.transliterate(text) }.getOrElse { OpenCcLite.toTraditional(text) }
            .let { OpenCcLite.toTraditional(it) }
            .map { simpToTrad[it] ?: it }
            .joinToString("")
            .let { toCantoneseTraditional(it) }
        "simp" -> runCatching { tradToSimpTransliterator.transliterate(text) }.getOrElse { OpenCcLite.toSimplified(text) }
            .let { OpenCcLite.toSimplified(it) }
            .map { tradToSimp[it] ?: it }
            .joinToString("")
        else -> text
    }

    private fun toCantoneseTraditional(input: String): String {
        val phraseMap = linkedMapOf(
            "不是" to "唔係",
            "不是的" to "唔係嘅",
            "沒有" to "冇",
            "没" to "冇",
            "不要" to "唔好",
            "不用" to "唔使",
            "不能" to "唔可以",
            "不可以" to "唔可以",
            "不知道" to "唔知道",
            "為了" to "為咗",
            "已經" to "已經",
            "這個" to "呢個",
            "這些" to "呢啲",
            "這裏" to "呢度",
            "那個" to "嗰個",
            "那些" to "嗰啲",
            "那裏" to "嗰度",
            "什麼" to "咩",
            "甚麼" to "咩",
            "為什麼" to "點解",
            "為甚麼" to "點解",
            "怎麼" to "點樣",
            "怎樣" to "點樣",
            "現在" to "而家",
            "剛才" to "頭先",
            "一點" to "少少",
            "一下" to "一下",
            "可以" to "可以",
            "我們" to "我哋",
            "你們" to "你哋",
            "他們" to "佢哋",
            "她們" to "佢哋",
            "它們" to "佢哋"
        )
        var out = input
        for ((from, to) in phraseMap.entries.sortedByDescending { it.key.length }) out = out.replace(from, to)
        out = out.replace(Regex("(?<=[\\p{IsHan}])的(?=[\\p{IsHan}])"), "嘅")
        out = out.replace(Regex("(?<=[\\p{IsHan}])了(?=($|[，。？！、,.?! ]))"), "咗")
        out = out.replace(Regex("(?<=[\\p{IsHan}])在(?=[\\p{IsHan}])"), "喺")
        return out
    }

    private fun postProcessVoiceText(raw: String): String {
        var text = raw.trim()
        if (isAsrDebugText(text)) return ""
        if (text.isBlank() || !voiceCleanupEnabled) return maybeAutoPunctuate(text)
        text = text.replace(Regex("\\s+"), " ")
        text = text.replace(Regex("\\s+([,.?!:;，。？！：；])"), "\$1")
        text = text.replace(Regex("([,.?!:;，。？！：；])([^\\s,.?!:;，。？！：；])"), "\$1 \$2")
        text = removeRepeatedWords(text)
        text = removeRepeatedPhrases(text)
        text = normalizeAllCapsEnglish(text)
        text = fixSenseVoiceEnglishSpelling(text)
        text = preserveCommonVoiceTerms(text)
        return maybeAutoPunctuate(text.trim())
    }

    private fun fixSenseVoiceEnglishSpelling(input: String): String {
        val corrections = mapOf(
            "umodel" to "model", "u model" to "model", "umodels" to "models",
            "teboard" to "keyboard", "keybord" to "keyboard", "key board" to "keyboard",
            "soureces" to "sources", "soure" to "source", "sorce" to "source",
            "alin(e)?d" to "aligned", "alligned" to "aligned",
            "simplfy" to "simplify", "simpf(y|i)" to "simplify", "simplif(y|i)d" to "simplified",
            "traditiona" to "traditional", "tradional" to "traditional",
            "captalized" to "capitalized", "captilized" to "capitalized",
            "pelling" to "spelling", "avilable" to "available",
            "download(ed)?" to "download$1", "qwen" to "Qwen", "sense voice" to "SenseVoice"
        )
        var out = input
        for ((from, to) in corrections) out = out.replace(Regex("(?i)\\b$from\\b"), to)
        return out
    }

    private fun normalizeAllCapsEnglish(input: String): String {
        val keepUpper = setOf("AI", "ASR", "API", "APK", "CLP", "COC", "OK", "PC", "USB", "URL", "IME", "ONNX", "RTX", "GPU", "CPU")
        return input.replace(Regex("\\b[A-Z][A-Z']{1,}\\b")) { m ->
            val word = m.value
            if (word in keepUpper || word.length <= 1) word else word.lowercase()
        }
    }

    private fun isAsrDebugText(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("Generated token", ignoreCase = true) ||
            t.startsWith("Token", ignoreCase = true) ||
            t.matches(Regex("(?i).*(token ids?|generated ids?).*"))
    }

    private fun maybeAutoPunctuate(text: String): String {
        // Do not punctuate individual live chunks. A chunk is not a sentence.
        // Final punctuation is applied once after all queued chunks finish.
        return text
    }

    private fun applyPcPunctuationIfSelected(text: String, client: PcAsrClient?): String {
        if (voicePunctuationMode != "pc_ai" || text.isBlank()) return text
        if (client == null) return addRulePunctuationFallback(text)
        return try {
            client.fixText(text, model = "pc_qwen_text", language = if (zhMode == "orig") "auto" else zhMode).text.trim().ifBlank { text }
        } catch (t: Throwable) {
            Log.w("QwenKeyboard", "PC punctuation failed; using rule fallback", t)
            addRulePunctuationFallback(text)
        }
    }

    private fun addRulePunctuationFallback(text: String): String {
        val t = text.trim()
        if (t.isBlank() || t.last() in ".?!。？！") return t
        return if (zhMode == "simp" || zhMode == "trad" || t.any { it in '\u4e00'..'\u9fff' }) "$t。" else "$t."
    }

    private fun removeRepeatedWords(text: String): String {
        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 2) return text
        val out = mutableListOf<String>()
        for (part in parts) {
            if (out.lastOrNull()?.let { normalizedVoiceToken(it) == normalizedVoiceToken(part) } != true) out.add(part)
        }
        return out.joinToString(" ")
    }

    private fun removeRepeatedPhrases(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            for (n in 2..6) {
                var i = 0
                while (i + n * 2 <= words.size) {
                    val a = words.subList(i, i + n).map { normalizedVoiceToken(it) }
                    val b = words.subList(i + n, i + n * 2).map { normalizedVoiceToken(it) }
                    if (a == b && a.any { it.isNotBlank() }) {
                        repeat(n) { words.removeAt(i + n) }
                        changed = true
                    } else i++
                }
            }
        }
        return words.joinToString(" ")
    }

    private fun trimOverlappingChunkPrefix(existing: String, incoming: String): String {
        if (existing.isBlank() || incoming.isBlank()) return incoming
        val oldWords = existing.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val newWords = incoming.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (oldWords.isEmpty() || newWords.isEmpty()) return incoming
        val maxOverlap = minOf(10, oldWords.size, newWords.size)
        for (n in maxOverlap downTo 1) {
            val oldTail = oldWords.takeLast(n).map { normalizedVoiceToken(it) }
            val newHead = newWords.take(n).map { normalizedVoiceToken(it) }
            if (oldTail == newHead && oldTail.any { it.isNotBlank() }) {
                return newWords.drop(n).joinToString(" ")
            }
        }
        // Also catch a short repeated filler after punctuation, e.g. previous ends
        // "... it is." and next starts "It is. Let's..." or "But. But let's...".
        if (oldWords.lastOrNull()?.let { normalizedVoiceToken(it) == normalizedVoiceToken(newWords.first()) } == true) {
            return newWords.drop(1).joinToString(" ")
        }
        return incoming
    }

    private fun normalizedVoiceToken(token: String): String = token
        .lowercase()
        .trim { it.isWhitespace() || it in ".,?!:;，。？！：；\"'“”‘’()[]{}" }

    private val qwertyNeighbors = mapOf(
        'q' to "wa", 'w' to "qase", 'e' to "wsdr", 'r' to "edft", 't' to "rfgy", 'y' to "tghu", 'u' to "yhji", 'i' to "ujko", 'o' to "iklp", 'p' to "ol",
        'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv", 'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kopm",
        'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn", 'n' to "bhjm", 'm' to "njkl"
    )
    private val qwertySecondNeighbors = mapOf(
        'q' to "se", 'w' to "zdr", 'e' to "aqft", 'r' to "sgy", 't' to "dhu", 'y' to "fji", 'u' to "gko", 'i' to "hlp", 'o' to "jm", 'p' to "k",
        'a' to "edx", 's' to "rfc", 'd' to "tgzv", 'f' to "yhxb", 'g' to "ujcn", 'h' to "ikvm", 'j' to "olbn", 'k' to "pmh", 'l' to "ijmn",
        'z' to "wdc", 'x' to "aefv", 'c' to "srgb", 'v' to "dthn", 'b' to "fym", 'n' to "guikl", 'm' to "hjol"
    )
    private val numberKeyNeighbors = mapOf(
        '1' to "q", '2' to "qw", '3' to "we", '4' to "er", '5' to "rt", '6' to "ty", '7' to "yu", '8' to "ui", '9' to "io", '0' to "op"
    )
    private val numberKeySecondNeighbors = mapOf(
        '1' to "wa", '2' to "eas", '3' to "rsd", '4' to "tdf", '5' to "yfg", '6' to "ugh", '7' to "ihj", '8' to "ojk", '9' to "pkl", '0' to "l"
    )
    private val contractionSuggestions = mapOf(
        "cant" to listOf("can't"), "wont" to listOf("won't"), "dont" to listOf("don't"), "isnt" to listOf("isn't"), "arent" to listOf("aren't"),
        "wasnt" to listOf("wasn't"), "werent" to listOf("weren't"), "couldnt" to listOf("couldn't"), "shouldnt" to listOf("shouldn't"),
        "wouldnt" to listOf("wouldn't"), "doesnt" to listOf("doesn't"), "didnt" to listOf("didn't"), "havent" to listOf("haven't"),
        "hasnt" to listOf("hasn't"), "hadnt" to listOf("hadn't"), "im" to listOf("I'm"), "ive" to listOf("I've"), "ill" to listOf("I'll"),
        "youre" to listOf("you're"), "youve" to listOf("you've"), "youll" to listOf("you'll"), "theyre" to listOf("they're"),
        "weve" to listOf("we've"), "thats" to listOf("that's"), "theres" to listOf("there's"), "whats" to listOf("what's"),
        "lets" to listOf("let's"), "its" to listOf("it's", "its")
    )
    private val globalCorrectionSuggestions = mapOf(
        "i" to listOf("I"), "ti" to listOf("to", "it"), "ot" to listOf("to", "it"), "ut" to listOf("it"),
        "u" to listOf("I", "you"), "r" to listOf("are"), "ur" to listOf("your", "you're"), "teh" to listOf("the"),
        "qwen" to listOf("Qwen"), "openclaw" to listOf("OpenClaw"), "sensevoice" to listOf("SenseVoice")
    )
    private val specialSuggestions = mapOf("thus" to listOf("this"), "byywr" to listOf("buyer"), "kike" to listOf("like"), "hemko" to listOf("hello"), "beyyer" to listOf("better"), "8xr" to listOf("ice"), "veesn" to listOf("cream"))
    private val commonNextWords = mapOf(
        "how" to listOf("are", "to", "is"),
        "how are" to listOf("you"),
        "what" to listOf("is", "are", "do"),
        "what is" to listOf("the", "your", "this"),
        "where" to listOf("is", "are", "do"),
        "where is" to listOf("the", "it", "my"),
        "when" to listOf("is", "are", "do"),
        "can" to listOf("you", "i", "we"),
        "can you" to listOf("please", "help", "check"),
        "i" to listOf("am", "will", "want"),
        "i am" to listOf("going", "not", "sure"),
        "i will" to listOf("send", "check", "call"),
        "i want" to listOf("to", "this", "the"),
        "thank" to listOf("you"),
        "thank you" to listOf("so", "for", "very"),
        "see" to listOf("you"),
        "see you" to listOf("later", "soon", "tomorrow"),
        "please" to listOf("check", "send", "let"),
        "let" to listOf("me", "us"),
        "let me" to listOf("know", "check", "see"),
        "good" to listOf("morning", "night", "idea"),
        "no" to listOf("problem", "need", "worries"),
        "唔" to listOf("係", "使", "好"),
        "我" to listOf("哋", "想", "會"),
        "你" to listOf("哋", "可以", "覺得"),
        "點" to listOf("解", "樣"),
        "呢" to listOf("個", "啲", "度")
    )
    private val pinyinMap = linkedMapOf(
        "a" to listOf("啊", "阿", "呀"), "ai" to listOf("愛", "矮", "哎"), "an" to listOf("安", "按", "案"), "ang" to listOf("昂"),
        "ba" to listOf("把", "吧", "爸"), "bai" to listOf("百", "白", "擺"), "ban" to listOf("辦", "半", "班"), "bang" to listOf("幫", "棒"), "bao" to listOf("包", "報", "保"), "bei" to listOf("被", "北", "備"), "ben" to listOf("本", "笨"), "bi" to listOf("比", "必", "筆"), "bian" to listOf("邊", "變", "便"), "biao" to listOf("表", "標"), "bie" to listOf("別"), "bin" to listOf("賓"), "bing" to listOf("並", "病", "冰"), "bo" to listOf("波", "播"), "bu" to listOf("不", "部", "步"),
        "ca" to listOf("擦"), "cai" to listOf("才", "菜", "彩"), "can" to listOf("參", "餐"), "cang" to listOf("藏", "倉"), "cao" to listOf("草", "操"), "ce" to listOf("測", "冊"), "cen" to listOf("岑"), "ceng" to listOf("曾", "層"), "cha" to listOf("查", "茶", "差"), "chai" to listOf("拆"), "chan" to listOf("產", "單"), "chang" to listOf("長", "常", "場"), "chao" to listOf("超", "朝"), "che" to listOf("車"), "chen" to listOf("陳", "晨"), "cheng" to listOf("成", "程", "城"), "chi" to listOf("吃", "持", "遲"), "chong" to listOf("重", "衝"), "chu" to listOf("出", "處", "除"), "chuang" to listOf("窗", "創"), "chun" to listOf("春", "純"), "ci" to listOf("次", "此", "詞"), "cong" to listOf("從", "聰"), "cuo" to listOf("錯"),
        "da" to listOf("大", "打", "達"), "dai" to listOf("帶", "代", "待"), "dan" to listOf("但", "單", "蛋"), "dang" to listOf("當", "黨"), "dao" to listOf("到", "道", "倒"), "de" to listOf("的", "得", "地"), "dei" to listOf("哋", "得", "第"), "deng" to listOf("等", "燈"), "di" to listOf("啲", "的", "地"), "dian" to listOf("點", "電", "店"), "diao" to listOf("調", "掉"), "die" to listOf("跌", "疊"), "ding" to listOf("定", "頂"), "dong" to listOf("動", "東", "懂"), "dou" to listOf("都", "到", "豆"), "du" to listOf("讀", "度", "都"), "dui" to listOf("對", "隊"), "duo" to listOf("多", "朵"),
        "e" to listOf("額", "鵝", "俄"), "en" to listOf("嗯", "恩"), "er" to listOf("而", "二", "兒"),
        "fa" to listOf("發", "法"), "fan" to listOf("返", "反", "飯"), "fang" to listOf("方", "放", "房"), "fei" to listOf("非", "飛", "費"), "fen" to listOf("分", "份", "粉"), "feng" to listOf("風", "封"), "fo" to listOf("佛"), "fou" to listOf("否"), "fu" to listOf("服", "副", "付"),
        "ga" to listOf("咖", "加", "家"), "gai" to listOf("該", "改"), "gan" to listOf("感", "乾", "敢"), "gang" to listOf("港", "剛"), "gao" to listOf("高", "搞", "告"), "ge" to listOf("個", "嘅", "各"), "gei" to listOf("給"), "gen" to listOf("跟", "根"), "geng" to listOf("更"), "gong" to listOf("工", "公", "共"), "gou" to listOf("夠", "狗"), "gu" to listOf("古", "故"), "gua" to listOf("掛"), "guan" to listOf("關", "管"), "guang" to listOf("光", "廣"), "gui" to listOf("貴", "歸"), "guo" to listOf("過", "國", "果"),
        "ha" to listOf("哈", "下"), "hai" to listOf("係", "喺", "還"), "han" to listOf("看", "漢", "含"), "hang" to listOf("行", "航"), "hao" to listOf("好", "號", "豪"), "he" to listOf("和", "何", "喝"), "hei" to listOf("黑"), "hen" to listOf("很", "恨"), "heng" to listOf("恆"), "hong" to listOf("香", "紅", "康"), "hou" to listOf("後", "候"), "hu" to listOf("胡", "護", "互"), "hua" to listOf("話", "花", "華"), "huan" to listOf("換", "還", "歡"), "huang" to listOf("黃"), "hui" to listOf("會", "回", "灰"), "hun" to listOf("混"), "huo" to listOf("或", "火", "活"),
        "ji" to listOf("機", "幾", "記"), "jia" to listOf("家", "加", "假"), "jian" to listOf("見", "件", "簡"), "jiang" to listOf("講", "將", "江"), "jiao" to listOf("教", "叫", "交"), "jie" to listOf("解", "接", "街"), "jin" to listOf("今", "近", "進"), "jing" to listOf("經", "京", "竟"), "jiu" to listOf("就", "九", "久"), "ju" to listOf("句", "局", "舉"), "juan" to listOf("卷"), "jue" to listOf("覺", "決"), "jun" to listOf("君", "軍"),
        "ka" to listOf("卡"), "kai" to listOf("開", "楷"), "kan" to listOf("看", "刊"), "kang" to listOf("康", "抗"), "kao" to listOf("靠", "考"), "ke" to listOf("可", "課", "客"), "ken" to listOf("肯"), "kong" to listOf("港", "空", "控"), "kou" to listOf("口", "扣"), "ku" to listOf("苦", "庫"), "kuai" to listOf("快", "塊"), "kuan" to listOf("款"), "kui" to listOf("虧"),
        "la" to listOf("啦", "喇", "拉"), "lai" to listOf("來", "賴"), "lan" to listOf("懶", "藍"), "lang" to listOf("浪", "郎"), "lao" to listOf("老"), "le" to listOf("了", "咗", "樂"), "lei" to listOf("你", "類", "累"), "li" to listOf("理", "裡", "離"), "lian" to listOf("連", "練"), "liang" to listOf("兩", "量"), "liao" to listOf("了", "料"), "lie" to listOf("列"), "lin" to listOf("林"), "ling" to listOf("令", "零"), "liu" to listOf("流", "留", "六"), "lo" to listOf("囉", "咯"), "long" to listOf("龍"), "lou" to listOf("樓"), "lu" to listOf("路", "錄"), "lun" to listOf("論"), "luo" to listOf("落", "羅"),
        "ma" to listOf("嗎", "嘛", "馬"), "mai" to listOf("買", "賣"), "man" to listOf("慢", "滿"), "mang" to listOf("忙"), "mao" to listOf("貓", "毛"), "mei" to listOf("冇", "沒", "美"), "men" to listOf("們", "門", "問"), "mi" to listOf("米", "密"), "mian" to listOf("面", "免"), "ming" to listOf("明", "名"), "mo" to listOf("麼", "模", "末"), "mou" to listOf("某"), "mu" to listOf("目", "母"),
        "na" to listOf("那", "哪", "拿"), "nai" to listOf("乃", "奶"), "nan" to listOf("難", "男"), "nang" to listOf("囊"), "nao" to listOf("腦", "鬧"), "ne" to listOf("呢", "哪", "內"), "nei" to listOf("內", "餒"), "nen" to listOf("嫩"), "neng" to listOf("能"), "ni" to listOf("你", "呢", "尼"), "nian" to listOf("年", "念"), "niang" to listOf("娘"), "niao" to listOf("鳥"), "nin" to listOf("您"), "ning" to listOf("寧"), "niu" to listOf("牛"), "nong" to listOf("農"), "nu" to listOf("怒"), "nv" to listOf("女"), "nuo" to listOf("諾"),
        "o" to listOf("哦", "喔"), "ou" to listOf("歐", "偶"),
        "pa" to listOf("怕", "爬"), "pai" to listOf("排", "派"), "pan" to listOf("盤", "判"), "pang" to listOf("旁"), "pao" to listOf("跑", "泡"), "pei" to listOf("配", "陪"), "pen" to listOf("噴"), "peng" to listOf("朋", "碰"), "pi" to listOf("皮", "批"), "pian" to listOf("片", "篇"), "piao" to listOf("票"), "pin" to listOf("拼", "品"), "ping" to listOf("平", "評"), "po" to listOf("破"), "pu" to listOf("普"),
        "qi" to listOf("起", "其", "期"), "qia" to listOf("卡"), "qian" to listOf("前", "錢", "千"), "qiang" to listOf("強"), "qiao" to listOf("橋", "巧"), "qie" to listOf("且"), "qin" to listOf("親"), "qing" to listOf("請", "清", "情"), "qiu" to listOf("求", "球"), "qu" to listOf("去", "區"), "quan" to listOf("全", "權"), "que" to listOf("卻", "確"), "qun" to listOf("群"),
        "ran" to listOf("然"), "rang" to listOf("讓"), "rao" to listOf("繞"), "re" to listOf("熱"), "ren" to listOf("人", "認"), "reng" to listOf("仍"), "ri" to listOf("日"), "rong" to listOf("容"), "rou" to listOf("肉"), "ru" to listOf("如", "入"), "ruan" to listOf("軟"), "rui" to listOf("瑞"), "run" to listOf("潤"), "ruo" to listOf("若"),
        "sa" to listOf("撒"), "sai" to listOf("賽"), "san" to listOf("三", "散"), "sang" to listOf("桑"), "se" to listOf("色"), "sen" to listOf("森"), "sha" to listOf("傻", "沙"), "shai" to listOf("曬"), "shan" to listOf("山", "刪"), "shang" to listOf("上", "商"), "shao" to listOf("少", "燒"), "she" to listOf("設", "社"), "shen" to listOf("神", "身", "深"), "sheng" to listOf("聲", "生", "勝"), "shi" to listOf("是", "時", "事"), "shou" to listOf("手", "收"), "shu" to listOf("書", "輸", "數"), "shui" to listOf("水", "誰"), "shuo" to listOf("說"), "si" to listOf("四", "思"), "song" to listOf("送"), "su" to listOf("速", "素"), "suan" to listOf("算"), "sui" to listOf("歲", "隨"), "suo" to listOf("所"),
        "ta" to listOf("他", "她", "它"), "tai" to listOf("太", "台"), "tan" to listOf("談", "彈"), "tang" to listOf("堂", "糖"), "tao" to listOf("套", "討"), "te" to listOf("特"), "teng" to listOf("疼"), "ti" to listOf("題", "提", "體"), "tian" to listOf("天", "填"), "tiao" to listOf("條", "跳"), "tie" to listOf("貼"), "ting" to listOf("聽", "停"), "tong" to listOf("同", "通"), "tou" to listOf("頭"), "tu" to listOf("圖", "土"), "tuan" to listOf("團"), "tui" to listOf("推", "退"),
        "wa" to listOf("哇", "挖"), "wai" to listOf("外"), "wan" to listOf("完", "晚", "玩"), "wang" to listOf("網", "往", "王"), "wei" to listOf("為", "位", "喂"), "wen" to listOf("問", "文", "聞"), "wo" to listOf("我", "喎", "和"), "wu" to listOf("唔", "無", "五"),
        "xi" to listOf("西", "係", "習"), "xia" to listOf("下", "夏"), "xian" to listOf("先", "現", "線"), "xiang" to listOf("想", "香", "向"), "xiao" to listOf("小", "笑"), "xie" to listOf("些", "謝", "寫"), "xin" to listOf("新", "心", "信"), "xing" to listOf("行", "型", "星"), "xiu" to listOf("修"), "xu" to listOf("需", "許"), "xuan" to listOf("選"), "xue" to listOf("學"),
        "ya" to listOf("呀", "也"), "yan" to listOf("言", "眼"), "yang" to listOf("樣", "陽"), "yao" to listOf("要", "咬", "腰"), "ye" to listOf("也", "夜"), "yi" to listOf("以", "一", "已"), "yin" to listOf("音", "因"), "ying" to listOf("應", "英"), "yo" to listOf("喲"), "yong" to listOf("用", "永"), "you" to listOf("有", "又", "由"), "yu" to listOf("語", "與", "於"), "yuan" to listOf("原", "遠", "員"), "yue" to listOf("粵", "月", "約"), "yun" to listOf("雲", "運"),
        "za" to listOf("咋"), "zai" to listOf("在", "再", "載"), "zan" to listOf("讚"), "zang" to listOf("髒"), "zao" to listOf("早", "造"), "ze" to listOf("則"), "zen" to listOf("怎"), "zeng" to listOf("曾", "增"), "zha" to listOf("查", "炸"), "zhai" to listOf("宅"), "zhan" to listOf("站", "展"), "zhang" to listOf("張", "長"), "zhao" to listOf("找", "照"), "zhe" to listOf("這", "者"), "zhen" to listOf("真", "陣"), "zheng" to listOf("正", "整", "證"), "zhi" to listOf("只", "知", "指"), "zhong" to listOf("中", "種", "鐘"), "zhou" to listOf("週", "州"), "zhu" to listOf("住", "主", "注"), "zhuan" to listOf("轉"), "zhuang" to listOf("裝"), "zhui" to listOf("追"), "zi" to listOf("字", "自", "子"), "zong" to listOf("總"), "zou" to listOf("走"), "zu" to listOf("組", "足"), "zui" to listOf("最"), "zuo" to listOf("做", "左", "作"),
        "nihao" to listOf("你好"), "xiexie" to listOf("謝謝"), "qingwen" to listOf("請問"), "meiyou" to listOf("冇", "沒有"), "bushi" to listOf("不是"), "keyi" to listOf("可以"), "zhongwen" to listOf("中文"), "jianti" to listOf("簡體"), "fanti" to listOf("繁體"), "xiangyao" to listOf("想要"), "haiyao" to listOf("還要"), "xianggang" to listOf("香港"), "dianjie" to listOf("點解"), "mhai" to listOf("唔係"), "ng" to listOf("唔", "五", "吾"), "go" to listOf("個", "哥", "過"), "gam" to listOf("咁", "今", "感")
    )

    // Practical 九方-style stroke dictionary. Official 九方 uses five basic strokes plus auxiliary
    // components and normally needs only 1-3 codes; public docs confirm 口 can be taken directly
    // on the 6 key. Until we have a licensed full table, this keeps the mode usable with stroke aliases.
    // 1/h=橫, 2/s=豎, 3/p=撇, 4/n=點/捺, 5/z=折, 6/k/o=口.
    private val jiufangAlias = mapOf(
        '1' to 'h', '2' to 's', '3' to 'p', '4' to 'n', '5' to 'z', '6' to 'k',
        'h' to 'h', 's' to 's', 'p' to 'p', 'n' to 'n', 'z' to 'z', 'k' to 'k', 'o' to 'k'
    )
    private val jiufangMap = linkedMapOf(
        "h" to listOf("一", "二", "三"), "s" to listOf("丨", "中", "山"), "p" to listOf("人", "入", "八"),
        "n" to listOf("丶", "心", "火"), "z" to listOf("乙", "了", "也"), "k" to listOf("口", "回", "品"),
        "hh" to listOf("二", "三", "王"), "hs" to listOf("十", "土", "王"), "hp" to listOf("木", "本", "未"),
        "hn" to listOf("不", "下", "平"), "hz" to listOf("七", "牙", "切"), "hk" to listOf("可", "哥", "歌"),
        "sh" to listOf("日", "田", "目"), "ss" to listOf("川", "山", "出"), "sp" to listOf("小", "少", "尖"),
        "sn" to listOf("水", "求", "冰"), "sz" to listOf("凹", "凸", "鼎"), "sk" to listOf("問", "間", "門"),
        "ph" to listOf("千", "牛", "生"), "ps" to listOf("你", "他", "佢"), "pp" to listOf("竹", "笑", "答"),
        "pn" to listOf("人", "大", "太"), "pz" to listOf("九", "凡", "風"), "pk" to listOf("向", "白", "自"),
        "nh" to listOf("文", "方", "言"), "ns" to listOf("主", "立", "童"), "np" to listOf("必", "心", "火"),
        "nn" to listOf("六", "炎", "米"), "nz" to listOf("之", "這", "進"), "nk" to listOf("高", "亮", "京"),
        "zh" to listOf("女", "好", "她"), "zs" to listOf("書", "畫", "建"), "zp" to listOf("刀", "力", "乃"),
        "zn" to listOf("又", "叉", "皮"), "zz" to listOf("乙", "弓", "已"), "zk" to listOf("司", "局", "君"),
        "kh" to listOf("因", "國", "園"), "ks" to listOf("叫", "中", "串"), "kp" to listOf("只", "兄", "員"),
        "kn" to listOf("呃", "唔", "咁"), "kz" to listOf("另", "呢", "吧"), "kk" to listOf("品", "器", "嘻"),
        "hsp" to listOf("我", "手", "打"), "hsn" to listOf("求", "球", "救"), "hpn" to listOf("想", "相", "樣"),
        "hzh" to listOf("好", "媽", "姐"), "hks" to listOf("事", "更", "吏"), "shn" to listOf("是", "時", "題"),
        "szh" to listOf("個", "國", "因"), "skh" to listOf("問", "間", "聞"), "pnh" to listOf("今", "令", "會"),
        "pns" to listOf("介", "企", "命"), "psh" to listOf("作", "住", "信"), "pkh" to listOf("自", "息", "鼻"),
        "nhs" to listOf("請", "說", "語"), "nkh" to listOf("高", "亮", "就"), "zsh" to listOf("書", "畫", "建"),
        "zpn" to listOf("發", "登", "癹"), "kpn" to listOf("只", "員", "買"), "kkh" to listOf("嘅", "器", "單")
    )

    private val flowWordMap = mapOf(
        "the" to listOf("the"), "and" to listOf("and"), "you" to listOf("you"), "your" to listOf("your"), "that" to listOf("that"),
        "this" to listOf("this"), "there" to listOf("there"), "their" to listOf("their"), "they" to listOf("they"), "then" to listOf("then"),
        "hello" to listOf("hello"), "thanks" to listOf("thanks"), "thank" to listOf("thank"), "please" to listOf("please"),
        "because" to listOf("because"), "about" to listOf("about"), "before" to listOf("before"), "after" to listOf("after"),
        "qwen" to listOf("Qwen"), "keyboard" to listOf("keyboard"), "model" to listOf("model"), "server" to listOf("server"),
        "local" to listOf("local"), "phone" to listOf("phone"), "voice" to listOf("voice"), "message" to listOf("message"),
        "today" to listOf("today"), "tomorrow" to listOf("tomorrow"), "tonight" to listOf("tonight"), "morning" to listOf("morning"),
        "meeting" to listOf("meeting"), "project" to listOf("project"), "version" to listOf("version"), "settings" to listOf("settings"),
        "finish" to listOf("finish"), "continue" to listOf("continue"), "improve" to listOf("improve"), "install" to listOf("install"),
        "testing" to listOf("testing"), "working" to listOf("working"), "problem" to listOf("problem"), "possible" to listOf("possible"),
        "dog" to listOf("dog"), "cat" to listOf("cat"), "can" to listOf("can"), "day" to listOf("day"), "done" to listOf("done"),
        "good" to listOf("good"), "great" to listOf("great"), "go" to listOf("go"), "going" to listOf("going"), "home" to listOf("home"),
        "have" to listOf("have"), "how" to listOf("how"), "now" to listOf("now"), "not" to listOf("not"), "need" to listOf("need"),
        "want" to listOf("want"), "what" to listOf("what"), "when" to listOf("when"), "where" to listOf("where"), "why" to listOf("why"),
        "yes" to listOf("yes"), "no" to listOf("no"), "ok" to listOf("ok"), "okay" to listOf("okay"), "soccer" to listOf("soccer"),
        "derrick" to listOf("Derrick"), "anthony" to listOf("Anthony"), "dee" to listOf("Dee"), "mary" to listOf("Mary"),
        "jane" to listOf("Jane"), "legend" to listOf("Legend"), "share" to listOf("share"), "funny" to listOf("funny"),
        "group" to listOf("group"), "signal" to listOf("Signal"), "whatsapp" to listOf("WhatsApp")
    )

    private data class FlowRank(val candidate: String, val score: Int, val coverage: Float, val orderedCoverage: Float)

    private fun bestFlowGuess(signature: String): String? {
        val ranked = flowRanks(signature, 5, forSuggestions = false)
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        val word = compactFlowToken(best.candidate.lowercase())
        val isStrong = best.coverage >= 0.82f && best.orderedCoverage >= 0.68f && best.score <= maxOf(9, word.length + 2)
        val hasMargin = second == null || second.score - best.score >= 3
        return if (isStrong && hasMargin) best.candidate else null
    }

    private fun flowGuesses(signature: String, limit: Int = 3): List<String> = flowRanks(signature, limit, forSuggestions = true).map { it.candidate }

    private fun flowRanks(signature: String, limit: Int = 3, forSuggestions: Boolean = false): List<FlowRank> {
        val sig = compactFlowToken(signature)
        if (sig.length < 2) return emptyList()
        val first = sig.firstOrNull() ?: return emptyList()
        val minCandidateLen = when {
            sig.length >= 9 -> 5
            sig.length >= 6 -> 4
            else -> 2
        }
        return (wordFreq.keys + learnedFreq.keys + flowWordMap.values.flatten())
            .asSequence()
            .map { it.trim() }
            .filter { it.length in 2..18 && it.firstOrNull()?.lowercaseChar() == first }
            .distinctBy { it.lowercase() }
            .mapNotNull { candidate ->
                val word = compactFlowToken(candidate.lowercase())
                if (word.isBlank() || word.length < minCandidateLen) null else {
                    val coverage = flowCoverage(sig, word)
                    val orderedCoverage = flowOrderedCoverage(sig, word)
                    val score = flowScore(sig, word)
                    val allowed = if (forSuggestions) {
                        coverage >= 0.30f && orderedCoverage >= 0.25f && score <= maxOf(34, word.length + 22)
                    } else {
                        coverage >= 0.55f && orderedCoverage >= 0.45f && score <= maxOf(16, word.length + 9)
                    }
                    if (allowed) FlowRank(candidate, score - word.length.coerceAtMost(8) / 2, coverage, orderedCoverage) else null
                }
            }
            .filterNot { forgottenWords.contains(normalizeLearnedWord(it.candidate)) }
            .sortedWith(compareBy<FlowRank> { it.score }.thenByDescending { it.coverage }.thenByDescending { it.orderedCoverage }.thenByDescending { it.candidate.length })
            .take(limit)
            .toList()
    }

    private fun compactFlowToken(value: String): String = value.lowercase().replace(Regex("[^a-z]"), "").replace(Regex("(.)\\1+"), "$1")

    private fun flowCoverage(signature: String, word: String): Float {
        if (word.isBlank()) return 0f
        var covered = 0
        for (ch in word.toSet()) if (signature.contains(ch)) covered++
        return covered.toFloat() / word.toSet().size.toFloat()
    }

    private fun flowOrderedCoverage(signature: String, word: String): Float {
        if (word.isBlank()) return 0f
        var i = 0
        var matched = 0
        for (ch in word) {
            val found = signature.indexOf(ch, i)
            if (found >= 0) {
                matched++
                i = found + 1
            } else if (signature.contains(ch)) {
                matched++ // Present but out of order because the finger looped/overshot.
            }
        }
        return matched.toFloat() / word.length.toFloat()
    }

    private fun flowScore(signature: String, word: String): Int {
        val sig = compactFlowToken(signature)
        val target = compactFlowToken(word)
        if (sig.isBlank() || target.isBlank()) return 999
        var i = 0
        var misses = 0
        var jumps = 0
        var matched = 0
        for (ch in target) {
            val found = sig.indexOf(ch, i)
            if (found >= 0) {
                matched++
                jumps += (found - i).coerceAtLeast(0)
                i = found + 1
            } else if (sig.contains(ch)) {
                misses += 2 // User looped/overshot; letter exists but order was messy.
            } else {
                misses += 5
            }
        }
        val extra = kotlin.math.max(0, sig.length - target.length)
        val prefixPenalty = if (sig.firstOrNull() == target.firstOrNull()) 0 else 6
        val suffixPenalty = if (sig.lastOrNull() == target.lastOrNull()) 0 else if (sig.contains(target.last())) 2 else 6
        val coveragePenalty = ((target.length - matched).coerceAtLeast(0)) * 3
        return misses + jumps / 3 + extra / 3 + prefixPenalty + suffixPenalty + coveragePenalty
    }

    private val suchengMap = linkedMapOf(
        "a" to listOf("日", "曰", "昌"), "b" to listOf("月", "朋", "服"), "c" to listOf("金", "針", "銀"),
        "d" to listOf("木", "林", "樣"), "e" to listOf("水", "河", "海"), "f" to listOf("火", "炎", "燈"),
        "g" to listOf("土", "地", "場"), "h" to listOf("竹", "笑", "等"), "i" to listOf("戈", "我", "成"),
        "j" to listOf("十", "古", "真"), "k" to listOf("大", "太", "因"), "l" to listOf("中", "串", "書"),
        "m" to listOf("一", "二", "不"), "n" to listOf("弓", "了", "子"), "o" to listOf("人", "你", "他"),
        "p" to listOf("心", "想", "必"), "q" to listOf("手", "打", "把"), "r" to listOf("口", "呢", "唔"),
        "s" to listOf("尸", "局", "尾"), "t" to listOf("廿", "英", "草"), "u" to listOf("山", "出", "屈"),
        "v" to listOf("女", "好", "媽"), "w" to listOf("田", "國", "個"), "x" to listOf("難", "又", "叉"),
        "y" to listOf("卜", "這", "上"),
        "ar" to listOf("問", "間", "聞"), "am" to listOf("是", "題", "量"), "bo" to listOf("臉", "服", "勝"),
        "dm" to listOf("本", "查", "相"), "ed" to listOf("梁", "染", "渠"), "fd" to listOf("燈", "煉", "榮"),
        "gr" to listOf("喜", "嘉", "臺"), "ha" to listOf("香", "舊", "算"), "ir" to listOf("咸", "台", "啟"),
        "jr" to listOf("古", "客", "容"), "kl" to listOf("布", "右", "存"), "ll" to listOf("串", "順", "川"),
        "mr" to listOf("可", "哥", "石"), "nd" to listOf("子", "李", "孫"), "oi" to listOf("令", "今", "念"),
        "pr" to listOf("句", "名", "包"), "qr" to listOf("扣", "捉", "搵"), "rr" to listOf("品", "器", "單"),
        "sv" to listOf("展", "屬", "屆"), "tw" to listOf("苗", "萬", "黃"), "ub" to listOf("崩", "崗", "峭"),
        "vr" to listOf("如", "始", "姐"), "wm" to listOf("國", "里", "黑"), "yr" to listOf("占", "高", "這")
    )

    private val autoCorrect = mapOf("teh" to "the", "adn" to "and", "hte" to "the", "dont" to "don't", "cant" to "can't", "wont" to "won't", "im" to "I'm", "ive" to "I've", "ill" to "I'll", "youre" to "you're", "thats" to "that's", "recieve" to "receive", "seperate" to "separate", "definately" to "definitely", "becuase" to "because")
    private val simpToTrad = mapOf(
        '国' to '國','语' to '語','说' to '說','话' to '話','这' to '這','个' to '個','们' to '們','会' to '會','来' to '來','对' to '對','时' to '時','间' to '間','后' to '後','发' to '發','现' to '現','实' to '實','验' to '驗','测' to '測','试' to '試','录' to '錄','转' to '轉','简' to '簡','体' to '體','处' to '處','输' to '輸','键' to '鍵','盘' to '盤','开' to '開','关' to '關','长' to '長','边' to '邊','过' to '過','还' to '還','为' to '為','学' to '學','习' to '習','应' to '應','设' to '設','计' to '計','项' to '項','问' to '問','题' to '題','数' to '數','据' to '據','库' to '庫','网' to '網','络' to '絡','云' to '雲','节' to '節','点' to '點','线' to '線','电' to '電','脑' to '腦','机' to '機','声' to '聲','识' to '識','别' to '別','结' to '結','质' to '質','错' to '錯','误' to '誤','删' to '刪','单' to '單','词' to '詞','选' to '選','择' to '擇','页' to '頁','车' to '車','马' to '馬','门' to '門','见' to '見','让' to '讓','给' to '給','买' to '買','卖' to '賣','听' to '聽','无' to '無','与' to '與','亲' to '親','爱' to '愛','东' to '東','风' to '風','区' to '區','导' to '導','报' to '報','请' to '請','认' to '認','证' to '證','权' to '權','显' to '顯','刚' to '剛','从' to '從','产' to '產','业' to '業','务' to '務','动' to '動','层' to '層','楼' to '樓','户' to '戶','备' to '備','议' to '議','义' to '義','达' to '達','进' to '進','运' to '運','远' to '遠','迟' to '遲','读' to '讀','写' to '寫','组' to '組','织' to '織','当' to '當','块' to '塊','张' to '張','种' to '種','总' to '總','钟' to '鐘','带' to '帶','连' to '連','续' to '續','复' to '複','制' to '製','广' to '廣','厂' to '廠','历' to '歷'
    )
    private val tradToSimp = simpToTrad.entries.associate { (k, v) -> v to k }

    private fun postUi(block: () -> Unit) { mainHandler.post(block) }

    override fun onFinishInput() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        flushPendingLearningSaves()
        if (running.get()) stopDictation()
        super.onFinishInput()
    }

    override fun onDestroy() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        flushPendingLearningSaves()
        if (running.get()) stopDictation()
        super.onDestroy()
    }

    private fun updateRootPadding() {
        if (!::inputRoot.isInitialized) return
        val bottom = when {
            settingsMode -> menuBottomInsetPx()
            handwritingMode -> handwritingBottomInsetPx()
            voiceMode || previewModeEnabled -> voiceBottomInsetPx()
            else -> keyboardBottomInsetPx()
        }
        inputRoot.setPadding(dp(6), dp(6), dp(6), bottom)
    }

    private fun topButtonHeightPx(): Int = dp(if (isLandscapeLayout()) 32 else 44)

    private fun topButtonSizePx(): Int {
        if (isLandscapeLayout()) return dp(32)
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val contentDp = (wDp - 12f).coerceAtLeast(300f) // root has 6dp left/right padding
        val gapDp = 1f
        val buttonCount = 7f
        val oldButtonDp = if (wDp < 370f) 38f else 40f
        val oldButtonsTotal = oldButtonDp * buttonCount + gapDp * (buttonCount - 1f)
        val oldStatusDp = (contentDp - oldButtonsTotal).coerceAtLeast(72f)
        val targetStatusDp = oldStatusDp * 0.8f
        val newButtonDp = ((contentDp - targetStatusDp - gapDp * (buttonCount - 1f)) / buttonCount).coerceIn(oldButtonDp, 48f)
        return dp(newButtonDp.toInt())
    }

    private fun topButtonGapPx(): Int = dp(1)

    private fun settingsPanelHeightPx(): Int {
        if (!displayAwareMode) return (resources.displayMetrics.heightPixels * 0.38f).toInt().coerceIn(dp(250), dp(360))
        val h = resources.displayMetrics.heightPixels
        return (h * 0.40f).toInt().coerceIn(dp(240), dp(390))
    }

    private fun clipboardScrollHeightPx(): Int {
        if (!displayAwareMode) return dp(260)
        val h = resources.displayMetrics.heightPixels
        return (h * 0.24f).toInt().coerceIn(dp(170), dp(250))
    }

    private fun safeNavInsetPx(extraDp: Int = 12): Int {
        val navId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val nav = if (navId > 0) resources.getDimensionPixelSize(navId) else dp(28)
        return nav + dp(extraDp)
    }

    private fun menuBottomInsetPx(): Int {
        if (!displayAwareMode) return keyboardBottomInsetPx()
        return maxOf(safeNavInsetPx(12), dp(displayBottomBufferDp.coerceIn(0, 96)))
    }

    private fun voicePanelHeightPx(): Int {
        val fraction = when {
            previewModeEnabled && displayAwareMode -> 0.42f
            previewModeEnabled -> 0.40f
            displayAwareMode -> 0.26f
            else -> 0.24f
        }
        val max = when {
            previewModeEnabled && displayAwareMode -> dp(430)
            previewModeEnabled -> dp(390)
            displayAwareMode -> dp(275)
            else -> dp(245)
        }
        val min = when {
            previewModeEnabled -> dp(300)
            displayAwareMode -> dp(195)
            else -> dp(205)
        }
        return (resources.displayMetrics.heightPixels * fraction).toInt().coerceIn(min, max)
    }

    private fun handwritingCandidateHeightPx(): Int {
        if (!displayAwareMode) return dp(54)
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        return dp(if (hDp < 760f) 46 else 50)
    }

    private fun handwritingPadHeightPx(): Int {
        if (!displayAwareMode) return dp(176)
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        return dp(if (hDp < 760f) 146 else 164)
    }

    private fun handwritingControlHeightPx(): Int {
        if (!displayAwareMode) return dp(42)
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        return dp(if (hDp < 760f) 36 else 40)
    }

    private fun keyboardCanvasHeightPx(): Int {
        val heightMultiplier = when (keyboardSizeMode) { "compact" -> 0.74f; "tall" -> 1.26f; else -> 1.0f }
        val base = ((if (previewModeEnabled) dp(216) else dp(322)) * heightMultiplier).toInt()
        if (!displayAwareMode) return base
        if (isLandscapeLayout() && !previewModeEnabled) {
            val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
            val targetFactor = when (keyboardSizeMode) { "compact" -> 0.28f; "tall" -> 0.40f; else -> 0.34f }
            val target = (hDp * targetFactor).toInt()
            val min = when (keyboardSizeMode) { "compact" -> dp(106); "tall" -> dp(150); else -> dp(128) }
            val max = when (keyboardSizeMode) { "compact" -> dp(132); "tall" -> dp(184); else -> dp(150) }
            return dp(target).coerceIn(min, max)
        }
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val scale = (hDp / 820f).coerceIn(0.84f, 1.08f)
        val min = if (previewModeEnabled) {
            when (keyboardSizeMode) { "compact" -> dp(156); "tall" -> dp(224); else -> dp(190) }
        } else {
            when (keyboardSizeMode) { "compact" -> dp(224); "tall" -> dp(330); else -> dp(270) }
        }
        val max = if (previewModeEnabled) {
            when (keyboardSizeMode) { "compact" -> dp(206); "tall" -> dp(292); else -> dp(250) }
        } else {
            when (keyboardSizeMode) { "compact" -> dp(286); "tall" -> dp(418); else -> dp(348) }
        }
        return (base * scale).toInt().coerceIn(min, max)
    }

    private fun suggestionRowHeightPx(): Int {
        val base = if (isLandscapeLayout()) 22 else 42
        val adjusted = when (suggestionSizeMode) { "small" -> base - 6; "large" -> base + 8; else -> base }
        return dp(adjusted.coerceAtLeast(18))
    }

    private fun keyGapPx(): Int = dp(when (keySpacingMode) { "tight" -> 0; "comfortable" -> 2; else -> 1 })

    private fun useLandscapeSplitCenterControls(): Boolean = isLandscapeLayout() && splitKeyboardMode != "off"

    private fun isLandscapeLayout(): Boolean = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

    private fun keyboardBottomInsetPx(): Int = if (displayAwareMode) dp(displayBottomBufferDp.coerceIn(0, 96)) else dp(2)

    private fun handwritingBottomInsetPx(): Int {
        if (!displayAwareMode) return keyboardBottomInsetPx()
        // The handwriting panel has a bottom control row; keep it above gesture/3-button nav bars.
        return maxOf(safeNavInsetPx(12), dp(displayBottomBufferDp.coerceIn(0, 96)))
    }

    private fun voiceBottomInsetPx(): Int {
        // Some apps/ROMs draw IME content underneath the 3-button navigation bar.
        // Preview mode has a bottom keyboard row, so reserve the full nav height plus breathing room.
        val defaultInset = maxOf(dp(42), safeNavInsetPx(14))
        return if (displayAwareMode) maxOf(defaultInset, dp(displayBottomBufferDp.coerceIn(0, 96))) else defaultInset
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
