package ai.droidpilot.app.tools

import android.content.Context
import android.provider.Settings
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/**
 * Aiminos ADB bridge — optional deep-control layer.
 *
 * When the user enables ADB mode (one-time PC setup: `adb tcpip 5555`), tools
 * gain a powerful fallback: `input tap/swipe/text` work even where the
 * accessibility tree cannot reach. Without ADB everything still works through
 * the accessibility service — this bridge is strictly additive.
 */
class AdbBridge(private val context: Context) {

    private val prefs by lazy { SecurePrefs(context) }

    /** Whether wireless/TCP debugging is enabled in system settings (informational). */
    fun wirelessDebuggingEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    } catch (_: Exception) {
        false
    }

    /** User opt-in flag (Settings screen). Protocol availability is checked live. */
    fun isAvailable(): Boolean = prefs.adbEnabled

    /**
     * Run one shell command through ADB. Throws IOException when ADB is off,
     * not listening, or not yet authorized.
     */
    fun exec(command: String): Pair<Int, String> {
        val c = AdbClient.connect(prefs)
        try {
            val out = c.shell(command)
            LogSystem.log("adb", "exec ok: ${command.take(40)} -> ${out.take(60)}")
            return 0 to out
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    companion object {
        /**
         * Generate (once) and persist the RSA-2048 keypair used for ADB auth.
         * Returns (privateBase64PKCS8, publicBase64X509). The private key is
         * stored in EncryptedSharedPreferences and never leaves the device.
         */
        fun ensureKeys(prefs: SecurePrefs): Pair<String, String> {
            val priv = prefs.adbKeyPriv
            val pub = prefs.adbKeyPub
            if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) return priv to pub
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp: java.security.KeyPair = kpg.generateKeyPair()
            val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
            val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
            prefs.adbKeyPriv = privB64
            prefs.adbKeyPub = pubB64
            LogSystem.log("adb", "generated new RSA keypair for device auth")
            return privB64 to pubB64
        }
    }
}
