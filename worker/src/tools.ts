import type { ToolSchema } from "./types";

function f(
  name: string,
  description: string,
  properties: Record<string, any>,
  required: string[] = []
): ToolSchema {
  return {
    type: "function",
    function: { name, description, parameters: { type: "object", properties, required } },
  };
}

/**
 * TOOL REGISTRY — the exact schemas advertised to the AI model.
 * The Android side must implement every tool listed here (ToolExecutor.kt).
 */
export const TOOL_SCHEMAS: ToolSchema[] = [
  f("get_device_info", "Get basic info about the Android device: model, Android version, battery, screen, accessibility status.", {}),
  f("get_current_package", "Get the package name of the app currently in the foreground. Use after open_app to verify.", {}),
  f(
    "get_screen_nodes",
    "Read the accessibility tree of the current screen. Returns elements with id, text, description, class, clickable, enabled, bounds. Call this BEFORE tapping anything.",
    { max_nodes: { type: "integer", description: "Max elements to return (default 250)" }, clickable_only: { type: "boolean", description: "Return only clickable elements" } }
  ),
  f("open_app", "Open an app by package name (e.g. com.zhiliaoapp.musically) or a well-known name like 'TikTok'.", { package_name: { type: "string" } }, ["package_name"]),
  f("swipe_up", "Swipe up — scrolls content down / advances to the next TikTok video.", { duration_ms: { type: "integer" } }),
  f("swipe_down", "Swipe down — scrolls content up.", { duration_ms: { type: "integer" } }),
  f("tap_element", "Tap an element returned by get_screen_nodes, identified by its element id (e.g. node_012).", { element_id: { type: "string" } }, ["element_id"]),
  f("press_back", "Press the Android BACK button.", {}),
  f("type_text", "Type text into the currently focused input field.", { text: { type: "string" } }, ["text"]),
  f("take_screenshot", "Capture a screenshot. LAST RESORT ONLY — prefer get_screen_nodes. Only call when accessibility data is insufficient.", {}),
  f("get_logs", "Read recent on-device DroidPilot logs (for debugging).", { limit: { type: "integer" } }),
  f(
    "run_shell",
    "Run a shell command on the device. Subject to strict policy: SAFE commands run, risky ones require prior explicit user approval (approved=true), destructive ones are always blocked. Most shell commands are unavailable without ADB.",
    {
      command: { type: "string" },
      approved: { type: "boolean", description: "Set true ONLY after the user explicitly approved this exact command in chat" },
    },
    ["command"]
  ),
  // --- Aiminos memory tools (execute SERVER-SIDE — never dispatched to the device) ---
  f("memory_save", "Save a durable fact about the user into Aiminos long-term memory (T3). Use whenever the user says 'تذكر/احفظ' or shares a lasting preference.", { key: { type: "string", description: "short fact name, e.g. 'حساب_tiktok' or 'لغة_مفضلة'" }, value: { type: "string", description: "the fact itself" } }, ["key", "value"]),
  f("memory_list", "List everything currently stored in Aiminos long-term memory for this device.", {}),
  f("memory_forget", "Delete one fact from long-term memory by its key.", { key: { type: "string" } }, ["key"]),
];
