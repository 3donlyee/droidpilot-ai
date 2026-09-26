package com.droidpilot.ai.tools

import kotlinx.serialization.json.JsonObject

/**
 * DroidPilot AI — Tool contract.
 *
 * Every capability the AI model can invoke is a [Tool]. The Worker emits
 * commands shaped like `{tool, arguments}` and the on-device [ToolExecutor]
 * routes them to the right [Tool] via [ToolRegistry].
 *
 * Implementations live in:
 *  - [AccessibilityTools] : get_screen_nodes, get_current_package, tap_element
 *  - [GestureTools]       : swipe_up, swipe_down
 *  - [AppTools]           : open_app, get_device_info
 *  - [ShellTools]         : run_shell
 *  - [ScreenshotTools]    : take_screenshot
 *  - [TypeTextTool]       : type_text
 */
interface Tool {
    /** Canonical name used in command envelopes, e.g. `get_screen_nodes`. */
    val name: String

    /** Human-readable description (also surfaced to the AI model as the tool spec). */
    val description: String

    /**
     * Execute the tool. Must be idempotent where possible — the Worker may
     * retry on transient failures.
     */
    suspend fun execute(args: JsonObject): ToolResult
}

/**
 * Standard result envelope returned by every tool.
 *
 * - [success] true if the tool completed its task
 * - [data]    optional JsonObject payload (the tool's "return value")
 * - [error]   optional error code + message when [success] is false
 *
 * Conventional error codes:
 *   - ELEMENT_NOT_FOUND
 *   - PERMISSION_DENIED
 *   - INVALID_ARGUMENT
 *   - SCREENSHOT_REQUIRES_PERMISSION
 *   - REQUIRES_CONFIRMATION
 *   - BLOCKED_BY_POLICY
 *   - TIMEOUT
 *   - INTERNAL_ERROR
 */
data class ToolResult(
    val success: Boolean,
    val data: JsonObject? = null,
    val error: ErrorBody? = null
) {
    data class ErrorBody(
        val code: String,
        val message: String
    )

    companion object {
        fun ok(data: JsonObject): ToolResult = ToolResult(success = true, data = data)

        fun error(code: String, message: String): ToolResult =
            ToolResult(success = false, error = ErrorBody(code, message))
    }
}
