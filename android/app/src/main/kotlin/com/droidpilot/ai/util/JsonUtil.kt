package com.droidpilot.ai.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * DroidPilot AI — JSON helpers for the Accessibility tree.
 *
 * Two design goals drive the format:
 *
 *  1. **Stable IDs**: the same logical element (same bounds + same text/desc)
 *     must produce the *same* `id` across two snapshots so the Worker can
 *     dedup. We use an 8-hex-char SHA-1 of the canonical tuple
 *     (bounds + text + description + className).
 *
 *  2. **Compactness**: empty / default fields are omitted. The Worker only
 *     needs enough signal to (a) render the tree to text for the AI model and
 *     (b) target an element by id for `tap_element`.
 */
object JsonUtil {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false          // strip empty/default values
        explicitNulls = false           // drop nulls entirely
        prettyPrint = false
        isLenient = true
    }

    // ---------------------------------------------------------------------------
    // AccessibilityNodeInfo -> JSON
    // ---------------------------------------------------------------------------

    /**
     * Convert a single AccessibilityNodeInfo into a compact JsonObject.
     *
     * The returned object ALWAYS contains `id` (stable hash), and includes
     * `text`, `description`, `class`, `clickable`, `enabled`, `bounds`,
     * and `actions` only when they have meaningful values.
     */
    fun accessibilityNodeToJson(node: AccessibilityNodeInfo?): JsonObject? {
        if (node == null) return null
        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: SecurityException) {
            // Some OEMs throw when retrieving bounds off certain nodes — ignore.
            return null
        }

        val text = node.text?.toString().orEmpty().takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString().orEmpty().takeIf { it.isNotBlank() }
        val cls = node.className?.toString().orEmpty().takeIf { it.isNotBlank() }

        val nodeId = stableNodeId(bounds, text, desc, cls)

        return buildJsonObject {
            put("id", nodeId)
            if (text != null) put("text", text)
            if (desc != null) put("description", desc)
            if (cls != null) put("className", cls)
            if (node.isClickable) put("clickable", true)
            if (!node.isEnabled) put("enabled", false) else put("enabled", true)
            // bounds: always include — required for tap coordinate inference
            put("bounds", buildJsonObject {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
            val actionNames = collectActions(node)
            if (actionNames.isNotEmpty()) {
                put("actions", buildJsonArray { actionNames.forEach { add(it) } })
            }
        }
    }

    /**
     * Walk the whole tree starting at [root], returning a JSON array of compact
     * node objects (depth-first). Use this for the `get_screen_nodes` tool output.
     *
     * The recursion is bounded to prevent runaway trees (some apps produce
     * very deep views); siblings beyond [maxSiblings] are dropped silently.
     */
    fun treeToJsonArray(root: AccessibilityNodeInfo?, maxSiblings: Int = 200): JsonArray {
        val out = ArrayList<JsonObject>(64)
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val j = accessibilityNodeToJson(node)
            if (j != null) out.add(j)
            val count = node.childCount
            val capped = if (count > maxSiblings) maxSiblings else count
            for (i in 0 until capped) {
                try {
                    walk(node.getChild(i))
                } catch (_: SecurityException) {
                    // skip inaccessible subtree
                } catch (_: IllegalStateException) {
                    // node recycled
                    return
                }
            }
        }
        walk(root)
        return buildJsonArray { out.forEach { add(it) } }
    }

    /**
     * Stable id for a node — 8 hex chars derived from SHA-1 of the
     * canonical tuple (bounds + text + description + className).
     *
     * Two nodes with identical bounds+text+desc+class in different
     * snapshots get the same id, which lets the Worker dedup trees.
     */
    fun stableNodeId(
        bounds: Rect,
        text: String?,
        desc: String?,
        className: String?
    ): String {
        val canonical = buildString {
            append(bounds.left).append(',')
            append(bounds.top).append(',')
            append(bounds.right).append(',')
            append(bounds.bottom).append('|')
            append(text.orEmpty()).append('|')
            append(desc.orEmpty()).append('|')
            append(className.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray())
        // First 4 bytes -> 8 hex chars
        return "node_" + buildString {
            for (i in 0 until 4) {
                append(String.format("%02x", digest[i]))
            }
        }
    }

    /**
     * SHA-256 dedup hash of a canonical JSON element tree.
     * The Worker compares this against the previous snapshot — if identical,
     * it can return `{unchanged: true}` to skip a multimodal re-render.
     */
    fun dedupHash(tree: JsonElement): String {
        val canonical = tree.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return buildString {
            digest.forEach { b -> append(String.format("%02x", b)) }
        }
    }

    /**
     * Drop empty fields recursively: removes keys whose value is an empty
     * string / empty object / empty array / null. Returns the pruned element.
     */
    fun compactTree(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val pruned = element.entries
                    .mapNotNull { (k, v) ->
                        val compacted = compactTree(v)
                        when (compacted) {
                            is JsonPrimitive -> if (compacted.content.isEmpty()) null
                            else k to compacted
                            is JsonObject -> if (compacted.isEmpty()) null else k to compacted
                            is JsonArray -> if (compacted.isEmpty()) null else k to compacted
                            else -> k to compacted
                        }
                    }.toMap()
                JsonObject(pruned)
            }
            is JsonArray -> JsonArray(element.map { compactTree(it) })
            else -> element
        }
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private fun collectActions(node: AccessibilityNodeInfo): List<String> {
        val actions = node.actions
        if (actions == 0) return emptyList()
        val names = ArrayList<String>(4)
        // We only list the most useful action names — reduces JSON size.
        val map = intArrayOf(
            AccessibilityNodeInfo.ACTION_CLICK to "click",
            AccessibilityNodeInfo.ACTION_LONG_CLICK to "long_click",
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "scroll_forward",
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "scroll_backward",
            AccessibilityNodeInfo.ACTION_SET_TEXT to "set_text",
            AccessibilityNodeInfo.ACTION_FOCUS to "focus",
            AccessibilityNodeInfo.ACTION_SELECT to "select"
        )
        for ((mask, name) in map) {
            if (actions and mask != 0) names.add(name)
        }
        return names
    }
}
