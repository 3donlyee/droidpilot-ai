package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NodeBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

@JsonClass(generateAdapter = true)
data class ScreenNode(
    val id: String,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val bounds: NodeBounds = NodeBounds(),
    val actions: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ScreenNodesResult(
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val elementCount: Int,
    val elements: List<ScreenNode>,
    val hash: String = ""
)

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    val deviceId: String,
    val pairingCode: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val product: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val accessibilityEnabled: Boolean,
    val foregroundPackage: String = ""
)

@JsonClass(generateAdapter = true)
data class ToolCommand(
    val id: String,
    val tool: String,
    val arguments: Map<String, Any?> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class ToolResult(
    val id: String,
    val tool: String,
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
