package ai.droidpilot.app.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import ai.droidpilot.app.access.DroidPilotAccessibilityService
import ai.droidpilot.app.access.ScreenTree
import ai.droidpilot.app.core.Command
import ai.droidpilot.app.core.LogSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Executes tool commands on the device and returns JSON envelopes:
 *   { id, type:"result", success, data?, error?, error_code? }
 *
 * Priority order enforced by design and by the agent prompt:
 *   Accessibility → Android APIs → Gesture APIs → Screenshot/OCR (last resort)
 */
class ToolExecutor(private val context: Context) {

    private val adb = AdbBridge(context)

    /** Node map from the most recent snapshot — ids match what the AI saw. */
    @Volatile
    private var lastNodeMap: Map<String, AccessibilityNodeInfo> = emptyMap()

    fun execute(cmd: Command): JSONObject {
        LogSystem.log("tool", "${cmd.tool} ${cmd.arguments}")
        val result = try {
            dispatch(cmd)
        } catch (t: Throwable) {
            fail(cmd.id, "EXECUTION_FAILED", t.message ?: t.javaClass.simpleName)
        }
        val brief = if (result.optBoolean("success")) "ok" else result.optString("error_code")
        LogSystem.log("tool", "-> $brief")
        return result
    }

    private fun dispatch(cmd: Command): JSONObject = when (cmd.tool) {
        "get_device_info" -> deviceInfo(cmd.id)
        "get_current_package" -> currentPackage(cmd.id)
        "get_screen_nodes" -> screenNodes(cmd.id, cmd.arguments)
        "open_app" -> openApp(cmd.id, cmd.arguments)
        "swipe_up" -> swipe(cmd.id, up = true, cmd.arguments)
        "swipe_down" -> swipe(cmd.id, up = false, cmd.arguments)
        "tap_element" -> tapElement(cmd.id, cmd.arguments)
        "press_back" -> pressBack(cmd.id)
        "type_text" -> typeText(cmd.id, cmd.arguments)
        "take_screenshot" -> screenshot(cmd.id)
        "get_logs" -> getLogs(cmd.id, cmd.arguments)
        "run_shell" -> runShell(cmd.id, cmd.arguments)
        else -> fail(cmd.id, "UNKNOWN_TOOL", cmd.tool)
    }

    // ---------------------------------------------------------------- helpers

    private fun ok(id: String, data: JSONObject? = null): JSONObject {
        val o = JSONObject().put("success", true)
        data?.let { o.put("data", it) }
        return o
    }

    private fun fail(id: String, code: String, message: String? = null): JSONObject {
        val o = JSONObject().put("success", false).put("error_code", code)
        message?.let { o.put("error", it.take(300)) }
        return o
    }

    private fun svc(id: String): DroidPilotAccessibilityService? =
        DroidPilotAccessibilityService.instance ?: null.also {
            LogSystem.log("tool", "accessibility service not connected")
        }

    private fun notConnected(id: String) =
        fail(id, "SERVICE_NOT_CONNECTED",
            "Accessibility service is enabled but not registered yet — toggle it OFF and ON once, then reopen TikTok")

    // ----------------------------------------------------------------- tools

    private fun deviceInfo(id: String): JSONObject {
        val w = context.resources.displayMetrics
        val batteryPct = try {
            val i = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (_: Exception) { -1 }

        val data = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("battery_pct", batteryPct)
            .put("screen", "${w.widthPixels}x${w.heightPixels}")
            .put("accessibility_connected", DroidPilotAccessibilityService.instance != null)
            .put("adb_wireless_enabled", adb.wirelessDebuggingEnabled())
        return ok(id, data)
    }

    private fun currentPackage(id: String): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        // Retry briefly: right after open_app the new window may not be active yet.
        val root = s.waitForRoot(2500)
            ?: return fail(id, "NO_WINDOW", "No active window available")
        val pkg = root.packageName?.toString()
            ?: return fail(id, "NO_WINDOW", "Active window has no package")
        return ok(id, JSONObject().put("package", pkg))
    }

    private fun screenNodes(id: String, args: JSONObject): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        // Retry briefly — handles the window transition right after open_app.
        val root = s.waitForRoot(2500)
            ?: return fail(id, "NO_WINDOW", "No active window available")

        val maxNodes = args.optInt("max_nodes", 250).coerceIn(10, 400)
        val clickableOnly = args.optBoolean("clickable_only", false)
        val snap = ScreenTree.snapshot(root, maxNodes)
        lastNodeMap = snap.nodeMap

        val changed = snap.hash != DroidPilotAccessibilityService.lastTreeHash
        DroidPilotAccessibilityService.lastTreeHash = snap.hash

        if (!changed) {
            // Dedupe: identical tree → don't resend the full payload.
            return ok(id, JSONObject()
                .put("unchanged", true)
                .put("hash", snap.hash)
                .put("node_count", snap.elements.length()))
        }

        val elements: JSONArray = if (clickableOnly) {
            val filtered = JSONArray()
            for (i in 0 until snap.elements.length()) {
                val e = snap.elements.getJSONObject(i)
                if (e.optBoolean("clickable")) filtered.put(e)
            }
            filtered
        } else snap.elements

        return ok(id, JSONObject()
            .put("package", snap.packageName)
            .put("node_count", snap.elements.length())
            .put("hash", snap.hash)
            .put("elements", elements))
    }

    private fun openApp(id: String, args: JSONObject): JSONObject {
        val target = args.optString("package_name").trim()
        if (target.isEmpty()) return fail(id, "INVALID_ARGUMENT", "package_name required")
        val pm = context.packageManager

        // 1) exact package
        pm.getLaunchIntentForPackage(target)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Thread.sleep(600)
            return ok(id, JSONObject().put("package", target).put("resolved", "exact"))
        }

        // 2) fuzzy match on app label / package substring
        for (app in pm.getInstalledApplications(0)) {
            val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { continue }
            if (label.equals(target, ignoreCase = true) ||
                label.contains(target, ignoreCase = true) ||
                app.packageName.contains(target, ignoreCase = true)
            ) {
                pm.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Thread.sleep(600)
                    return ok(id, JSONObject()
                        .put("package", app.packageName)
                        .put("resolved", "label:$label"))
                }
            }
        }
        return fail(id, "PACKAGE_NOT_FOUND", target)
    }

    private fun swipe(id: String, up: Boolean, args: JSONObject): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        val (w, h) = s.screenSize()
        val duration = args.optInt("duration_ms", 350).coerceIn(120, 1200).toLong()
        val cx = w / 2f
        val y1 = if (up) h * 0.75f else h * 0.30f
        val y2 = if (up) h * 0.30f else h * 0.75f
        val sent = s.gestureSwipe(cx, y1, cx, y2, duration)
        return if (sent) ok(id, JSONObject().put("direction", if (up) "up" else "down"))
        else fail(id, "GESTURE_FAILED")
    }

    private fun tapElement(id: String, args: JSONObject): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        val elementId = args.optString("element_id").trim()
        if (elementId.isEmpty()) return fail(id, "INVALID_ARGUMENT", "element_id required")

        // Prefer a node reference from the snapshot the AI actually saw.
        var node: AccessibilityNodeInfo? = lastNodeMap[elementId]
        if (node == null) {
            // Fall back to a fresh snapshot with the same traversal order.
            val root = s.rootInActiveWindow ?: return fail(id, "NO_WINDOW")
            lastNodeMap = ScreenTree.snapshot(root).nodeMap
            node = lastNodeMap[elementId]
        }
        node ?: return fail(id, "ELEMENT_NOT_FOUND", elementId)

        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ok(id, JSONObject().put("tapped", "click_action").put("element_id", elementId))
            }
            // Walk up for a clickable ancestor.
            var parent: AccessibilityNodeInfo? = node.parent
            var hops = 0
            while (parent != null && hops < 4) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return ok(id, JSONObject().put("tapped", "ancestor_action").put("element_id", elementId))
                }
                parent = parent.parent
                hops++
            }
            // Final fallback: gesture tap at the element's center.
            val b = s.boundsOf(node)
            val tapped = s.gestureTap(b.exactCenterX(), b.exactCenterY())
            return when {
                tapped -> ok(id, JSONObject().put("tapped", "gesture").put("element_id", elementId))
                adb.isAvailable() -> adbFallback(id, "input tap ${b.exactCenterX().toInt()} ${b.exactCenterY().toInt()}")
                else -> fail(id, "TAP_FAILED", elementId)
            }
        } catch (e: Exception) {
            return fail(id, "ELEMENT_NOT_FOUND", "node went stale: ${e.message}")
        }
    }

    private fun pressBack(id: String): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        return if (s.pressBack()) ok(id) else fail(id, "BACK_FAILED")
    }

    private fun typeText(id: String, args: JSONObject): JSONObject {
        val s = svc(id) ?: return notConnected(id)
        val text = args.optString("text")
        if (text.isEmpty()) return fail(id, "INVALID_ARGUMENT", "text required")

        val root = s.rootInActiveWindow
            ?: return fail(id, "NO_WINDOW", "No active window available")
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail(id, "NO_TEXT_INPUT", "No focused editable field")

        if (!focus.isEditable) return fail(id, "NO_TEXT_INPUT", "Focused node is not editable")

        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            ok(id, JSONObject().put("typed", text.take(50)))
        } else if (adb.isAvailable() && text.all { it.code in 32..126 }) {
            // ADB input text only supports ASCII — spaces must be %s.
            adbFallback(id, "input text " + text.replace(" ", "%s").replace("\"", "\\\""))
        } else {
            fail(id, "TYPE_FAILED")
        }
    }

    /** Deep-control fallback: run the command through the ADB bridge (opt-in). */
    private fun adbFallback(id: String, command: String): JSONObject {
        return try {
            adb.exec(command)
            ok(id, JSONObject().put("via", "adb").put("cmd", command.take(60)))
        } catch (t: Throwable) {
            LogSystem.log("adb", "fallback failed: ${t.message}")
            fail(id, "ADB_FALLBACK_FAILED", t.message?.take(120))
        }
    }

    private fun screenshot(id: String): JSONObject {
        // Policy: screenshot is the LAST resort — only taken when this tool is
        // explicitly called. Captured → analyzed → discarded (never persisted).
        val s = svc(id) ?: return notConnected(id)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fail(id, "UNSUPPORTED_API", "Screenshot needs Android 11+")
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    val hw = screenshot.hardwareBuffer
                    val bm: Bitmap? = if (hw != null) {
                        val wrapped = Bitmap.wrapHardwareBuffer(
                            hw, ColorSpace.get(ColorSpace.Named.SRGB)
                        )
                        val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) hw.close()
                        soft
                    } else null
                    deferred.complete(bm)
                } catch (_: Throwable) {
                    deferred.complete(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                deferred.complete(null)
            }
        }
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (t: Throwable) {
            return fail(id, "SCREENSHOT_UNSUPPORTED", t.message)
        }

        val bitmap = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(4000) { deferred.await() }
        } ?: return fail(id, "SCREENSHOT_TIMEOUT")

        // Downscale to width ≤ 720 and compress to JPEG q60 to keep payloads small.
        val scale = min(1f, 720f / bitmap.width)
        val out = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
        val bytes = ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.JPEG, 60, bos)
            bos.toByteArray()
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val w = out.width; val h = out.height
        out.recycle(); bitmap.recycle()

        return ok(id, JSONObject()
            .put("image_base64", b64)
            .put("mime", "image/jpeg")
            .put("width", w)
            .put("height", h)
            .put("note", "on-demand capture; discarded after analysis"))
    }

    private fun getLogs(id: String, args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 100).coerceIn(10, 500)
        val arr = JSONArray()
        for (line in LogSystem.dump(limit)) arr.put(line)
        return ok(id, JSONObject().put("lines", arr))
    }

    private fun runShell(id: String, args: JSONObject): JSONObject {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return fail(id, "INVALID_ARGUMENT", "command required")
        val approved = args.optBoolean("approved", false)

        return when (CommandValidator.classify(command)) {
            CommandValidator.Policy.BLOCKED ->
                fail(id, "BLOCKED_COMMAND", "Policy forbids this command")

            CommandValidator.Policy.REQUIRES_CONFIRMATION ->
                if (!approved) fail(id, "CONFIRMATION_REQUIRED", "User must approve this command in the Web UI")
                else shellExec(id, command)

            CommandValidator.Policy.SAFE -> shellExec(id, command)
        }
    }

    private fun shellExec(id: String, command: String): JSONObject {
        // Prefer the ADB bridge when present (not available in MVP).
        if (adb.isAvailable()) {
            return try {
                val (code, out) = adb.exec(command)
                ok(id, JSONObject().put("exit_code", code).put("stdout", out.take(4000)))
            } catch (t: Throwable) {
                fail(id, "EXECUTION_FAILED", t.message)
            }
        }
        // App-level execution: works only for commands a regular app may run
        // (e.g. getprop). Anything requiring shell/system privileges fails honestly.
        return try {
            val p = ProcessBuilder("sh", "-c", command).start()
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return fail(id, "EXECUTION_TIMEOUT")
            }
            val stdout = p.inputStream.bufferedReader().readText()
            val stderr = p.errorStream.bufferedReader().readText()
            val code = p.exitValue()
            if (code == 0) ok(id, JSONObject().put("exit_code", 0).put("stdout", stdout.take(4000)))
            else fail(id, "EXECUTION_FAILED", "exit=$code ${stderr.take(300)}${stdout.take(200)}")
        } catch (t: Throwable) {
            fail(id, "SHELL_UNAVAILABLE", "No shell privileges without ADB/root: ${t.message?.take(120)}")
        }
    }
}
