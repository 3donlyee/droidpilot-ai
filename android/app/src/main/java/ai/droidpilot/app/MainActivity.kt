package ai.droidpilot.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
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

        requestNotificationPermission()
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
            toast("Enter the Worker URL first")
            return
        }
        if (url.startsWith("http://") && !url.contains("10.0.2.2") && !url.contains("localhost")) {
            toast("HTTPS only — http:// is allowed only for local dev")
            return
        }
        prefs.baseUrl = url
        toast("Worker URL saved")
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
                toast("Enter PIN $pin in the Web UI, then enable Accessibility")
                refreshStatus()
            } catch (t: Throwable) {
                LogSystem.log("ui", "register failed: ${t.message}")
                toast("Register failed: ${t.message?.take(80)}")
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            toast("Accessibility settings unavailable")
        }
    }

    private fun startConnection() {
        if (!accessibilityEnabled()) {
            toast("Enable the DroidPilot accessibility service first")
            openAccessibilitySettings()
            return
        }
        if (!prefs.isPaired && prefs.deviceId.isNullOrBlank()) {
            toast("Press Connect first")
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
            connected -> "● Connected"
            paired -> "○ Paired — service stopped"
            else -> "○ Not connected"
        }
        tvDevice.text = "Device: ${prefs.deviceId ?: "—"} (${Build.MODEL})"
        tvA11y.text = "Accessibility: ${if (accessibilityEnabled()) "enabled" else "disabled"}"
        val lines = LogSystem.dump(14)
        tvLog.text = lines.joinToString("\n")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
