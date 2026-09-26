/**
 * Model definition + provider union used by the Model Registry.
 */
export type ModelProvider = "cloudflare" | "openai" | "groq" | "ollama" | "local";

export interface ModelDefinition {
  /** Model id as passed to the provider (e.g. `@cf/openai/gpt-oss-20b`). */
  id: string;

  /** Human-readable name for the Web UI selector. */
  displayName: string;

  /** Which provider backend serves this model. Determines which AIProvider is used. */
  provider: ModelProvider;

  /** True if the provider supports OpenAI-style tool/function calling. */
  supportsTools: boolean;

  /** True if the model accepts image/vision inputs. */
  supportsVision: boolean;

  /** Whether the model is selectable in the Web UI. Disabled models are still listed in code for reference. */
  enabled: boolean;

  /** Maximum input+output tokens the model accepts. */
  contextWindow: number;

  /** Optional short description shown in the UI. */
  description?: string;
}
