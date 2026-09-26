package com.example.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.NodeBounds
import com.example.data.model.ScreenNode
import com.example.data.model.ScreenNodesResult
import java.security.MessageDigest

object ScreenNodeParser {

    private var lastTreeHash: String = ""
    private var lastResult: ScreenNodesResult? = null

    fun parseTree(
        root: AccessibilityNodeInfo?,
        packageName: String,
        forceFull: Boolean = false
    ): ScreenNodesResult {
        if (root == null) {
            return ScreenNodesResult(
                packageName = packageName,
                elementCount = 0,
                elements = emptyList(),
                hash = "empty"
            )
        }

        val elements = mutableListOf<ScreenNode>()
        var counter = 1

        traverse(root, elements) {
            val num = counter++
            "node_%03d".format(num)
        }

        // Compute hash for deduplication and diffing
        val sb = StringBuilder()
        for (el in elements) {
            sb.append(el.id).append(':')
                .append(el.text ?: "").append(':')
                .append(el.description ?: "").append(':')
                .append(el.bounds.left).append(',')
                .append(el.bounds.top).append(',')
                .append(el.bounds.right).append(',')
                .append(el.bounds.bottom).append(';')
        }
        val currentHash = computeHash(sb.toString())

        // Optimization: if hash is identical and not forced, return cached or compact diff
        if (!forceFull && currentHash == lastTreeHash && lastResult != null) {
            return lastResult!!.copy(
                timestamp = System.currentTimeMillis()
            )
        }

        val result = ScreenNodesResult(
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
            elementCount = elements.size,
            elements = elements,
            hash = currentHash
        )

        lastTreeHash = currentHash
        lastResult = result
        return result
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        result: MutableList<ScreenNode>,
        idGenerator: () -> String
    ) {
        if (node == null || !node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName
        val isClickable = node.isClickable
        val isEnabled = node.isEnabled
        val isScrollable = node.isScrollable
        val isEditable = node.isEditable

        // Filter: Keep if it has text, description, clickable, editable, or resource-id
        val isMeaningful = !text.isNullOrEmpty() ||
                !desc.isNullOrEmpty() ||
                isClickable ||
                isScrollable ||
                isEditable ||
                !viewId.isNullOrEmpty()

        if (isMeaningful) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Extract available action names
            val actions = mutableListOf<String>()
            val actionList = node.actionList
            if (actionList != null) {
                for (act in actionList) {
                    when (act.id) {
                        AccessibilityNodeInfo.ACTION_CLICK -> actions.add("click")
                        AccessibilityNodeInfo.ACTION_LONG_CLICK -> actions.add("long_click")
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> actions.add("scroll_forward")
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> actions.add("scroll_backward")
                        AccessibilityNodeInfo.ACTION_SET_TEXT -> actions.add("set_text")
                    }
                }
            }

            val nodeId = if (!viewId.isNullOrEmpty()) {
                viewId.substringAfter(":id/")
            } else {
                idGenerator()
            }

            result.add(
                ScreenNode(
                    id = nodeId,
                    text = text?.ifEmpty { null },
                    description = desc?.ifEmpty { null },
                    className = node.className?.toString()?.substringAfterLast('.'),
                    clickable = isClickable,
                    enabled = isEnabled,
                    bounds = NodeBounds(
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom
                    ),
                    actions = actions
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverse(child, result, idGenerator)
                child.recycle()
            }
        }
    }

    private fun computeHash(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
