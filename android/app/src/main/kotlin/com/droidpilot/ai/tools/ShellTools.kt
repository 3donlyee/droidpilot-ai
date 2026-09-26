package com.droidpilot.ai.tools

import com.droidpilot.ai.bridge.AdbBridge
import com.droidpilot.ai.bridge.CommandValidator
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI — Shell tool.
 *
 * Executes shell commands through the AdbBridge if available. Phase 1 MVP:
 * the AdbBridge is always unavailable, so the tool returns
 * `ADB_NOT_AVAILABLE` for any SAFE command and the appropriate policy
 * result for REQUIRES_CONFIRMATION / BLOCKED commands.
 *
 * The classification itself happens in [CommandValidator] (called by the
 * [ToolExecutor] before this tool's `execute` is even invoked). This tool
 * is responsible for *running* the command, not for deciding if it's safe.
 *
 * Phase 7 (ADB) wiring:
 *   - SAFE commands                  → run via AdbBridge.executeShell(command)
 *   - REQUIRES_CONFIRMATION          → Phase 2 dialog; in MVP returns REQUIRES_CONFIRMATION
 *                                      (this code path is unreachable in MVP because the
 *                                      ToolExecutor already returns early — kept defensive)
 *   - BLOCKED                        → unreachable; ToolExecutor returns BLOCKED_BY_POLICY
 */
class RunShellTool : Tool {

    override val name: String = "run_shell"
    override val description: String =
        "Execute a shell command on the device (via local ADB when available). " +
            "Commands are classified SAFE / REQUIRES_CONFIRMATION / BLOCKED by the " +
            "CommandValidator. SAFE commands run immediately."

    override suspend fun execute(args: JsonObject): ToolResult {
        val cmd = (args["command"] as? JsonPrimitive)?.content
            ?: return ToolResult.error("INVALID_ARGUMENT", "missing `command` argument")

        // Defensive double-check — in case the executor was bypassed.
        val policy = CommandValidator().classify(cmd)
        if (policy == CommandValidator.CommandPolicy.BLOCKED) {
            return ToolResult.error(
                "BLOCKED_BY_POLICY",
                "command was classified BLOCKED: $cmd"
            )
        }
        if (policy == CommandValidator.CommandPolicy.REQUIRES_CONFIRMATION) {
            // Phase 1: no UI dialog yet. Return error.
            return ToolResult.error(
                "REQUIRES_CONFIRMATION",
                "command requires explicit user confirmation (Phase 2 will add a dialog)"
            )
        }

        // SAFE — try the ADB bridge.
        val bridge = AdbBridge.get()
        if (!bridge.isAvailable()) {
            // Phase 1 MVP: no ADB → cannot actually run any shell command.
            Logger.w(TAG, "run_shell: ADB_NOT_AVAILABLE for '$cmd'")
            return ToolResult.error(
                "ADB_NOT_AVAILABLE",
                "ADB bridge is not enabled (Phase 7 feature). SAFE commands cannot be " +
                    "executed without ADB. Use Accessibility-based tools instead."
            )
        }
        // Phase 7 path — execute via ADB.
        val outcome = bridge.executeShell(cmd)
        val success = (outcome["success"] as? JsonPrimitive)?.content == "true"
        return if (success) ToolResult.ok(outcome)
        else ToolResult.error(
            "SHELL_FAILED",
            (outcome["message"] as? JsonPrimitive)?.content ?: "shell execution failed"
        )
    }

    companion object { private const val TAG = "RunShell" }
}
