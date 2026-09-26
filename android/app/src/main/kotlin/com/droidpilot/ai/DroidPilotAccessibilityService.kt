package com.droidpilot.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.droidpilot.ai.util.JsonUtil
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI Accessibility Service.
 *
 * Responsibilities:
 *  - Provide a globally-accessible [instance] to the rest of the app
 *    (so the [ToolExecutor] can call into the active service at any time).
 *  - Expose safe wrappers around `rootInActiveWindow`, `performGlobalAction`,
 *    `performAction(ACTION_CLICK)`, and `dispatchGesture`.
 *
 * Notes:
 *  - We do NOT process accessibility events here. The agent polls the tree
 *    via [getRootInActiveWindowSafe] on demand — much cheaper and avoids
 *    event-flood issues on some OEMs.
 *  - All public methods catch SecurityException — these are thrown by the
 *    framework when the calling code lacks the right permission / process.
 */
class DroidPilotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We poll on-demand — ignore events. (Keeps CPU/wake low.)
    }

    override fun onInterrupt() {
        Logger.w(TAG, "AccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Logger.i(TAG, "AccessibilityService unbound")
        return false
    }

    // --------------------------------------------------------------------------
    // Public API used by tools
    // --------------------------------------------------------------------------

    /**
     * Safe wrapper around [getRootInActiveWindow]. Returns null if the
     * window is unavailable or access is denied.
     */
    fun getRootInActiveWindowSafe(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (t: SecurityException) {
            Logger.w(TAG, "getRootInActiveWindowSafe denied: ${t.message}")
            null
        } catch (t: IllegalStateException) {
            Logger.w(TAG, "getRootInActiveWindowSafe recycled: ${t.message}")
            null
        }
    }

    /**
     * Returns the package name of the currently active window's root, or null.
     */
    fun getForegroundPackage(): String? {
        return try {
            getRootInActiveWindowSafe()?.packageName?.toString()
        } catch (t: SecurityException) {
            Logger.w(TAG, "getForegroundPackage denied: ${t.message}")
            null
        }
    }

    /**
     * Taps the centre of [node]. Returns a structured result.
     *
     * Strategy:
     *  1) Try `node.performAction(ACTION_CLICK)` (cheap, native).
     *  2) If that fails, fall back to dispatching a tap gesture at the
     *     node's screen centre via [dispatchGesture].
     */
    fun performTap(node: AccessibilityNodeInfo?): JsonObject {
        if (node == null) {
            return errorResult("ELEMENT_NOT_FOUND", "node is null")
        }
        // Strategy 1: native click
        return try {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Logger.i(TAG, "performTap: ACTION_CLICK ok")
                buildJsonObject {
                    put("performed", "click")
                    put("success", true)
                }
            } else {
                // Strategy 2: gesture at centre
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                val ok = dispatchTap(cx, cy)
                Logger.i(TAG, "performTap: gesture at ($cx,$cy) ok=$ok")
                buildJsonObject {
                    put("performed", if (ok) "gesture_tap" else "failed")
                    put("success", ok)
                    put("x", cx.toInt())
                    put("y", cy.toInt())
                }
            }
        } catch (t: SecurityException) {
            Logger.w(TAG, "performTap denied: ${t.message}")
            errorResult("PERMISSION_DENIED", "tap not allowed: ${t.message}")
        }
    }

    /**
     * Tap at absolute screen coordinates. Used by `tap_element` after computing
     * the centre from the node bounds.
     */
    fun performTapAt(x: Float, y: Float): Boolean = dispatchTap(x, y)

    /**
     * Swipe in the given [direction]. Direction is one of
     * `up`, `down`, `left`, `right`.
     *
     * The swipe is a vertical/horizontal gesture that travels ~70% of the
     * screen dimension and takes ~350ms (fast enough to scroll a feed,
     * slow enough to register as a fling on most OEMs).
     */
    fun performSwipe(direction: String): JsonObject {
        val (startX, startY, endX, endY) = swipePath(direction)
        val ok = dispatchSwipe(startX, startY, endX, endY, SWIPE_DURATION_MS)
        return buildJsonObject {
            put("performed", "swipe_$direction")
            put("success", ok)
            put("from", buildJsonObject {
                put("x", startX.toInt()); put("y", startY.toInt())
            })
            put("to", buildJsonObject {
                put("x", endX.toInt()); put("y", endY.toInt())
            })
        }
    }

    /**
     * Press the system Back button.
     */
    fun performBack(): JsonObject {
        val ok = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (t: SecurityException) {
            Logger.w(TAG, "performBack denied: ${t.message}")
            false
        }
        return buildJsonObject {
            put("performed", "back")
            put("success", ok)
        }
    }

    // --------------------------------------------------------------------------
    // Gesture internals
    // --------------------------------------------------------------------------

    private fun dispatchTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Logger.w(TAG, "dispatchTap failed: $t")
            false
        }
    }

    private fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Logger.w(TAG, "dispatchSwipe failed: $t")
            false
        }
    }

    private fun swipePath(direction: String): FloatArray {
        val w = screenWidth().toFloat()
        val h = screenHeight().toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val spanX = w * 0.7f
        val spanY = h * 0.7f
        return when (direction.lowercase()) {
            "up"    -> floatArrayOf(cx, cy + spanY / 2, cx, cy - spanY / 2)
            "down"  -> floatArrayOf(cx, cy - spanY / 2, cx, cy + spanY / 2)
            "left"  -> floatArrayOf(cx + spanX / 2, cy, cx - spanX / 2, cy)
            "right" -> floatArrayOf(cx - spanX / 2, cy, cx + spanX / 2, cy)
            else    -> floatArrayOf(cx, cy + spanY / 2, cx, cy - spanY / 2) // default = up
        }
    }

    private fun screenWidth(): Int {
        return try {
            context.resources.displayMetrics.widthPixels
        } catch (_: Throwable) { 1080 }
    }

    private fun screenHeight(): Int {
        return try {
            context.resources.displayMetrics.heightPixels
        } catch (_: Throwable) { 2400 }
    }

    private fun errorResult(code: String, message: String): JsonObject = buildJsonObject {
        put("success", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    companion object {
        private const val TAG = "DroidPilotA11y"
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_DURATION_MS = 350L

        /**
         * The currently running accessibility service instance, or null if
         * the service is not enabled / not running.
         */
        @Volatile
        @JvmStatic
        var instance: DroidPilotAccessibilityService? = null
            private set
    }
}
