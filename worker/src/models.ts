export interface AIModel {
  id: string;
  displayName: string;
  provider: 'cloudflare' | 'groq' | 'openai' | 'ollama' | 'local';
  supportsTools: boolean;
  supportsVision: boolean;
  supportsReasoning: boolean;
  maxTokens: number;
  enabled: boolean;
  description: string;
}

export const MODEL_REGISTRY: AIModel[] = [
  {
    id: "@cf/openai/gpt-oss-20b",
    displayName: "GPT-OSS 20B",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    supportsReasoning: true,
    maxTokens: 4096,
    enabled: true,
    description: "Cloudflare default reasoning model with full tool calling capabilities."
  },
  {
    id: "@cf/meta/llama-3.3-70b-instruct",
    displayName: "Llama 3.3 70B Instruct",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    supportsReasoning: true,
    maxTokens: 4096,
    enabled: true,
    description: "Meta high-intelligence instruction-tuned model with function calling."
  },
  {
    id: "@cf/meta/llama-3.1-8b-instruct",
    displayName: "Llama 3.1 8B Instruct",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    supportsReasoning: false,
    maxTokens: 2048,
    enabled: true,
    description: "Fast lightweight instruction model for low-latency tasks."
  },
  {
    id: "groq/llama-3.3-70b-versatile",
    displayName: "Groq Llama 3.3 70B (External)",
    provider: "groq",
    supportsTools: true,
    supportsVision: false,
    supportsReasoning: true,
    maxTokens: 8192,
    enabled: false,
    description: "Ultra-fast execution via Groq LPU API."
  }
];

export function getModelById(id: string): AIModel | undefined {
  return MODEL_REGISTRY.find(m => m.id === id && m.enabled) || MODEL_REGISTRY[0];
}

export function listAvailableModels(): AIModel[] {
  return MODEL_REGISTRY.filter(m => m.enabled);
}
