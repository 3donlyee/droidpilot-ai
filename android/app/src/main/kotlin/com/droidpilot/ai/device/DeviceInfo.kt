package com.droidpilot.ai.device

import kotlinx.serialization.Serializable

/**
 * Snapshot of device hardware + system info. Sent to the Worker on
 * `/api/device/register` and returned by the `get_device_info` tool.
 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val screenDensity: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val batteryLevel: Int,        // 0..100
    val isCharging: Boolean
) {
    companion object {
        const val EMPTY_DEVICE_ID = ""
    }
}
