package ai.droidpilot.app.access

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        // Accessibility button → Aiminos Voice Panel (🎤 talk / ⏹ stop / ▶ resume).
        // Registered through AccessibilityButtonController — the documented API for
        // handling accessibility-button clicks (flagRequestAccessibilityButton).
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        VoicePanel.toggle(this@DroidPilotAccessibilityService)
                    }

                    override fun onAvailabilityChanged(
                        controller: AccessibilityButtonController,
                        available: Boolean
                    ) {}
                }
            )
        } catch (_: Exception) {
            LogSystem.log("a11y", "accessibility button callback unavailable on this device")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastPackage) {
            lastPackage = pkg
            // Foreground app changed → previous tree snapshot is no longer valid.
            lastTreeHash = null
        }
        // aMiNo: while the ADB pairing service waits for a port, scan the
        // Settings "Pair device with pairing code" dialog for its IP:port.
        if (adbPairWatching && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString() ?: ""
            if (p == "com.android.settings" || p == "com.android.systemui") {
                scanForPairingEndpoint()
            }
        }
    }

    // ---- aMiNo ADB pairing watcher (zero cost unless the pairing service is live) ----

    @Volatile
    private var adbPairWatching: Boolean = false

    private var lastEndpointScan: Long = 0

    private val endpointRegex = Regex("\\b(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{3,5})\\b")

    private fun scanForPairingEndpoint() {
        val now = System.currentTimeMillis()
        if (now - lastEndpointScan < 500) return
        lastEndpointScan = now
        try {
            val listener = ai.droidpilot.app.adb.AdbPairingService.endpointListener ?: run {
                adbPairWatching = false
                return
            }
            val windows: List<AccessibilityWindowInfo> =
                if (Build.VERSION.SDK_INT >= 21) this.windows else emptyList()
            for (w in windows) {
                val root = w.root ?: continue
                val found = findEndpointText(root) ?: continue
                listener(found)
                return
            }
        } catch (_: Throwable) {
        }
    }

    private fun findEndpointText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val t = node.text?.toString()
        if (t != null) {
            val m = endpointRegex.find(t)
            if (m != null) return m.value
        }
        for (i in 0 until node.childCount) {
            val found = findEndpointText(node.getChild(i)) ?: continue
            return found
        }
        return null
    }

    /** Called by AdbPairingService to arm/disarm the cheap dialog watcher. */
    fun setAdbPairWatching(enabled: Boolean) {
        adbPairWatching = enabled
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Clear on unbind too (rebind cycles call onServiceConnected again).
        instance = null
        LogSystem.log("a11y", "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
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
