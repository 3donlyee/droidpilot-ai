package ai.droidpilot.app.tools

import android.os.Build
import android.content.Context
import android.provider.Settings
import ai.droidpilot.app.adb.AdbKeyManager
import ai.droidpilot.app.adb.AdbTlsClient
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/**
 * aMiNo ADB bridge — optional deep-control layer.
 *
 * Routing (strictly additive — accessibility tools keep working without it):
 *   1. Wireless ADB TLS (preferred): paired in-app via AdbPairingService —
 *      real SPAKE2+TLS pairing, connection on the mDNS-discovered port.
 *   2. Legacy TCP:5555 (requires one-time `adb tcpip 5555` from a PC).
 */
class AdbBridge(private val context: Context) {

    private val prefs by lazy { SecurePrefs(context) }

    /** Whether wireless debugging is enabled in system settings (informational). */
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
        // 1. Wireless TLS path (Android 11+, paired via the in-app flow)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && prefs.adbPaired) {
            val endpoint = prefs.adbConnectEndpoint
            if (!endpoint.isNullOrBlank()) {
                try {
                    val key = AdbKeyManager.load(prefs)
                    val idx = endpoint.lastIndexOf(':')
                    if (idx > 0) {
                        val host = endpoint.substring(0, idx)
                        val port = endpoint.substring(idx + 1).toInt()
                        val out = AdbTlsClient.runShell(host, port, key, command)
                        LogSystem.log("adb", "tls exec ok: ${command.take(40)} -> ${out.take(60)}")
                        return 0 to out
                    }
                } catch (t: Throwable) {
                    LogSystem.log("adb", "tls exec failed (${t.message}); falling back to legacy")
                }
            }
        }
        // 2. Legacy classic path (TCP 5555, PC setup)
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
