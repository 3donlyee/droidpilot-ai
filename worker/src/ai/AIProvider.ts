/**
 * DroidPilot AI — Provider-agnostic AI types.
 *
 * The `AIProvider` interface is the boundary between the AgentEngine and any
 * underlying LLM backend (Workers AI, OpenAI, Groq, Ollama, ...). Adding a new
 * provider = implementing this interface once.
 */

export type ChatRole = "system" | "user" | "assistant" | "tool";

export interface ToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string; // JSON string, per OpenAI convention
  };
}

export interface ChatMessage {
  role: ChatRole;
  content: string;
  /** Present on assistant messages that requested tool calls (OpenAI convention). */
  tool_calls?: ToolCall[];
  /** Present on `tool` role messages — the id of the tool_call this result answers. */
  tool_call_id?: string;
  /** Optional name for `tool` messages (the function name that produced this result). */
  name?: string;
}

export interface ToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    /** JSON Schema for the function arguments. */
    parameters: object;
  };
}

export type FinishReason = "stop" | "tool_calls" | "length";

export interface AIResponse {
  content: string | null;
  tool_calls?: ToolCall[];
  finish_reason: FinishReason;
  usage?: {
    prompt_tokens: number;
    completion_tokens: number;
  };
}

export interface ChatOptions {
  model: string;
  messages: ChatMessage[];
  tools?: ToolDefinition[];
  temperature?: number;
  maxTokens?: number;
}

export interface AIProvider {
  /** Provider name for logging. */
  readonly name: string;

  /** Run a single chat completion (non-streaming). */
  chat(opts: ChatOptions): Promise<AIResponse>;
}
