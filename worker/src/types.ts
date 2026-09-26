export type ChatMessage =
  | { role: "system"; content: string }
  | { role: "user"; content: string }
  | { role: "assistant"; content: string | null; tool_calls?: { id: string; name: string; args: any }[] }
  | { role: "tool"; tool_call_id: string; content: string };

export interface ToolSchema {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, any>;
  };
}

export interface ToolCall {
  id: string;
  name: string;
  args: any;
}

export interface AIChatResult {
  text: string | null;
  toolCalls: ToolCall[];
  style?: string;
  raw?: any;
}

export interface AIProvider {
  name: string;
  chat(modelId: string, messages: ChatMessage[], tools: ToolSchema[]): Promise<AIChatResult>;
}

export interface Command {
  type: "command";
  id: string;
  tool: string;
  arguments: any;
  approved?: boolean;
  issued_at: number;
}

export interface DeviceRecord {
  device_id: string;
  device_name?: string;
  model?: string;
  android_version?: string;
  app_version?: string;
  secret_hash: string;
  pairing_code?: string;
  pairing_expires?: number;
  status: "pending" | "active" | "revoked";
  created_at: number;
  last_seen?: number;
}

export interface DeviceResult {
  id: string;
  success: boolean;
  data?: any;
  error?: string | null;
  error_code?: string | null;
}

export type TurnState = "thinking" | "awaiting_device" | "done" | "error";

export interface TurnStep {
  ts: number;
  type: "user" | "ai" | "tool_call" | "tool_result" | "final" | "error" | "info";
  text?: string;
  tool?: string;
  cmd_id?: string;
  ok?: boolean;
  summary?: string;
  args?: any;
}

export interface Turn {
  id: string;
  device_id: string;
  model_id: string;
  state: TurnState;
  messages: ChatMessage[];
  steps: TurnStep[];
  tool_calls_used: number;
  pending_command?: Command | null;
  pending_since?: number | null;
  final_response?: string | null;
  error?: string | null;
  created_at: number;
  updated_at: number;
}
