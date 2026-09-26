package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import com.example.data.model.ToolCommand
import com.example.data.model.ToolResult
import com.example.data.repository.DroidPilotRepository
import com.example.service.DroidPilotAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ToolExecutor(
    private val context: Context,
    private val deviceManager: DeviceManager,
    private val adbBridge: AdbBridge,
    private val repository: DroidPilotRepository
) {

    suspend fun execute(command: ToolCommand): ToolResult = withContext(Dispatchers.IO) {
        val toolName = command.tool
        val args = command.arguments
        repository.log("ACTION", "TOOL", "Executing tool: $toolName with args: $args")

        try {
            when (toolName) {
                "get_device_info" -> {
                    val info = deviceManager.refreshDeviceInfo()
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = true,
                        data = info
                    )
                }

                "get_current_package" -> {
                    val pkg = DroidPilotAccessibilityService.currentForegroundPackage
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = true,
                        data = mapOf("package" to pkg)
                    )
                }

                "get_screen_nodes" -> {
                    val forceFull = args["force"] as? Boolean ?: false
                    val nodes = DroidPilotAccessibilityService.getNodes(forceFull)
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = true,
                        data = nodes
                    )
                }

                "open_app" -> {
                    val target = args["package_name"] as? String
                        ?: args["app"] as? String
                        ?: args["name"] as? String
                        ?: ""

                    val success = launchApp(target)
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("target" to target, "launched" to success),
                        error = if (!success) "Unable to launch app for target: '$target'" else null
                    )
                }

                "swipe_up" -> {
                    val success = DroidPilotAccessibilityService.swipeUp()
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("direction" to "UP"),
                        error = if (!success) "Accessibility service not ready or gesture failed" else null
                    )
                }

                "swipe_down" -> {
                    val success = DroidPilotAccessibilityService.swipeDown()
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("direction" to "DOWN"),
                        error = if (!success) "Accessibility service not ready or gesture failed" else null
                    )
                }

                "tap_element" -> {
                    val elementId = args["element_id"] as? String
                        ?: args["id"] as? String
                        ?: args["text"] as? String
                        ?: ""

                    val x = (args["x"] as? Number)?.toFloat()
                    val y = (args["y"] as? Number)?.toFloat()

                    val success = if (x != null && y != null) {
                        DroidPilotAccessibilityService.tapCoordinates(x, y)
                    } else if (elementId.isNotEmpty()) {
                        DroidPilotAccessibilityService.tapElement(elementId)
                    } else {
                        false
                    }

                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("target" to elementId, "tapped" to success),
                        error = if (!success) "ELEMENT_NOT_FOUND: could not tap '$elementId'" else null
                    )
                }

                "press_back" -> {
                    val success = DroidPilotAccessibilityService.pressBack()
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("action" to "BACK")
                    )
                }

                "type_text" -> {
                    val text = args["text"] as? String ?: ""
                    val success = DroidPilotAccessibilityService.typeText(text)
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = success,
                        data = mapOf("typed" to text),
                        error = if (!success) "Failed to type into active input field" else null
                    )
                }

                "take_screenshot" -> {
                    // Screenshot is secondary / on-demand only
                    val base64 = captureScreenshotBase64()
                    if (base64 != null) {
                        ToolResult(
                            id = command.id,
                            tool = toolName,
                            success = true,
                            data = mapOf("image_base64" to base64, "format" to "jpeg")
                        )
                    } else {
                        ToolResult(
                            id = command.id,
                            tool = toolName,
                            success = false,
                            error = "Screenshot unavailable: Requires Android 11+ and active AccessibilityService"
                        )
                    }
                }

                "get_logs" -> {
                    val logs = repository.recentLogs.first().take(50)
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = true,
                        data = logs
                    )
                }

                "run_shell" -> {
                    val shellCmd = args["command"] as? String ?: ""
                    val result = adbBridge.executeShell(shellCmd)
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = result.exitCode == 0,
                        data = mapOf(
                            "command" to result.command,
                            "policy" to result.policy.name,
                            "exitCode" to result.exitCode,
                            "output" to result.output
                        ),
                        error = result.error
                    )
                }

                else -> {
                    ToolResult(
                        id = command.id,
                        tool = toolName,
                        success = false,
                        error = "UNKNOWN_TOOL: Tool '$toolName' is not supported."
                    )
                }
            }
        } catch (e: Exception) {
            repository.log("ERROR", "TOOL", "Tool $toolName failed: ${e.message}")
            ToolResult(
                id = command.id,
                tool = toolName,
                success = false,
                error = e.message ?: "Execution failed"
            )
        }
    }

    private fun launchApp(target: String): Boolean {
        val pm = context.packageManager
        val cleanTarget = target.trim().lowercase()

        // Common package mappings
        val packageMap = mapOf(
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "chrome" to listOf("com.android.chrome"),
            "youtube" to listOf("com.google.android.youtube"),
            "settings" to listOf("com.android.settings")
        )

        // Try exact package first
        var launchIntent = pm.getLaunchIntentForPackage(target)

        // Try map
        if (launchIntent == null && packageMap.containsKey(cleanTarget)) {
            for (pkg in packageMap[cleanTarget]!!) {
                launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) break
            }
        }

        // Try partial match in installed packages
        if (launchIntent == null) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                if (app.packageName.lowercase().contains(cleanTarget)) {
                    launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) break
                }
            }
        }

        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }

    private suspend fun captureScreenshotBase64(): String? {
        return suspendCancellableCoroutine { cont ->
            DroidPilotAccessibilityService.takeScreenshot { bmp ->
                if (bmp != null) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    bmp.recycle()
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    cont.resume(b64)
                } else {
                    cont.resume(null)
                }
            }
        }
    }
}
