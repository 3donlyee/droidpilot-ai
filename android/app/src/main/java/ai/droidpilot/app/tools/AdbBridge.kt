package ai.droidpilot.app.tools

import android.content.Context
import android.provider.Settings
import ai.droidpilot.app.core.LogSystem
import java.io.IOException

/**
 * Optional ADB capability layer.
 *
 * The MVP does NOT depend on ADB in any way. When Wireless Debugging is
 * available on the device (Android 11+) this bridge is the place where a full
 * ADB client (pairing via SPAKE2 + ADB protocol) can be implemented later.
 * See docs/ARCHITECTURE.md → "ADB bridge (optional)".
 */
class AdbBridge(private val context: Context) {

    /** Whether wireless debugging is enabled in system settings (informational only). */
    fun wirelessDebuggingEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    } catch (_: Exception) {
        false
    }

    /**
     * MVP: full ADB pairing/protocol is not implemented, so the bridge is
     * intentionally never "available" — run_shell falls back to app-level
     * execution of policy-validated commands only.
     */
    fun isAvailable(): Boolean = false

    fun exec(command: String): Pair<Int, String> {
        LogSystem.log("adb", "exec requested but bridge not available: ${command.take(40)}")
        throw IOException("ADB bridge not available on this build")
    }
}
