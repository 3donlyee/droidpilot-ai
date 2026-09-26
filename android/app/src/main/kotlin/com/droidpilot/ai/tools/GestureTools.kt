package com.droidpilot.ai.tools

import com.droidpilot.ai.DroidPilotAccessibilityService
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gesture tools — vertical swipes implemented via
 * [android.accessibilityservice.AccessibilityService.dispatchGesture].
 *
 * (Horizontal swipes + multi-finger gestures are intentionally omitted from
 * MVP; we'll add `swipe_left`, `swipe_right`, `long_press`, `pinch` in Phase 2.)
 */

class SwipeUpTool : Tool {
    override val name: String = "swipe_up"
    override val description: String =
        "Swipe UP (scroll content downward). Travels ~70% of screen height."

    override suspend fun execute(args: JsonObject): ToolResult =
        doSwipe("up")
}

class SwipeDownTool : Tool {
    override val name: String = "swipe_down"
    override val description: String =
        "Swipe DOWN (scroll content upward). Travels ~70% of screen height."

    override suspend fun execute(args: JsonObject): ToolResult =
        doSwipe("down")
}

private fun doSwipe(direction: String): ToolResult {
    val service = DroidPilotAccessibilityService.instance
        ?: return ToolResult.error(
            "PERMISSION_DENIED",
            "AccessibilityService is not running."
        )
    return try {
        val outcome = service.performSwipe(direction)
        val success = (outcome["success"] as? JsonPrimitive)?.content == "true"
        if (success) ToolResult.ok(outcome)
        else ToolResult.error("INTERNAL_ERROR", "swipe_$direction dispatch failed")
    } catch (t: SecurityException) {
        Logger.w("Gesture", "swipe_$direction denied: ${t.message}")
        ToolResult.error("PERMISSION_DENIED", "swipe denied: ${t.message}")
    } catch (t: Throwable) {
        Logger.e("Gesture", "swipe_$direction failed: $t")
        ToolResult.error("INTERNAL_ERROR", t.message ?: t.javaClass.simpleName)
    }
}
