package ai.qwenkeyboard.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class DebugAlibabaAiTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Thread {
            val started = System.currentTimeMillis()
            try {
                val prefs = context.getSharedPreferences("pc_asr", Context.MODE_PRIVATE)
                val key = prefs.getString("alibaba_modelstudio_api_key", "").orEmpty().trim()
                val savedModel = prefs.getString("alibaba_modelstudio_model", "qwen3.6-plus").orEmpty()
                val model = if (savedModel in listOf("qwen3.6-plus", "qwen3.6-flash")) savedModel else "qwen3.6-plus"
                if (model != savedModel) prefs.edit().putString("alibaba_modelstudio_model", model).apply()
                if (key.isBlank()) {
                    Log.e(TAG, "PHONE_TEST_FAIL no Alibaba key saved")
                    return@Thread
                }
                val sample = "um I think ah we should test this now uh 食咗飯未啊 跟住之後系咪去踢波咧"
                val prompt = """
Format this voice dictation transcript. Keep spoken fillers such as um, uh, ah, ar, 嗯, 啊, 呀, 啦, 咧. Add punctuation at proper places. Fix only obvious ASR spelling or Chinese character mistakes. Use Traditional Chinese and colloquial Cantonese where the input is Cantonese. Return only the final transcript text.

Text:
$sample
""".trimIndent()
                val fixed = callAlibaba(key, model, prompt)
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "PHONE_TEST_OK model=$model elapsedMs=$elapsed result=${fixed.take(220)}")
            } catch (t: Throwable) {
                Log.e(TAG, "PHONE_TEST_FAIL ${t.message}", t)
            }
        }.start()
    }

    private fun callAlibaba(key: String, model: String, prompt: String): String {
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
            .put("max_tokens", 220)
            .put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You format voice dictation. Return only final transcript text. No explanations."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299 || !resp.trimStart().startsWith("{")) throw RuntimeException("Alibaba HTTP $code: ${resp.take(180)}")
        val json = JSONObject(resp)
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    companion object {
        private const val TAG = "QwenAlibabaPhoneTest"
    }
}
