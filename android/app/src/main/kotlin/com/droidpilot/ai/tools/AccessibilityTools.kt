package com.droidpilot.ai.tools

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.droidpilot.ai.DroidPilotAccessibilityService
import com.droidpilot.ai.util.JsonUtil
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tools that read / act on the Accessibility tree.
 *
 *   - [GetScreenNodesTool]      : `get_screen_nodes`  — dump compact tree
 *   - [GetCurrentPackageTool]   : `get_current_package`
 *   - [TapElementTool]          : `tap_element`        — click a node by stable id
 */
// All classes in this file share the same package — declarations follow.

class GetScreenNodesTool : Tool {
    override val name: String = "get_screen_nodes"
    override val description: String =
        "Return the current screen as a compact JSON tree of accessibility " +
            "nodes (id, text, description, className, clickable, enabled, bounds, actions)."

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = DroidPilotAccessibilityService.instance
            ?: return ToolResult.error(
                "PERMISSION_DENIED",
                "AccessibilityService is not running. The user must enable it in Settings."
            )

        val root = service.getRootInActiveWindowSafe()
            ?: return ToolResult.error(
                "ELEMENT_NOT_FOUND",
                "rootInActiveWindow is null (no active window or access denied)."
            )

        val pkg = root.packageName?.toString().orEmpty()
        val nodes = JsonUtil.treeToJsonArray(root)

        val payload = buildJsonObject {
            put("package", pkg)
            put("elements", nodes)
            put("node_count", nodes.size)
            // Include dedup hash so Worker can skip re-rendering if unchanged.
            put("hash", JsonUtil.dedupHash(nodes))
        }
        // We MUST release the node tree so the framework can recycle it.
        try {
            root.recycle()
        } catch (_: Throwable) { /* best effort */ }

        Logger.i(TAG, "get_screen_nodes: ${nodes.size} nodes for $pkg")
        return ToolResult.ok(payload)
    }

    companion object { private const val TAG = "GetScreenNodes" }
}

class GetCurrentPackageTool : Tool {
    override val name: String = "get_current_package"
    override val description: String =
        "Return the package name of the foreground app."

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = DroidPilotAccessibilityService.instance
            ?: return ToolResult.error(
                "PERMISSION_DENIED",
                "AccessibilityService is not running."
            )
        val pkg = service.getForegroundPackage()
            ?: return ToolResult.error(
                "ELEMENT_NOT_FOUND",
                "Could not determine foreground package."
            )
        return ToolResult.ok(buildJsonObject { put("package", pkg) })
    }
}

class TapElementTool : Tool {
    override val name: String = "tap_element"
    override val description: String =
        "Tap the element with the given stable `element_id` " +
            "(as returned by get_screen_nodes). Alternatively pass `x` and `y` " +
            "for an absolute tap."

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = DroidPilotAccessibilityService.instance
            ?: return ToolResult.error("PERMISSION_DENIED", "AccessibilityService not running.")

        // Option A: explicit x/y tap.
        val xPrim = args["x"]
        val yPrim = args["y"]
        if (xPrim is JsonPrimitive && yPrim is JsonPrimitive) {
            val x = xPrim.content.toFloatOrNull()
            val y = yPrim.content.toFloatOrNull()
            if (x == null || y == null) {
                return ToolResult.error("INVALID_ARGUMENT", "x/y must be numbers")
            }
            val ok = service.performTapAt(x, y)
            return if (ok) ToolResult.ok(buildJsonObject {
                put("performed", "gesture_tap")
                put("x", x.toInt()); put("y", y.toInt())
            }) else ToolResult.error("INTERNAL_ERROR", "gesture dispatch failed")
        }

        // Option B: element_id lookup.
        val elementId = (args["element_id"] as? JsonPrimitive)?.content
            ?: (args["id"] as? JsonPrimitive)?.content
            ?: return ToolResult.error(
                "INVALID_ARGUMENT",
                "missing `element_id` (or `x`/`y`) argument"
            )

        val root = service.getRootInActiveWindowSafe()
            ?: return ToolResult.error("ELEMENT_NOT_FOUND", "no active window root")

        val node = findNodeById(root, elementId)
        return if (node == null) {
            try { root.recycle() } catch (_: Throwable) {}
            ToolResult.error("ELEMENT_NOT_FOUND", "no node with id=$elementId")
        } else {
            val result = service.performTap(node)
            try { node.recycle() } catch (_: Throwable) {}
            try { root.recycle() } catch (_: Throwable) {}
            // service.performTap already returns a JsonObject describing outcome
            val success = (result["success"] as? JsonPrimitive)?.content == "true"
            if (success) ToolResult.ok(result)
            else ToolResult.error(
                "INTERNAL_ERROR",
                (result["error"] as? JsonObject)?.let { e ->
                    ((e["message"] as? JsonPrimitive)?.content)
                } ?: "tap failed"
            )
        }
    }

    /**
     * Walk the tree to find a node whose stable id matches [elementId].
     * The id is computed identically to how [GetScreenNodesTool] generates
     * it (bounds + text + desc + class) so that ids are stable across calls.
     */
    private fun findNodeById(root: AccessibilityNodeInfo, elementId: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (found != null || node == null) return
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: SecurityException) { return }
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val cls = node.className?.toString()
            val id = JsonUtil.stableNodeId(bounds, text, desc, cls)
            if (id == elementId) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                try { walk(node.getChild(i)) } catch (_: SecurityException) {}
                if (found != null) return
            }
        }
        walk(root)
        return found
    }
}
