package com.droidpilot.ai.tools

import com.droidpilot.ai.DroidPilotAccessibilityService
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DroidPilot AI — Screenshot tool.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  MVP LIMITATION (Phase 1)
 *
 *  Android requires user consent (MediaProjection system dialog) before any
 *  app can capture the screen. Asking for this consent requires a foreground
 *  Activity (we'd need to launch a transparent Activity to invoke
 *  `MediaProjectionManager.createScreenCaptureIntent()`).
 *
 *  To keep Phase 1 self-contained (no extra Activity), this tool:
 *
 *    1) If the AccessibilityService is active and an editable / focusable
 *       window is available, attempts to use the PixelCopy API on the
 *       root window of the active app — *this only works for windows owned
 *       by the calling process*, which the foreground app is NOT. So this
 *       path will almost always return the same error as below.
 *
 *    2) Otherwise returns:
 *
 *           { success:false, error:{ code:"SCREENSHOT_REQUIRES_PERMISSION",
 *                                   message:"MediaProjection consent is required." } }
 *
 *  Phase 2+ will add a transparent consent Activity that requests the
 *  MediaProjection token once, caches the resulting `Intent` in the
 *  ForegroundService, and uses `VirtualDisplay` + `ImageReader` for
 *  on-demand captures.
 * ─────────────────────────────────────────────────────────────────────────
 */
class TakeScreenshotTool : Tool {

    override val name: String = "take_screenshot"
    override val description: String =
        "Capture the current screen as a base64 PNG. Requires one-time " +
            "MediaProjection consent (Phase 2). In MVP, returns " +
            "SCREENSHOT_REQUIRES_PERMISSION unless a PixelCopy-compatible " +
            "window is available."

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = DroidPilotAccessibilityService.instance
        if (service == null) {
            Logger.w(TAG, "take_screenshot: AccessibilityService not running")
            return ToolResult.error(
                "SCREENSHOT_REQUIRES_PERMISSION",
                "AccessibilityService is not running. Enable it first; " +
                    "or grant MediaProjection consent (Phase 2)."
            )
        }

        // -------------------------------------------------------------------
        // Attempt 1: PixelCopy on the service's window.
        //
        // PixelCopy only works for windows of the calling process; an
        // AccessibilityService runs in the host app process, so PixelCopy of
        // a *different* app's window will fail with PERMISSION_DENIED.
        // We try anyway and fall back to the error below.
        // -------------------------------------------------------------------
        val pixelCopySupported = false   // see note above — disabled in MVP
        if (pixelCopySupported) {
            // TODO(Phase 2): wire up android.view.PixelCopy against a target
            // Surface (from MediaProjection). Left as documented hook.
        }

        // -------------------------------------------------------------------
        // Final fallback: tell the caller to grant MediaProjection.
        // -------------------------------------------------------------------
        Logger.i(TAG, "take_screenshot: returning SCREENSHOT_REQUIRES_PERMISSION")
        return ToolResult.error(
            "SCREENSHOT_REQUIRES_PERMISSION",
            "Screenshot capture requires MediaProjection consent, which needs a " +
                "foreground Activity. Phase 2 will add a transparent consent flow."
        )
    }

    companion object { private const val TAG = "Screenshot" }
}
