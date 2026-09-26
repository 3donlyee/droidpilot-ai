package com.droidpilot.ai.tools

import android.content.Context
import android.content.Intent
import com.droidpilot.ai.DroidPilotApp
import com.droidpilot.ai.device.DeviceManager
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App-management tools.
 *
 *   - [OpenAppTool]         : `open_app(package_name)` — launch via PackageManager
 *   - [GetDeviceInfoTool]   : `get_device_info` — model, OS, screen, battery
 */
class OpenAppTool : Tool {
    override val name: String = "open_app"
    override val description: String =
        "Launch the app identified by `package_name` (e.g. com.android.chrome)."

    override suspend fun execute(args: JsonObject): ToolResult {
        val pkg = (args["package_name"] as? JsonPrimitive)?.content
            ?: (args["package"] as? JsonPrimitive)?.content
            ?: return ToolResult.error(
                "INVALID_ARGUMENT",
                "missing `package_name` argument"
            )

        val ctx: Context = DroidPilotApp.get()
        return try {
            val pm = ctx.packageManager
            val launchIntent: Intent? = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                Logger.w(TAG, "no launch intent for $pkg")
                return ToolResult.error(
                    "ELEMENT_NOT_FOUND",
                    "no launchable activity for package '$pkg' (not installed or not launchable)"
                )
            }
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            ctx.startActivity(launchIntent)
            Logger.i(TAG, "open_app: launched $pkg")
            ToolResult.ok(buildJsonObject {
                put("package", pkg)
                put("launched", true)
            })
        } catch (t: SecurityException) {
            Logger.w(TAG, "open_app denied for $pkg: ${t.message}")
            ToolResult.error("PERMISSION_DENIED", "cannot launch $pkg: ${t.message}")
        } catch (t: Throwable) {
            Logger.e(TAG, "open_app failed for $pkg: $t")
            ToolResult.error("INTERNAL_ERROR", "open_app failed: ${t.message}")
        }
    }

    companion object { private const val TAG = "OpenApp" }
}

class GetDeviceInfoTool : Tool {
    override val name: String = "get_device_info"
    override val description: String =
        "Return device hardware + system info (model, OS, screen size, battery)."

    override suspend fun execute(args: JsonObject): ToolResult {
        val ctx: Context = DroidPilotApp.get()
        val deviceId = com.droidpilot.ai.Prefs.get().deviceId
        val info = DeviceManager.get(ctx).collect(deviceId = deviceId)
        // Convert DeviceInfo into a JsonObject the agent can read.
        return ToolResult.ok(buildJsonObject {
            put("device_id", info.deviceId)
            put("name", info.name)
            put("model", info.model)
            put("manufacturer", info.manufacturer)
            put("android_version", info.androidVersion)
            put("sdk_int", info.sdkInt)
            put("screen_density", info.screenDensity)
            put("screen_width", info.screenWidth)
            put("screen_height", info.screenHeight)
            put("battery_level", info.batteryLevel)
            put("is_charging", info.isCharging)
        })
    }
}
