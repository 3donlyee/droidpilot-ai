export interface ToolDefinition {
  name: string;
  description: string;
  parameters: {
    type: 'object';
    properties: Record<string, any>;
    required?: string[];
  };
}

export const ANDROID_TOOLS: ToolDefinition[] = [
  {
    name: "get_device_info",
    description: "Get real hardware telemetry, model, manufacturer, battery, and accessibility status of the connected Android device.",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "get_current_package",
    description: "Get the application package currently active in the foreground on the phone (e.g. TikTok, Chrome).",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "get_screen_nodes",
    description: "Inspect active screen UI elements hierarchy via Android AccessibilityService, returning elements with id, text, bounds, and clickability.",
    parameters: {
      type: "object",
      properties: {
        force: {
          type: "boolean",
          description: "Force full tree refresh bypassing cache/diff"
        }
      }
    }
  },
  {
    name: "open_app",
    description: "Launch an Android app by package name or alias (e.g. 'TikTok', 'Chrome', 'Settings', or 'com.zhiliaoapp.musically').",
    parameters: {
      type: "object",
      properties: {
        package_name: {
          type: "string",
          description: "App package name or friendly name (e.g. TikTok, com.zhiliaoapp.musically)"
        }
      },
      required: ["package_name"]
    }
  },
  {
    name: "swipe_up",
    description: "Execute a smooth upward swipe gesture (scrolling to next video or page on TikTok/reels/feed).",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "swipe_down",
    description: "Execute a downward swipe gesture (scrolling backwards or refreshing feed).",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "tap_element",
    description: "Click an on-screen UI node by its id, label, or exact screen coordinates (x, y).",
    parameters: {
      type: "object",
      properties: {
        element_id: {
          type: "string",
          description: "The node ID or visible text label (e.g. 'Like', 'node_001')"
        },
        x: {
          type: "number",
          description: "Optional X coordinate"
        },
        y: {
          type: "number",
          description: "Optional Y coordinate"
        }
      }
    }
  },
  {
    name: "press_back",
    description: "Simulate pressing the system Back button.",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "type_text",
    description: "Type text into the currently active or focused input field.",
    parameters: {
      type: "object",
      properties: {
        text: {
          type: "string",
          description: "The text string to type"
        }
      },
      required: ["text"]
    }
  },
  {
    name: "take_screenshot",
    description: "Capture screen pixels as base64 JPEG. Note: Use only on demand or when accessibility inspection fails.",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "get_logs",
    description: "Retrieve recent execution logs and events from the phone.",
    parameters: {
      type: "object",
      properties: {}
    }
  },
  {
    name: "run_shell",
    description: "Execute a safe shell command on the Android device subject to Command Policy (SAFE: getprop, pm list, input).",
    parameters: {
      type: "object",
      properties: {
        command: {
          type: "string",
          description: "Shell command string to execute"
        }
      },
      required: ["command"]
    }
  }
];
