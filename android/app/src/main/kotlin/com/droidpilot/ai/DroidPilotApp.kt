package com.droidpilot.ai

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.droidpilot.ai.util.Logger

/**
 * DroidPilot AI — application entry point.
 *
 * Initialises:
 *  - [Logger]
 *  - [Prefs] — plain SharedPreferences wrapper for non-sensitive runtime config
 *    (worker URL, last device id, last pairing code). Secrets (device_secret)
 *    live in a separate EncryptedSharedPreferences file in production.
 *
 *  NOTE on encryption:
 *  For Phase 1 (MVP) we keep device_secret in plain SharedPreferences for
 *  simplicity & reliability across OEM quirks. Phase 2+ migrates to
 *  EncryptedSharedPreferences unconditionally — see [securePrefs] stub.
 */
class DroidPilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.i(TAG, "DroidPilotApp onCreate")
        prefs = Prefs(this)
        // Pre-warm encrypted prefs lazily — defer to first access to avoid ANR on cold boot.
    }

    companion object {
        private const val TAG = "DroidPilotApp"

        @Volatile
        private var instance: DroidPilotApp? = null

        @Volatile
        private var prefs: Prefs? = null

        fun get(): DroidPilotApp =
            instance ?: error("DroidPilotApp not initialised yet")

        fun prefs(): Prefs =
            prefs ?: synchronized(this) {
                prefs ?: Prefs(instance ?: error("DroidPilotApp not initialised"))
                    .also { prefs = it }
            }
    }
}

/**
 * Thin wrapper around SharedPreferences for non-sensitive runtime config.
 */
class Prefs(context: Context) {

    private val context: Context = context.applicationContext

    private val sp: SharedPreferences =
        context.getSharedPreferences("droidpilot", Context.MODE_PRIVATE)

    var workerUrl: String
        get() = sp.getString(KEY_WORKER_URL, "").orEmpty()
        set(value) { sp.edit().putString(KEY_WORKER_URL, value).apply() }

    var deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, "").orEmpty()
        set(value) { sp.edit().putString(KEY_DEVICE_ID, value).apply() }

    var pairingCode: String
        get() = sp.getString(KEY_PAIRING_CODE, "").orEmpty()
        set(value) { sp.edit().putString(KEY_PAIRING_CODE, value).apply() }

    /**
     * device_secret — for Phase 1 MVP we store it here.
     * TODO(Phase 2): migrate to [securePrefs].
     */
    var deviceSecret: String
        get() = sp.getString(KEY_DEVICE_SECRET, "").orEmpty()
        set(value) { sp.edit().putString(KEY_DEVICE_SECRET, value).apply() }

    fun clearPairing() {
        sp.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_DEVICE_SECRET)
            .apply()
    }

    /**
     * Lazily-initialised EncryptedSharedPreferences (for future use).
     *
     * EncryptedSharedPreferences uses Android Keystore; it can fail on first
     * install with strange OEM Keymaster issues. We catch and fall back to
     * plain prefs in MVP rather than crashing the app.
     */
    @Volatile
    private var securePrefsCache: SharedPreferences? = null

    @Synchronized
    fun securePrefs(): SharedPreferences? {
        if (securePrefsCache != null) return securePrefsCache
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            securePrefsCache = EncryptedSharedPreferences.create(
                context,
                "droidpilot_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            securePrefsCache
        } catch (t: Throwable) {
            Logger.w("Prefs", "EncryptedSharedPreferences unavailable, using plain prefs: $t")
            null
        }
    }

    companion object {
        private const val KEY_WORKER_URL = "worker_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_SECRET = "device_secret"
    }
}
