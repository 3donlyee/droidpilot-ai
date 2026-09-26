package com.droidpilot.ai

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidpilot.ai.device.PairingManager
import com.droidpilot.ai.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DroidPilot AI — Main Activity.
 *
 * Responsibilities:
 *  - Show app status (Disconnected / Connecting / Paired / Connected)
 *  - Show device_id + pairing_code (so the user can enter them in the Web UI)
 *  - Editable Worker URL EditText (persisted to SharedPreferences)
 *  - Buttons:
 *      • Enable Accessibility → opens system Accessibility Settings
 *      • Start Service        → starts [DroidPilotForegroundService]
 *      • Pair                 → calls [PairingManager.register] and stores result
 *  - Live log view (subscribes to [Logger.logsLive])
 */
class MainActivity : AppCompatActivity() {

    // View bindings (no ViewBinding plugin to keep the build minimal)
    private lateinit var workerUrlInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnEnableAccessibility: android.widget.Button
    private lateinit var btnStartService: android.widget.Button
    private lateinit var btnPair: android.widget.Button
    private lateinit var statusValue: android.widget.TextView
    private lateinit var deviceIdValue: android.widget.TextView
    private lateinit var pairingCodeValue: android.widget.TextView
    private lateinit var logText: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        workerUrlInput = findViewById(R.id.workerUrlInput)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnStartService = findViewById(R.id.btnStartService)
        btnPair = findViewById(R.id.btnPair)
        statusValue = findViewById(R.id.statusValue)
        deviceIdValue = findViewById(R.id.deviceIdValue)
        pairingCodeValue = findViewById(R.id.pairingCodeValue)
        logText = findViewById(R.id.logText)

        // Restore last worker URL
        val prefs = Prefs.get()
        val savedUrl = prefs.workerUrl
        if (savedUrl.isNotBlank()) workerUrlInput.setText(savedUrl)
        else workerUrlInput.setText(getString(R.string.hint_worker_url))

        // Bind buttons
        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStartService.setOnClickListener { onStartServiceClicked() }

        btnPair.setOnClickListener { onPairClicked() }

        // Observe Logger
        Logger.logsLive.observe(this) { text ->
            logText.text = text
        }

        Logger.i(TAG, "MainActivity onCreate")

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ----------------------------------------------------------------------

    private fun onStartServiceClicked() {
        val url = workerUrlInput.text?.toString().orEmpty().trim()
        if (url.isBlank()) {
            toast(R.string.toast_worker_url_required); return
        }
        // Persist URL
        Prefs.get().workerUrl = url

        val deviceId = Prefs.get().deviceId
        val deviceSecret = Prefs.get().deviceSecret
        if (deviceId.isBlank() || deviceSecret.isBlank()) {
            toast("Pair first (no device_id / device_secret).")
            return
        }

        if (!isAccessibilityEnabled()) {
            toast(R.string.toast_accessibility_not_enabled)
            return
        }

        DroidPilotForegroundService.start(this, url, deviceId, deviceSecret)
        toast(R.string.toast_service_started)
        statusValue.text = getString(R.string.status_connecting)
    }

    private fun onPairClicked() {
        val url = workerUrlInput.text?.toString().orEmpty().trim()
        if (url.isBlank()) {
            toast(R.string.toast_worker_url_required); return
        }
        Prefs.get().workerUrl = url

        toast(R.string.toast_pairing_started)
        btnPair.isEnabled = false
        statusValue.text = getString(R.string.status_connecting)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PairingManager(this@MainActivity).register(url)
            }
            btnPair.isEnabled = true
            when (result) {
                is PairingManager.Result.Success -> {
                    val r = result.response
                    deviceIdValue.text = r.device_id
                    pairingCodeValue.text = r.pairing_code
                    statusValue.text = getString(R.string.status_paired)
                    toast(getString(R.string.toast_pairing_success, r.device_id))
                    Logger.i(TAG, "paired: device_id=${r.device_id} code=${r.pairing_code}")
                }
                is PairingManager.Result.Failure -> {
                    statusValue.text = getString(R.string.status_disconnected)
                    toast(getString(R.string.toast_pairing_failed, result.reason))
                    Logger.e(TAG, "pairing failed: ${result.reason}")
                }
            }
        }
    }

    // ----------------------------------------------------------------------

    private fun refreshStatus() {
        val prefs = Prefs.get()
        if (prefs.deviceId.isNotBlank()) {
            deviceIdValue.text = prefs.deviceId
            pairingCodeValue.text = prefs.pairingCode
            statusValue.text = if (DroidPilotAccessibilityService.instance != null)
                getString(R.string.status_paired)
            else
                getString(R.string.status_paired)
        } else {
            deviceIdValue.text = getString(R.string.value_unknown)
            pairingCodeValue.text = getString(R.string.value_unknown)
            statusValue.text = getString(R.string.status_disconnected)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, DroidPilotAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colon = if (enabled.contains(':')) ':' else ';'
        val splitter = TextUtils.SimpleStringSplitter(colon)
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val cn = ComponentName.unflattenFromString(splitter.next())
            if (cn != null && cn == expected) return true
        }
        return false
    }

    private fun toast(msg: CharSequence) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    private fun toast(resId: Int) = toast(getString(resId))

    companion object {
        private const val TAG = "MainActivity"
    }
}
