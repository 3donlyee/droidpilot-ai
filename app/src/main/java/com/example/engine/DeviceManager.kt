package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.data.model.DeviceInfo
import com.example.data.repository.DroidPilotRepository
import com.example.service.DroidPilotAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

class DeviceManager(
    private val context: Context,
    private val repository: DroidPilotRepository
) {
    private val _deviceInfo = MutableStateFlow(createInitialDeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    private var cachedDeviceId: String = ""
    private var cachedPairingCode: String = ""
    private var cachedDeviceSecret: String = ""

    suspend fun initialize() {
        val prefs = context.getSharedPreferences("droidpilot_device", Context.MODE_PRIVATE)
        cachedDeviceId = prefs.getString("device_id", null) ?: run {
            val randomSuffix = (1000..9999).random().toString(16).uppercase().take(4)
            val newId = "DROID-$randomSuffix"
            prefs.edit().putString("device_id", newId).apply()
            newId
        }

        cachedPairingCode = prefs.getString("pairing_code", null) ?: run {
            val pin = String.format("%06d", SecureRandom().nextInt(1000000))
            prefs.edit().putString("pairing_code", pin).apply()
            pin
        }

        cachedDeviceSecret = prefs.getString("device_secret", null) ?: run {
            val secret = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_secret", secret).apply()
            secret
        }

        refreshDeviceInfo()
    }

    fun getDeviceId(): String = cachedDeviceId
    fun getPairingCode(): String = cachedPairingCode
    fun getDeviceSecret(): String = cachedDeviceSecret

    fun regeneratePairingCode(): String {
        val pin = String.format("%06d", SecureRandom().nextInt(1000000))
        cachedPairingCode = pin
        context.getSharedPreferences("droidpilot_device", Context.MODE_PRIVATE)
            .edit()
            .putString("pairing_code", pin)
            .apply()
        refreshDeviceInfo()
        return pin
    }

    fun refreshDeviceInfo(): DeviceInfo {
        val batteryStatus = getBatteryStatus()
        val metrics = getScreenDimensions()
        val isA11yActive = DroidPilotAccessibilityService.isServiceRunning()
        val foregroundPkg = DroidPilotAccessibilityService.currentForegroundPackage

        val info = DeviceInfo(
            deviceId = cachedDeviceId.ifEmpty { "DROID-INIT" },
            pairingCode = cachedPairingCode.ifEmpty { "------" },
            model = Build.MODEL ?: "Unknown",
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            brand = Build.BRAND ?: "Unknown",
            product = Build.PRODUCT ?: "Unknown",
            androidVersion = Build.VERSION.RELEASE ?: "13",
            sdkInt = Build.VERSION.SDK_INT,
            batteryLevel = batteryStatus.first,
            isCharging = batteryStatus.second,
            screenWidth = metrics.first,
            screenHeight = metrics.second,
            accessibilityEnabled = isA11yActive,
            foregroundPackage = foregroundPkg
        )
        _deviceInfo.value = info
        return info
    }

    private fun createInitialDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceId = "DROID-INIT",
            pairingCode = "------",
            model = Build.MODEL ?: "OPPO Reno5",
            manufacturer = Build.MANUFACTURER ?: "OPPO",
            brand = Build.BRAND ?: "OPPO",
            product = Build.PRODUCT ?: "CPH2159",
            androidVersion = Build.VERSION.RELEASE ?: "13",
            sdkInt = Build.VERSION.SDK_INT,
            batteryLevel = 100,
            isCharging = false,
            screenWidth = 1080,
            screenHeight = 2400,
            accessibilityEnabled = false,
            foregroundPackage = ""
        )
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, isCharging)
        } catch (_: Exception) {
            Pair(100, false)
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } catch (_: Exception) {
            Pair(1080, 2400)
        }
    }
}
