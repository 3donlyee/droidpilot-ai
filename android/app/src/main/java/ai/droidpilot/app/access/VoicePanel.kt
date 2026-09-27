package ai.droidpilot.app.access

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.droidpilot.app.core.ApiClient
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import org.json.JSONObject
import java.util.Locale

/**
 * aMiNo Voice Panel — appears when the user taps the Accessibility button.
 *
 *   🎤 speak → Speech-to-Text → POST /api/chat (source:"voice") → poll turn → TTS reply
 *   ⏹ stop   → POST /api/turn/stop
 *   ▶ resume → chat("أكمل المهمة السابقة…")
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW permission needed).
 */
class VoicePanel(private val context: Context) {

    companion object {
        @Volatile private var current: VoicePanel? = null
        fun toggle(svc: AccessibilityService) {
            synchronized(this) {
                val p = current
                if (p != null) p.hide() else { current = VoicePanel(svc).also { it.show() } }
            }
        }
        private fun release() { current = null }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs by lazy { SecurePrefs(context) }
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var listening = false

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun bg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text; setTextColor(color); textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text; textSize = 14f; isAllCaps = false
            background = bg(0xFF232B3F.toInt(), 14)
            setTextColor(0xFFE9EDF6.toInt())
            setOnClickListener { onClick() }
        }

    fun show() {
        if (root != null) return
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xF0111621.toInt(), 22)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        val title = label("◆ aMiNo", 15f, 0xFF7C5CFF.toInt(), true)
        status = label("جاهز — تكلم أو اكتب أمرك", 13f, 0xFF8B95A9.toInt())

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(button("🎤 تكلم", { startListening() }))
        row.addView(button("⏹", { stopTask() }))
        row.addView(button("▶", { resumeTask() }))
        row.addView(button("✕", { hide() }))

        // FIX: text fallback — speech recognition is a single point of failure
        // on ColorOS (no recognizer / no ar-DZ / mic denied). Now the user can
        // ALWAYS type the command. Requires the window to be focusable.
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(context).apply {
            hint = "أو اكتب أمرك هنا…"
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(0xFFE9EDF6.toInt())
            setHintTextColor(0xFF6B7386.toInt())
            background = bg(0xFF232B3F.toInt(), 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(button("إرسال", {
            val txt = input.text.toString().trim()
            if (txt.isNotEmpty()) {
                input.setText("")
                sendToBrain(txt)
            }
        }))

        col.addView(title)
        col.addView(status)
        col.addView(row)
        col.addView(inputRow)

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // FIX: window must be focusable for the EditText fallback to work.
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        try {
            wm.addView(col, params)
            root = col
            initTts()
            LogSystem.log("voice", "panel shown")
        } catch (t: Throwable) {
            LogSystem.log("voice", "panel add failed: ${t.message}")
        }
    }

    private fun LinearLayout.children(): Any = this

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; status = null
        tts?.shutdown(); tts = null; ttsReady = false
        release()
        LogSystem.log("voice", "panel hidden")
    }

    private fun setStatus(t: String) {
        root?.post { status?.text = t }
    }

    // ------------------------------------------------------------- TTS
    private fun initTts() {
        try {
            tts = TextToSpeech(context) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val ar = tts?.setLanguage(Locale("ar"))
                    if (ar == TextToSpeech.LANG_MISSING_DATA || ar == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                }
            }
        } catch (t: Throwable) { LogSystem.log("voice", "tts init failed: ${t.message}") }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try { tts?.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, "aiminos_reply") } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------- STT → chat
    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            setStatus("✗ التعرف على الصوت غير متاح على هذا الجهاز"); return
        }
        listening = true
        setStatus("🎙️ تحدّث الآن…")
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-DZ")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("⟳ أفهم ما قلت…") }
            override fun onError(error: Int) {
                listening = false
                sr.destroy()
                // FIX: specific Arabic guidance per error instead of the same
                // misleading «لم أسمعك جيدًا» for everything.
                setStatus(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "✗ صلاحية الميكروفون مرفوضة — فعّلها من إعدادات التطبيق أو اكتب الأمر"
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "✗ لم أتعرف على الكلام — حاول مجددًا أو اكتب الأمر"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "✗ لم أسمع صوتًا — اقترب من الميكروفون أو اكتب الأمر"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "✗ مشكلة شبكة في التعرف على الصوت — اكتب الأمر"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "✗ التعرف على الصوت مشغول — انتظر ثانية ثم أعد المحاولة"
                        else -> "✗ خطأ في التعرف على الصوت ($error) — اكتب الأمر"
                    }
                )
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                sr.destroy()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) sendToBrain(text) else setStatus("✗ لم أتعرّف على الكلام")
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        sr.startListening(intent)
    }

    private fun sendToBrain(message: String) {
        if (prefs.deviceId.isNullOrBlank()) { setStatus("✗ اقترن الهاتف أولاً من الشاشة الرئيسية"); return }
        setStatus("⟳ «${message.take(40)}» → العقل المدبّر…")
        Thread {
            try {
                val api = ApiClient(prefs)
                val r = api.chat(message, source = "voice")
                val turnId = r?.optString("turn_id").orEmpty()
                if (turnId.isBlank()) {
                    // FIX: translate server errors instead of showing raw codes.
                    val err = r?.optString("error").orEmpty()
                    setStatus(
                        when {
                            err.contains("DEVICE_BUSY") -> "⏳ هناك مهمة قيد التنفيذ — اضغط ⏹ لإيقافها ثم أعد المحاولة"
                            err.contains("DEVICE_OFFLINE") -> "✗ الهاتف غير متصل بالعقل — افتح التطبيق واضغط «بدء الاتصال»"
                            err.isBlank() -> "✗ تعذر الإرسال — تحقق من الرابط والاقتران"
                            else -> "✗ ${err.take(60)}"
                        }
                    )
                    return@Thread
                }
                // FIX: multi-step tasks legitimately run minutes — follow the
                // turn while it is alive (was a hard 180 s deadline), and show
                // progress so the panel never looks dead.
                val deadline = System.currentTimeMillis() + 420_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000)
                    val t = api.getTurn(turnId) ?: continue
                    val st = t.optString("state")
                    if (st == "thinking" || st == "awaiting_device") {
                        val steps = t.optJSONArray("steps")?.length() ?: 0
                        setStatus("⟳ يعمل… الخطوة $steps")
                        continue
                    }
                    if (st == "done" || st == "stopped" || st == "error") {
                        val final = t.optString("final_response").ifBlank { t.optString("error") }
                        setStatus("◆ ${final.take(90)}")
                        speak(final)
                        LogSystem.log("voice", "turn $turnId → $st")
                        return@Thread
                    }
                }
                setStatus("⏱️ ما زالت المهمة تعمل خلف الكواليس — اضغط ▶ للمتابعة أو ⏹ للإيقاف")
            } catch (t: Throwable) {
                LogSystem.log("voice", "voice turn failed: ${t.message}")
                setStatus("✗ ${t.message?.take(60)}")
            }
        }.start()
    }

    private fun stopTask() {
        if (prefs.deviceId.isNullOrBlank()) { setStatus("✗ لا جهاز مقترن"); return }
        setStatus("⏹ أوقف المهمة…")
        Thread {
            try { ApiClient(prefs).stopTurn("voice"); setStatus("⏹ تم الإيقاف") }
            catch (t: Throwable) { setStatus("✗ ${t.message?.take(60)}") }
        }.start()
    }

    private fun resumeTask() = sendToBrain("أكمل المهمة السابقة من حيث توقفت.")
}
