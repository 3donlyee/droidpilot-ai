package ai.droidpilot.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.droidpilot.app.core.ApiClient
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import ai.droidpilot.app.service.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SecurePrefs
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvStatus: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvA11y: TextView
    private lateinit var etUrl: EditText
    private lateinit var pinCard: LinearLayout
    private lateinit var tvDeviceId: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvAdb: TextView
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = SecurePrefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvDevice = findViewById(R.id.tvDevice)
        tvA11y = findViewById(R.id.tvA11y)
        etUrl = findViewById(R.id.etUrl)
        pinCard = findViewById(R.id.pinCard)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvPin = findViewById(R.id.tvPin)
        tvLog = findViewById(R.id.tvLog)

        etUrl.setText(prefs.baseUrl ?: "")

        findViewById<Button>(R.id.btnSaveUrl).setOnClickListener { saveUrl() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startConnection() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopConnection() }

        addAdbRow()
        requestPermissions()

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            adbStateReceiver,
            IntentFilter(ai.droidpilot.app.adb.AdbPairingService.BROADCAST_STATE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** ADB deep-control toggle + REAL wireless pairing button. */
    private fun addAdbRow() {
        try {
            val content = findViewById<android.view.ViewGroup>(android.R.id.content)
            val scroll = content.getChildAt(0) as? android.widget.ScrollView ?: return
            val root = scroll.getChildAt(0) as? LinearLayout ?: return

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 8, 8, 8)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(this).apply {
                text = "وضع ADB — تحكم أعمق"
                textSize = 13f
                isChecked = prefs.adbEnabled
                setOnCheckedChangeListener { _, v ->
                    prefs.adbEnabled = v
                    Toast.makeText(this@MainActivity, if (v) "وضع ADB مُفعّل" else "وضع ADB مُعطّل", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(cb)
            root.addView(row)

            val pairBtn = Button(this).apply {
                text = "بدء اقتران ADB"
                textSize = 14f
                setOnClickListener { startAdbPairing() }
            }
            val pairRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 0, 8, 8)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            pairRow.addView(pairBtn)
            tvAdb = TextView(this).apply {
                textSize = 13f
                setPadding(16, 0, 16, 0)
                text = adbStatusText()
            }
            pairRow.addView(tvAdb)
            root.addView(pairRow)
        } catch (_: Throwable) {
            // cosmetic only — never break app startup
        }
    }

    private fun adbStatusText(): String = when {
        prefs.adbPaired && !prefs.adbConnectEndpoint.isNullOrBlank() ->
            "● ADB متصل (${prefs.adbConnectEndpoint})"
        prefs.adbPaired -> "✔ ADB مقترن — بانتظار منفذ الاتصال"
        else -> "○ ADB غير مقترن"
    }

    /** Real wireless ADB pairing — user stays on the Wireless debugging screen. */
    private fun startAdbPairing() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("اقتران Wireless ADB يتطلب Android 11 أو أحدث")
            return
        }
        LogSystem.log("adb", "user requested ADB pairing")
        val intent = Intent(this, ai.droidpilot.app.adb.AdbPairingService::class.java)
            .setAction(ai.droidpilot.app.adb.AdbPairingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        toast("افتح: الإعدادات ← خيارات المطورين ← التصحيح اللاسلكي ← اقتران الجهاز برمز")
    }

    private val adbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ai.droidpilot.app.adb.AdbPairingService.BROADCAST_STATE) return
            val state = intent.getStringExtra(ai.droidpilot.app.adb.AdbPairingService.EXTRA_STATE) ?: return
            val message = intent.getStringExtra(ai.droidpilot.app.adb.AdbPairingService.EXTRA_MESSAGE) ?: ""
            if (this@MainActivity::tvAdb.isInitialized) {
                tvAdb.text = when (state) {
                    ai.droidpilot.app.adb.AdbPairingService.STATE_CONNECTED -> adbStatusText()
                    ai.droidpilot.app.adb.AdbPairingService.STATE_PAIRED -> "✔ ADB مقترن — بانتظار منفذ الاتصال"
                    ai.droidpilot.app.adb.AdbPairingService.STATE_WAITING -> "⟳ $message"
                    ai.droidpilot.app.adb.AdbPairingService.STATE_PAIRING -> "⟳ $message"
                    ai.droidpilot.app.adb.AdbPairingService.STATE_FAILED -> "✖ $message"
                    else -> tvAdb.text
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------- actions

    private fun saveUrl() {
        val url = etUrl.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) {
            toast("أدخل رابط العامل أولًا")
            return
        }
        if (url.startsWith("http://") && !url.contains("10.0.2.2") && !url.contains("localhost")) {
            toast("HTTPS فقط — الرابط يجب أن يبدأ بـ https://")
            return
        }
        prefs.baseUrl = url
        toast("تم حفظ الرابط ✓")
        refreshStatus()
    }

    private fun connect() {
        if (prefs.baseUrl.isNullOrBlank()) {
            toast("Save the Worker URL first")
            return
        }
        LogSystem.log("ui", "registering device…")
        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ApiClient(prefs).register() }
                val deviceId = resp.optString("device_id")
                val pin = resp.optString("pairing_code")
                tvDeviceId.text = deviceId
                tvPin.text = pin
                pinCard.visibility = LinearLayout.VISIBLE
                toast("أدخل الرمز $pin في واجهة الويب ثم فعّل الإتاحة")
                refreshStatus()
            } catch (t: Throwable) {
                LogSystem.log("ui", "register failed: ${t.message}")
                toast("Register failed: ${t.message?.take(80)}")
            }
        }
    }

    private fun openAccessibilitySettings() {
        // FIX: open aMiNo's OWN accessibility page (toggle included) instead of
        // dumping the user into the long general list where it's easy to miss.
        // Uses the ACTION string literal ("android.settings.ACCESSIBILITY_DETAILS",
        // API 31+) because the Settings.* constant may not resolve against older
        // compileSdk targets; on any failure we fall back to the general page.
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cn = ComponentName(packageName,
                    ai.droidpilot.app.access.DroidPilotAccessibilityService::class.java.name)
                val i = Intent("android.settings.ACCESSIBILITY_DETAILS")
                i.putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
                startActivity(i)
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                toast("تعذر فتح إعدادات الإتاحة")
            }
        }
    }

    private fun startConnection() {
        if (!accessibilityEnabled()) {
            toast("فعّل خدمة إتاحة aMiNo أولًا ثم أعد المحاولة")
            openAccessibilitySettings()
            return
        }
        if (!prefs.isPaired && prefs.deviceId.isNullOrBlank()) {
            toast("اضغط «اقتران بالويب» أولًا")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        refreshStatus()
    }

    private fun stopConnection() {
        stopService(Intent(this, ConnectionService::class.java))
        refreshStatus()
    }

    // -------------------------------------------------------------- status

    private fun accessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = "$packageName/${ai.droidpilot.app.access.DroidPilotAccessibilityService::class.java.canonicalName}"
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.let { si -> "${si.packageName}/${si.name}" == expected } }
    }

    private fun refreshStatus() {
        val connected = ConnectionService.running
        val paired = prefs.isPaired || !prefs.deviceId.isNullOrBlank()
        tvStatus.text = when {
            connected -> "● متصل"
            paired -> "○ مقترن — الخدمة متوقفة"
            else -> "○ غير متصل"
        }
        tvDevice.text = "الجهاز: ${prefs.deviceId ?: "—"} (${Build.MODEL})"
        tvA11y.text = "الإتاحة: ${if (accessibilityEnabled()) "مُفعّلة ✓" else "معطّلة — فعّلها من الزر أدناه"}"
        val lines = LogSystem.dump(14)
        tvLog.text = lines.joinToString("\n")
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
