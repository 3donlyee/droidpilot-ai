package com.droidpilot.ai.tools

import com.droidpilot.ai.bridge.CommandEnvelope
import com.droidpilot.ai.bridge.CommandValidator
import com.droidpilot.ai.bridge.ResultEnvelope
import com.droidpilot.ai.util.JsonUtil
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI — Tool Executor.
 *
 * Receives a [CommandEnvelope] (id + tool name + arguments) from the Worker,
 * resolves the [Tool] via [ToolRegistry], validates args via
 * [CommandValidator], executes the tool, and returns a [ResultEnvelope]
 * ready to send back over the WSS channel.
 *
 * Error handling is exhaustive:
 *   - Unknown tool        → ResultEnvelope(success=false, error=UNKNOWN_TOOL)
 *   - Invalid arguments   → ResultEnvelope(success=false, error=INVALID_ARGUMENT)
 *   - Rejected by policy  → ResultEnvelope(success=false, error=BLOCKED_BY_POLICY)
 *   - Confirmation needed  → ResultEnvelope(success=false, error=REQUIRES_CONFIRMATION)
 *   - Tool throws         → ResultEnvelope(success=false, error=INTERNAL_ERROR)
 */
object ToolExecutor {

    private val validator = CommandValidator()

    /**
     * Execute a single [CommandEnvelope]. Always returns a [ResultEnvelope]
     * (never throws) — errors are wrapped into the result.
     */
    suspend fun execute(envelope: CommandEnvelope): ResultEnvelope {
        val id = envelope.id
        val toolName = envelope.tool
        Logger.i(TAG, "→ execute id=$id tool=$toolName args=${envelope.arguments}")

        val tool = ToolRegistry.get(toolName)
        if (tool == null) {
            return buildError(
                id,
                "UNKNOWN_TOOL",
                "no tool named '$toolName' is registered"
            )
        }

        // Policy check (currently only run_shell)
        when (validator.validate(toolName, envelope.arguments)) {
            CommandValidator.ValidationResult.REJECTED -> {
                Logger.w(TAG, "✗ command rejected by policy: $toolName")
                return buildError(
                    id,
                    "BLOCKED_BY_POLICY",
                    "command was classified BLOCKED by CommandValidator"
                )
            }
            CommandValidator.ValidationResult.NEEDS_CONFIRMATION -> {
                Logger.w(TAG, "! command requires confirmation: $toolName")
                // Phase 1 MVP: return error; Phase 2+ shows dialog.
                return buildError(
                    id,
                    "REQUIRES_CONFIRMATION",
                    "command requires explicit user confirmation (Phase 2 dialog)"
                )
            }
            CommandValidator.ValidationResult.ALLOWED -> { /* proceed */ }
        }

        // Execute
        val result = try {
            tool.execute(envelope.arguments)
        } catch (t: SecurityException) {
            Logger.e(TAG, "tool $toolName denied: $t")
            return buildError(id, "PERMISSION_DENIED", t.message ?: t.javaClass.simpleName)
        } catch (t: Throwable) {
            Logger.e(TAG, "tool $toolName threw: $t")
            return buildError(id, "INTERNAL_ERROR", t.message ?: t.javaClass.simpleName)
        }

        // Build result envelope
        return if (result.success) {
            ResultEnvelope(
                id = id,
                success = true,
                data = result.data ?: JsonObject(emptyMap())
            )
        } else {
            ResultEnvelope(
                id = id,
                success = false,
                error = result.error?.let {
                    ResultEnvelope.ErrorBody(it.code, it.message)
                } ?: ResultEnvelope.ErrorBody("UNKNOWN_ERROR", "no error body provided")
            )
        }
    }

    /**
     * Convenience wrapper that builds an error [ResultEnvelope] with the right
     * shape: `{type:"result", id, success:false, error:{code,message}}`.
     */
    private fun buildError(id: String, code: String, message: String): ResultEnvelope {
        return ResultEnvelope(
            id = id,
            success = false,
            error = ResultEnvelope.ErrorBody(code, message)
        )
    }

    private const val TAG = "ToolExecutor"
}
