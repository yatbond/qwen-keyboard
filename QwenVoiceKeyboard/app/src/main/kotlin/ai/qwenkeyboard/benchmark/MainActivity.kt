package ai.qwenkeyboard.benchmark

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var chunkSpinner: Spinner
    private val chunkOptions = listOf("2s", "3s", "5s", "7s", "10s", "12s", "15s")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(makeUi())
    }

    private fun makeUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(18)
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "Qwen Voice Keyboard\nv1.2.2 separate app"
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Separate keyboard app. Enable ‘Qwen Voice Keyboard’ in Android keyboard settings, then switch to it inside Telegram."
            textSize = 15f
            setPadding(0, dp(16), 0, dp(12))
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(label("PC ASR URL"))
        urlInput = EditText(this).apply {
            setText(prefs().getString("url", "https://voice.dee-photography.com") ?: "https://voice.dee-photography.com")
            setSingleLine(true)
        }
        root.addView(urlInput, LinearLayout.LayoutParams(-1, -2))

        root.addView(label("Access token"))
        tokenInput = EditText(this).apply {
            setText(prefs().getString("token", "") ?: "")
            hint = "Paste PC ASR token here"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(-1, -2))

        root.addView(label("Keyboard chunk size"))
        chunkSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, chunkOptions)
            val saved = prefs().getString("chunk_sec", "5") ?: "5"
            setSelection(chunkOptions.indexOf("${saved}s").takeIf { it >= 0 } ?: chunkOptions.indexOf("5s"))
        }
        root.addView(chunkSpinner, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "Save Keyboard Settings"
            setOnClickListener { saveSettings() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        root.addView(Button(this).apply {
            text = "Open Keyboard Settings"
            setOnClickListener { startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        return root
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(10), 0, 0)
    }

    private fun saveSettings() {
        val chunkSec = (chunkSpinner.selectedItem?.toString() ?: "5s").removeSuffix("s")
        prefs().edit()
            .putString("url", urlInput.text.toString().trim().ifBlank { "https://voice.dee-photography.com" })
            .putString("token", tokenInput.text.toString())
            .putString("engine", "qwen_1_7b")
            .putString("chunk_sec", chunkSec)
            .apply()
        Toast.makeText(this, "Saved. Enable/switch to Qwen Voice Keyboard in Telegram.", Toast.LENGTH_LONG).show()
    }

    private fun prefs() = getSharedPreferences("pc_asr", Context.MODE_PRIVATE)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
