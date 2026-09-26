package com.droidpilot.ai.bridge

import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI — ADB Bridge (STUB).
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  PHASE 7 ONLY.
 *
 *  This class is intentionally inert in the MVP. When `AdbBridge.isAvailable()`
 *  returns `false` (always in Phase 1), the agent uses the AccessibilityService
 *  exclusively. No ADB-related functionality is exposed to the model.
 *
 *  The contract below documents how Phase 7 will wire this up:
 *
 *    • Wireless Debugging is enabled ON THE DEVICE (no PC).
 *    • The user opts in via a UI toggle.
 *    • This bridge connects to `localhost:5555` (Android → Android, same
 *      device — only possible from Android 11+ via Wireless Debugging).
 *    • A tiny allowlist of ADB commands is exposed (mostly diagnostics:
 *      `dumpsys`, `getprop`, `pm list packages`).
 *    • All ADB output is treated as untrusted — never parsed for control flow.
 *
 *  No root, no Shizuku, no PC.
 *
 *  Note: connecting to localhost ADB from the same device requires the user
 *  to pair via the OS dialog at least once. We do NOT attempt automated
 *  pairing — it is not possible without root.
 * ─────────────────────────────────────────────────────────────────────────
 */
class AdbBridge {

    /** Always false in MVP. Phase 7 flips this once connected. */
    fun isAvailable(): Boolean {
        // TODO(Phase 7): return true when the local ADB socket is connected
        return false
    }

    /** Phase 7: connect to localhost:5555. No-op in MVP. */
    fun start() {
        Logger.i(TAG, "start: ADB bridge unavailable in MVP — no-op")
    }

    /** Phase 7: tear down local ADB socket. No-op in MVP. */
    fun stop() {
        Logger.i(TAG, "stop: ADB bridge unavailable in MVP — no-op")
    }

    /**
     * Phase 7: execute a single ADB shell command on localhost.
     * In MVP, returns `{"error":"ADB_NOT_AVAILABLE"}`.
     */
    fun executeShell(command: String): JsonObject {
        Logger.i(TAG, "executeShell: '$command' — ADB_NOT_AVAILABLE in MVP")
        return buildJsonObject {
            put("success", false)
            put("error", "ADB_NOT_AVAILABLE")
            put("message", "ADB bridge is not enabled. Phase 7 feature.")
            put("requested_command", command)
        }
    }

    companion object {
        private const val TAG = "AdbBridge"

        @Volatile
        private var instance: AdbBridge? = null

        fun get(): AdbBridge =
            instance ?: synchronized(this) {
                instance ?: AdbBridge().also { instance = it }
            }
    }
}
