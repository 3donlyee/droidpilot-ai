package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.db.AppDatabase
import com.example.data.model.ScreenNodesResult
import com.example.data.repository.DroidPilotRepository
import com.example.engine.ScreenNodeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DroidPilotAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var repository: DroidPilotRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.getInstance(this)
        repository = DroidPilotRepository(db)
        log("INFO", "AccessibilityService initialized")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        log("INFO", "AccessibilityService connected and listening")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty() && pkg != currentForegroundPackage && !pkg.contains("android.inputmethod")) {
            currentForegroundPackage = pkg
            log("ACTION", "Foreground package changed to: $pkg")
        }
    }

    override fun onInterrupt() {
        log("WARN", "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
    }

    private fun log(level: String, message: String) {
        serviceScope.launch {
            try {
                if (::repository.isInitialized) {
                    repository.log(level, "ACCESSIBILITY", message)
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        @Volatile
        var instance: DroidPilotAccessibilityService? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentForegroundPackage: String = "com.example"

        fun isServiceRunning(): Boolean = instance != null && isRunning

        fun getNodes(forceFull: Boolean = false): ScreenNodesResult {
            val s = instance ?: return ScreenNodesResult(
                packageName = currentForegroundPackage,
                elementCount = 0,
                elements = emptyList(),
                hash = "service_not_running"
            )
            val root = s.rootInActiveWindow
            val result = ScreenNodeParser.parseTree(root, currentForegroundPackage, forceFull)
            root?.recycle()
            return result
        }

        fun tapCoordinates(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false
            s.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            try {
                latch.await(1000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            return success
        }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val s = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false
            s.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            try {
                latch.await(durationMs + 500, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            return success
        }

        fun swipeUp(): Boolean {
            val s = instance ?: return false
            val metrics = s.resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.78f
            val endY = metrics.heightPixels * 0.22f
            return swipe(cx, startY, cx, endY, 280)
        }

        fun swipeDown(): Boolean {
            val s = instance ?: return false
            val metrics = s.resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.22f
            val endY = metrics.heightPixels * 0.78f
            return swipe(cx, startY, cx, endY, 280)
        }

        fun tapElement(identifier: String): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false

            // Try finding by text or description or view ID
            val targetNode = findNode(root, identifier)
            if (targetNode != null) {
                val rect = Rect()
                targetNode.getBoundsInScreen(rect)
                val performed = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.recycle()
                root.recycle()
                if (performed) return true

                // Fallback to tap at node bounds center
                return tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }

            root.recycle()
            return false
        }

        private fun findNode(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
            if (node == null) return null
            val viewId = node.viewIdResourceName
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if ((viewId != null && viewId.contains(target, ignoreCase = true)) ||
                (text != null && text.contains(target, ignoreCase = true)) ||
                (desc != null && desc.contains(target, ignoreCase = true))
            ) {
                return AccessibilityNodeInfo.obtain(node)
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                val found = findNode(child, target)
                child?.recycle()
                if (found != null) return found
            }
            return null
        }

        fun typeText(text: String): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val target = focused ?: findFirstEditableNode(root)

            val success = if (target != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val res = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                target.recycle()
                res
            } else {
                false
            }
            root.recycle()
            return success
        }

        private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findFirstEditableNode(child)
                child?.recycle()
                if (found != null) return found
            }
            return null
        }

        fun pressBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun pressHome(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        }

        fun pressRecents(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        }

        fun takeScreenshot(callback: (Bitmap?) -> Unit) {
            val s = instance
            if (s == null) {
                callback(null)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                s.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    s.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshotResult.hardwareBuffer.close()
                            callback(copy)
                        }

                        override fun onFailure(errorCode: Int) {
                            callback(null)
                        }
                    }
                )
            } else {
                callback(null)
            }
        }
    }
}
