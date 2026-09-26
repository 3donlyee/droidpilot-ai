package com.droidpilot.ai.tools

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.droidpilot.ai.DroidPilotAccessibilityService
import com.droidpilot.ai.DroidPilotApp
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI — TypeText tool.
 *
 * Strategy:
 *   1) Walk the accessibility tree to find the currently focused node.
 *   2) If the focused node supports ACTION_SET_TEXT, dispatch it directly
 *      (works on most editable EditText / TextView instances, including
 *      in-app web editors that expose a focused node).
 *   3) Fallback: try the InputMethodManager to inject characters. This is
 *      less reliable — it only works for the soft-keyboard target window
 *      and fails if no editable field has focus.
 *
 * Arguments:
 *   `text`  (required) — the string to type
 *   `element_id` (optional) — if provided, set text on that node instead of
 *     the currently focused one. Useful for fields that aren't focused yet.
 */
class TypeTextTool : Tool {

    override val name: String = "type_text"
    override val description: String =
        "Type `text` into the focused editable field (or a specific element_id)."

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = (args["text"] as? JsonPrimitive)?.content
            ?: return ToolResult.error("INVALID_ARGUMENT", "missing `text` argument")

        val service = DroidPilotAccessibilityService.instance
            ?: return ToolResult.error("PERMISSION_DENIED", "AccessibilityService not running.")

        val root = service.getRootInActiveWindowSafe()
            ?: return ToolResult.error("ELEMENT_NOT_FOUND", "no active window root")

        val target = pickTargetNode(root, args)

        if (target == null) {
            try { root.recycle() } catch (_: Throwable) {}
            return ToolResult.error(
                "ELEMENT_NOT_FOUND",
                "no focused editable node; pass `element_id` or tap an input first"
            )
        }

        // Attempt 1: ACTION_SET_TEXT on the focused node
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
        } catch (t: SecurityException) {
            Logger.w(TAG, "ACTION_SET_TEXT denied: ${t.message}")
            false
        }

        try { target.recycle() } catch (_: Throwable) {}
        try { root.recycle() } catch (_: Throwable) {}

        return if (ok) {
            Logger.i(TAG, "type_text: ACTION_SET_TEXT ok (${text.length} chars)")
            ToolResult.ok(buildJsonObject {
                put("method", "action_set_text")
                put("length", text.length)
            })
        } else {
            // Attempt 2: InputMethodManager fallback
            fallbackIme(text)
        }
    }

    /**
     * Pick the node to set text on:
     *  - if `element_id` provided, walk the tree to find that node
     *  - else walk and return the first focused + editable node
     */
    private fun pickTargetNode(
        root: android.view.accessibility.AccessibilityNodeInfo,
        args: JsonObject
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val elementId = (args["element_id"] as? JsonPrimitive)?.content
        if (elementId != null) {
            // Reuse TapElementTool's matching via stable id by re-walking the tree.
            return findEditableNodeById(root, elementId)
        }
        return findFocusedEditable(root)
    }

    /**
     * Walk the tree to find the node that is focused AND editable.
     */
    private fun findFocusedEditable(
        node: android.view.accessibility.AccessibilityNodeInfo?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: SecurityException) { null } ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
            try { child.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Walk the tree to find an editable node by stable id.
     */
    private fun findEditableNodeById(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        elementId: String
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = android.graphics.Rect()
        try { node.getBoundsInScreen(bounds) } catch (_: SecurityException) { return null }
        val id = com.droidpilot.ai.util.JsonUtil.stableNodeId(
            bounds,
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.className?.toString()
        )
        if (id == elementId && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: SecurityException) { null } ?: continue
            val found = findEditableNodeById(child, elementId)
            if (found != null) return found
            try { child.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Fallback: ask the InputMethodManager to commit the text.
     * This only works if the IME is currently bound to one of our windows
     * (which is rare for a service). Most of the time this returns false.
     */
    private fun fallbackIme(text: String): ToolResult {
        val ctx: Context = DroidPilotApp.get()
        return try {
            @Suppress("DEPRECATION", "InternalApi")
            val ime = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            // There is no public API to inject text into an arbitrary app's IME
            // from a service context. The cleanest cross-app approach would
            // be to use a custom InputMethodService (Phase 3 idea).
            Logger.w(TAG, "type_text: IME fallback unavailable from service context")
            ToolResult.error(
                "ELEMENT_NOT_FOUND",
                "no focused editable node and IME fallback unavailable from service context"
            )
        } catch (t: Throwable) {
            Logger.e(TAG, "type_text IME fallback failed: $t")
            ToolResult.error("INTERNAL_ERROR", "type_text failed: ${t.message}")
        }
    }

    companion object { private const val TAG = "TypeText" }
}
