package com.droidpilot.ai.device

import android.content.Context
import android.graphics.Point
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.droidpilot.ai.DroidPilotAccessibilityService
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Collects [DeviceInfo] from the running Android system, and provides
 * the current foreground package name (via Accessibility if available,
 * UsageStatsManager fallback deliberately omitted because it requires an
 * additional non-essential permission for MVP).
 */
class DeviceManager(private val context: Context) {

    /**
     * Build a fresh [DeviceInfo] snapshot. The `deviceId` is taken from
     * [com.droidpilot.ai.Prefs] — empty if not yet paired.
     */
    fun collect(deviceId: String = ""): DeviceInfo {
        val (w, h, dpi) = screenInfo()
        val (level, charging) = batteryInfo()
        return DeviceInfo(
            deviceId = deviceId,
            name = Build.DEVICE ?: "unknown",
            model = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            screenDensity = dpi,
            screenWidth = w,
            screenHeight = h,
            batteryLevel = level,
            isCharging = charging
        )
    }

    /**
     * Returns the *current foreground package name*, or null if not
     * discoverable. Uses AccessibilityService as primary source.
     */
    fun foregroundPackage(): String? {
        val service = DroidPilotAccessibilityService.instance ?: return null
        return service.getForegroundPackage()
    }

    /**
     * Returns the foreground package name as a compact JsonObject
     * for tool responses: `{"package": "com.example"}`.
     */
    fun foregroundPackageJson(): JsonObject {
        val pkg = foregroundPackage()
        return buildJsonObject {
            if (pkg != null) put("package", pkg)
            else put("package", JsonPrimitive(""))
        }
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private fun screenInfo(): Triple<Int, Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return Triple(0, 0, 0)
            val metrics: DisplayMetrics = context.resources.displayMetrics
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            Triple(point.x, point.y, metrics.densityDpi)
        } catch (t: Throwable) {
            Logger.w(TAG, "screenInfo failed: $t")
            Triple(0, 0, 0)
        }
    }

    private fun batteryInfo(): Pair<Int, Boolean> {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return Pair(-1, false)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toInt()
            val charging = bm.isCharging
            Pair(level.coerceIn(0, 100), charging)
        } catch (t: Throwable) {
            Logger.w(TAG, "batteryInfo failed: $t")
            Pair(-1, false)
        }
    }

    /**
     * Optional PowerManager wake-lock helper. The foreground service uses this
     * to keep the CPU alive during long-running command sequences.
     */
    fun newWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidPilot:cmd")
        } catch (t: Throwable) {
            Logger.w(TAG, "wake-lock failed: $t")
            null
        }
    }

    companion object {
        private const val TAG = "DeviceManager"

        @Volatile
        private var instance: DeviceManager? = null

        fun get(context: Context): DeviceManager =
            instance ?: synchronized(this) {
                instance ?: DeviceManager(context.applicationContext).also { instance = it }
            }
    }
}
