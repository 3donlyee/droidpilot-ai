package com.droidpilot.ai.tools

import com.droidpilot.ai.util.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton registry of all [Tool] implementations available to the agent.
 *
 * The registry is populated lazily on first access (see [init]).
 * Lookups are O(1) via a [ConcurrentHashMap].
 */
object ToolRegistry {

    private val tools: MutableMap<String, Tool> = ConcurrentHashMap()

    @Volatile
    private var initialised = false

    /**
     * Initialise the registry if not already done. Called from [ToolExecutor]
     * before first execution.
     */
    fun ensureInitialised() {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            registerAll()
            initialised = true
            Logger.i(TAG, "ToolRegistry initialised with ${tools.size} tools")
        }
    }

    /** Look up a tool by name, returning null if unknown. */
    fun get(name: String): Tool? {
        ensureInitialised()
        return tools[name]
    }

    /** All registered tool names. */
    fun names(): List<String> {
        ensureInitialised()
        return tools.keys.toList()
    }

    /** Register an additional tool (for tests / future dynamic tool plug-ins). */
    @Synchronized
    fun register(tool: Tool) {
        tools[tool.name] = tool
        Logger.d(TAG, "registered tool: ${tool.name}")
    }

    // --------------------------------------------------------------------------
    // Boot population
    // --------------------------------------------------------------------------

    private fun registerAll() {
        // AccessibilityTools
        register(GetScreenNodesTool())
        register(GetCurrentPackageTool())
        register(TapElementTool())
        // GestureTools
        register(SwipeUpTool())
        register(SwipeDownTool())
        // AppTools
        register(OpenAppTool())
        register(GetDeviceInfoTool())
        // Type text
        register(TypeTextTool())
        // ShellTools
        register(RunShellTool())
        // ScreenshotTools
        register(TakeScreenshotTool())
    }

    private const val TAG = "ToolRegistry"
}
