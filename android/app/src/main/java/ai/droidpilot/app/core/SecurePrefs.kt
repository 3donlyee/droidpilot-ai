package ai.droidpilot.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for device credentials and configuration.
 * The device_secret is stored encrypted at rest; it never leaves the device
 * except when proving identity to the Cloudflare Worker over HTTPS.
 */
class SecurePrefs(context: Context) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "droidpilot_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            LogSystem.log("prefs", "Encrypted prefs failed, falling back to plain: ${t.message}")
            context.getSharedPreferences("droidpilot_plain", Context.MODE_PRIVATE)
        }
    }

    var baseUrl: String?
        get() = prefs.getString("base_url", null)
        set(v) = prefs.edit().putString("base_url", v).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(v) = prefs.edit().putString("device_id", v).apply()

    var deviceSecret: String?
        get() = prefs.getString("device_secret", null)
        set(v) = prefs.edit().putString("device_secret", v).apply()

    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    var isPaired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()

    var allowInsecureLocal: Boolean
        get() = prefs.getBoolean("allow_insecure_local", false)
        set(v) = prefs.edit().putBoolean("allow_insecure_local", v).apply()

    /** ADB deep-control mode (user opt-in from Settings row). */
    var adbEnabled: Boolean
        get() = prefs.getBoolean("adb_enabled", false)
        set(v) = prefs.edit().putBoolean("adb_enabled", v).apply()

    /** Wireless ADB paired successfully via the in-app pairing flow. */
    var adbPaired: Boolean
        get() = prefs.getBoolean("adb_paired", false)
        set(v) = prefs.edit().putBoolean("adb_paired", v).apply()

    /** Verified ADB TLS endpoint "ip:port" (connection port, changes per toggle). */
    var adbConnectEndpoint: String?
        get() = prefs.getString("adb_connect_endpoint", null)
        set(v) = prefs.edit().putString("adb_connect_endpoint", v).apply()

    /** Last seen pairing port (informational). */
    var adbLastPairPort: Int
        get() = prefs.getInt("adb_last_pair_port", -1)
        set(v) = prefs.edit().putInt("adb_last_pair_port", v).apply()

    /** RSA-2048 keypair for ADB auth (PKCS8 private, X509 public, Base64). */
    var adbKeyPriv: String?
        get() = prefs.getString("adb_key_priv", null)
        set(v) = prefs.edit().putString("adb_key_priv", v).apply()

    var adbKeyPub: String?
        get() = prefs.getString("adb_key_pub", null)
        set(v) = prefs.edit().putString("adb_key_pub", v).apply()

    fun clearCredentials() {
        deviceId = null
        deviceSecret = null
        isPaired = false
    }
}
