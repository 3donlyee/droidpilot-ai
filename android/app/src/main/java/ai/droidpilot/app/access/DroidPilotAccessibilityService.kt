package ai.droidpilot.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.droidpilot.app.core.LogSystem

/**
 * The eyes and hands of DroidPilot.
 *
 * - Detects the foreground app
 * - Reads the accessibility tree (text, contentDescription, className,
 *   clickable, enabled, bounds, actions)
 * - Performs taps / swipes through gesture APIs
 * - Handles BACK through global actions
 */
class DroidPilotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DroidPilotAccessibilityService? = null
            private set

        /** Hash of the last snapshot — used to dedupe unchanged trees. */
        @Volatile
        var lastTreeHash: String? = null
    }

    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // CRITICAL FIX: register the bound instance so ToolExecutor can reach it.
        // Without this, instance stays null forever and every a11y-dependent
        // tool fails with SERVICE_NOT_CONNECTED even when the service is
        // genuinely enabled on the device.
        instance = this
        LogSystem.log("a11y", "accessibility service connected — instance registered")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastPackage) {
            lastPackage = pkg
            // Foreground app changed → previous tree snapshot is no longer valid.
            lastTreeHash = null
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Clear on unbind too (rebind cycles call onServiceConnected again).
        instance = null
        LogSystem.log("a11y", "accessibility service unbound")
        return super.onUnbind(intent)
    }

    /**
     * Waits up to [timeoutMs] for an active window root — window transitions
     * (e.g. right after open_app) leave rootInActiveWindow null briefly.
     */
    fun waitForRoot(timeoutMs: Long = 2500L): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var root = rootInActiveWindow
        while (root == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            root = rootInActiveWindow
        }
        return root
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Foreground package (best-effort, from the active window root). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun gestureSwipe(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun centerOfScreen(): Pair<Float, Float> {
        val m = resources.displayMetrics
        return m.widthPixels / 2f to m.heightPixels / 2f
    }

    /** Screen size in pixels (for gestures). */
    fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }
}
