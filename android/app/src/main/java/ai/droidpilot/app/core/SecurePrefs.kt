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

    fun clearCredentials() {
        deviceId = null
        deviceSecret = null
        isPaired = false
    }
}
