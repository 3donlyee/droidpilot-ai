package ai.droidpilot.app.access

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Compact accessibility-tree snapshot.
 *
 * Data-optimization rules (see docs/ARCHITECTURE.md):
 *  - compact JSON with short field names for text-heavy trees
 *  - a stable traversal order yields stable element ids (node_001…)
 *  - an MD5 hash lets the Worker/device skip re-sending unchanged trees
 */
object ScreenTree {

    class Snapshot(
        val packageName: String,
        val elements: JSONArray,
        val nodeMap: Map<String, AccessibilityNodeInfo>,
        val hash: String
    ) {
        fun toJson(maxNodes: Int): JSONObject {
            val out = JSONObject()
            out.put("package", packageName)
            out.put("node_count", elements.length())
            out.put("hash", hash)
            out.put("elements", elements)
            return out
        }
    }

    fun snapshot(root: AccessibilityNodeInfo, maxNodes: Int = 250): Snapshot {
        val packageName = root.packageName?.toString() ?: "unknown"
        val elements = JSONArray()
        val map = HashMap<String, AccessibilityNodeInfo>()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= maxNodes) return
            count++
            val id = String.format("node_%03d", count)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val o = JSONObject()
            o.put("id", id)
            o.put("text", node.text?.toString()?.take(80) ?: JSONObject.NULL)
            o.put("description", node.contentDescription?.toString()?.take(80) ?: JSONObject.NULL)
            o.put("class", node.className?.toString()?.substringAfterLast('.') ?: JSONObject.NULL)
            o.put("clickable", node.isClickable)
            o.put("enabled", node.isEnabled)
            o.put("bounds", JSONObject()
                .put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            elements.put(o)
            map[id] = node

            for (i in 0 until node.childCount) {
                try {
                    walk(node.getChild(i))
                } catch (_: Exception) {
                    // Nodes can disappear mid-traversal; skip them.
                }
            }
        }

        walk(root)

        // Hash over the semantic content (package + elements) so identical trees dedupe.
        val digest = MessageDigest.getInstance("MD5")
            .digest("$packageName|$elements".toByteArray())
        val hash = digest.joinToString("") { String.format("%02x", it) }

        return Snapshot(packageName, elements, map, hash)
    }
}
