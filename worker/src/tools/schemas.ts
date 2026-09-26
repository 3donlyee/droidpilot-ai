import type { ToolDefinition } from "../ai/AIProvider";

/**
 * Tool schemas exposed to the AI model.
 *
 * These names MUST match the tools implemented on the Android device side
 * (see android/.../tools/). The device-side `ToolRegistry` rejects commands
 * for unknown tools, so adding a new tool here requires adding it on the
 * device too.
 */
export const TOOL_SCHEMAS: ToolDefinition[] = [
  {
    type: "function",
    function: {
      name: "get_device_info",
      description:
        "Return device model, manufacturer, Android version, SDK int, screen size, and battery level.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "get_current_package",
      description: "Return the package name of the foreground app.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "get_screen_nodes",
      description:
        "Return the current accessibility tree as a compact JSON array of nodes. " +
        "Each node has a stable `id` (e.g. \"node_a1b2c3d4\"), `text`, `desc`, `bounds`, and `clickable`. " +
        "Use this BEFORE any tap to know the exact element_id to tap. PREFERRED over take_screenshot.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "open_app",
      description: "Launch an app by its Android package name (e.g. com.android.chrome).",
      parameters: {
        type: "object",
        properties: {
          package_name: {
            type: "string",
            description: "Android package name, e.g. com.android.chrome, com.zhiliaoapp.musically (TikTok)",
          },
        },
        required: ["package_name"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "swipe_up",
      description: "Swipe up (scroll content downward). Useful for scrolling feeds and lists.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "swipe_down",
      description: "Swipe down (scroll content upward, or pull-to-refresh).",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "tap_element",
      description:
        "Tap an accessibility node by its stable element_id (the `id` field returned by get_screen_nodes).",
      parameters: {
        type: "object",
        properties: {
          element_id: {
            type: "string",
            description: "Stable node id from get_screen_nodes, e.g. node_a1b2c3d4",
          },
        },
        required: ["element_id"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "press_back",
      description: "Press the Android system Back button.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "type_text",
      description: "Type text into the currently focused editable field (replaces any existing selection).",
      parameters: {
        type: "object",
        properties: {
          text: {
            type: "string",
            description: "Text to type into the focused field",
          },
        },
        required: ["text"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "take_screenshot",
      description:
        "Capture an on-demand screenshot. Returns a base64-encoded PNG. Use SPARINGLY — prefer get_screen_nodes. " +
        "Requires MediaProjection permission on the device; may return SCREENSHOT_REQUIRES_PERMISSION if not granted.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "run_shell",
      description:
        "Run a shell command on the device. Subject to SAFE / REQUIRES_CONFIRMATION / BLOCKED policy. " +
        "Blocked commands (rm, reboot, factory reset, etc.) are rejected before execution. " +
        "Safe commands include: getprop, dumpsys, pm list packages, pm path, input tap/swipe, etc.",
      parameters: {
        type: "object",
        properties: {
          command: {
            type: "string",
            description: "Shell command to execute, e.g. `pm list packages` or `getprop ro.product.model`",
          },
        },
        required: ["command"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "get_logs",
      description: "Return recent device logs from the on-device logger buffer.",
      parameters: {
        type: "object",
        properties: {
          limit: {
            type: "number",
            description: "Maximum number of log lines to return (default 50, max 500)",
          },
        },
        required: [],
      },
    },
  },
];
