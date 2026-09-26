package com.droidpilot.ai.device

import android.content.Context
import com.droidpilot.ai.Prefs
import com.droidpilot.ai.bridge.WorkerClient
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Manages the device → Worker pairing flow:
 *
 *   POST /api/device/register
 *      body: { device_name, model, android_version, ... }
 *      response: { device_id, device_secret, pairing_code }
 *
 * The pairing code is a short-lived 6-digit PIN shown to the user, who then
 * enters it in the Web UI to prove they own the device.
 *
 * Storage:
 *  Phase 1 (MVP) — plain SharedPreferences via [Prefs]. Marked as TODO for
 *                  EncryptedSharedPreferences migration.
 *  Phase 2+      — migrate to EncryptedSharedPreferences (see [Prefs.securePrefs]).
 *
 *  NOTE: NO real secrets are written here. The `device_secret` returned by
 *  the Worker is opaque random data and used only to authenticate WSS frames
 *  against the Worker.
 */
class PairingManager(
    private val context: Context,
    private val workerClient: WorkerClient = WorkerClient(),
    private val deviceManager: DeviceManager = DeviceManager(context)
) {

    @Serializable
    data class RegisterRequest(
        val device_name: String,
        val model: String,
        val manufacturer: String,
        val android_version: String,
        val sdk_int: Int,
        val screen_width: Int,
        val screen_height: Int
    )

    @Serializable
    data class RegisterResponse(
        val device_id: String,
        val device_secret: String,
        val pairing_code: String,
        val expires_in_seconds: Long? = null
    )

    sealed class Result {
        data class Success(val response: RegisterResponse) : Result()
        data class Failure(val reason: String, val code: Int? = null) : Result()
    }

    /**
     * Calls /api/device/register. On success, persists the returned
     * device_id / device_secret / pairing_code in [Prefs].
     */
    suspend fun register(workerUrl: String): Result {
        val info = deviceManager.collect(deviceId = "")
        val req = RegisterRequest(
            device_name = info.name,
            model = info.model,
            manufacturer = info.manufacturer,
            android_version = info.androidVersion,
            sdk_int = info.sdkInt,
            screen_width = info.screenWidth,
            screen_height = info.screenHeight
        )

        return try {
            Logger.i(TAG, "register: POST $workerUrl/api/device/register (model=${req.model})")
            val resp = workerClient.registerDevice(workerUrl, req)
            // Persist
            Prefs.get().let { prefs ->
                prefs.deviceId = resp.device_id
                prefs.deviceSecret = resp.device_secret
                prefs.pairingCode = resp.pairing_code
            }
            Logger.i(TAG, "register success: device_id=${resp.device_id} pairing_code=${resp.pairing_code}")
            Result.Success(resp)
        } catch (t: Throwable) {
            Logger.e(TAG, "register failed: $t")
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Convenience: return a JsonObject describing the current pairing state
     * (used to render the on-screen device ID + pairing code).
     */
    fun currentPairingJson(): JsonObject {
        val prefs = Prefs.get()
        return buildJsonObject {
            put("device_id", prefs.deviceId)
            put("pairing_code", prefs.pairingCode)
            put("paired", prefs.deviceSecret.isNotEmpty())
        }
    }

    companion object {
        private const val TAG = "PairingManager"
    }
}
