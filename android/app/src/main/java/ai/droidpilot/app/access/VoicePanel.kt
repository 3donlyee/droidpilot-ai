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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.droidpilot.app.core.ApiClient
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import org.json.JSONObject
import java.util.Locale

/**
 * Aiminos Voice Panel — appears when the user taps the Accessibility button.
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

        val title = label("◆ Aiminos", 15f, 0xFF7C5CFF.toInt(), true)
        status = label("جاهز", 13f, 0xFF8B95A9.toInt())

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(button("🎤 تكلم", { startListening() }))
        row.addView(button("⏹", { stopTask() }))
        row.addView(button("▶", { resumeTask() }))
        row.addView(button("✕", { hide() }))

        col.addView(title)
        col.addView(status)
        col.addView(row)

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
                setStatus("✗ لم أسمعك جيدًا — حاول مرة أخرى")
                sr.destroy()
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
                    setStatus("✗ ${r?.optString("error") ?: "تعذر الإرسال"}")
                    return@Thread
                }
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000)
                    val t = api.getTurn(turnId) ?: continue
                    val st = t.optString("state")
                    if (st == "done" || st == "stopped" || st == "error") {
                        val final = t.optString("final_response").ifBlank { t.optString("error") }
                        setStatus("◆ ${final.take(90)}")
                        speak(final)
                        LogSystem.log("voice", "turn $turnId → $st")
                        return@Thread
                    }
                }
                setStatus("⏱️ انتهت مهلة الاستجابة")
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
