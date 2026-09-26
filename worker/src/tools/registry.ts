import { TOOL_SCHEMAS } from "./schemas";
import type { ToolDefinition } from "../ai/AIProvider";

/**
 * Lookup map for tools by name. Used to validate incoming AI tool calls
 * before they are routed to the device.
 */
export const TOOL_REGISTRY = new Map<string, ToolDefinition>(
  TOOL_SCHEMAS.map((t) => [t.function.name, t] as const),
);

/** Return all known tool names (e.g. for diagnostics / listings). */
export function getToolNames(): string[] {
  return Array.from(TOOL_REGISTRY.keys());
}

/** True if `name` is a recognized tool. */
export function isKnownTool(name: string): boolean {
  return TOOL_REGISTRY.has(name);
}
