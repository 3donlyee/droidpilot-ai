import { AIModel } from './models';
import { ANDROID_TOOLS, ToolDefinition } from './tools';

export interface AIMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  name?: string;
  tool_call_id?: string;
  tool_calls?: Array<{
    id: string;
    type: 'function';
    function: {
      name: string;
      arguments: string;
    };
  }>;
}

export interface AIResponse {
  reply: string;
  toolCalls?: Array<{
    id: string;
    name: string;
    arguments: Record<string, any>;
  }>;
}

export interface AIProvider {
  chat(
    model: AIModel,
    messages: AIMessage[],
    tools: ToolDefinition[],
    env: any
  ): Promise<AIResponse>;
}

export class CloudflareAIProvider implements AIProvider {
  async chat(
    model: AIModel,
    messages: AIMessage[],
    tools: ToolDefinition[],
    env: any
  ): Promise<AIResponse> {
    if (!env.AI) {
      throw new Error("Cloudflare Workers AI binding 'env.AI' is not configured.");
    }

    try {
      const formattedTools = tools.map(t => ({
        type: "function",
        function: {
          name: t.name,
          description: t.description,
          parameters: t.parameters
        }
      }));

      const response: any = await env.AI.run(model.id as any, {
        messages: messages,
        tools: formattedTools,
        max_tokens: model.maxTokens
      });

      if (response?.tool_calls && response.tool_calls.length > 0) {
        return {
          reply: response.response || "",
          toolCalls: response.tool_calls.map((tc: any, index: number) => {
            let parsedArgs = {};
            try {
              parsedArgs = typeof tc.function.arguments === 'string'
                ? JSON.parse(tc.function.arguments)
                : tc.function.arguments || {};
            } catch {
              parsedArgs = {};
            }
            return {
              id: tc.id || `call_${Date.now()}_${index}`,
              name: tc.function.name,
              arguments: parsedArgs
            };
          })
        };
      }

      return {
        reply: response?.response || response?.text || (typeof response === 'string' ? response : JSON.stringify(response))
      };
    } catch (err: any) {
      // Graceful fallback parser for models returning JSON in content
      console.error("Workers AI run error:", err);
      return {
        reply: `AI Model execution error: ${err.message}`
      };
    }
  }
}

export class OpenAICompatibleProvider implements AIProvider {
  private endpoint: string;
  private apiKey: string;

  constructor(endpoint: string, apiKey: string) {
    this.endpoint = endpoint;
    this.apiKey = apiKey;
  }

  async chat(
    model: AIModel,
    messages: AIMessage[],
    tools: ToolDefinition[]
  ): Promise<AIResponse> {
    const res = await fetch(`${this.endpoint}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.apiKey}`
      },
      body: JSON.stringify({
        model: model.id,
        messages,
        tools: tools.map(t => ({
          type: "function",
          function: {
            name: t.name,
            description: t.description,
            parameters: t.parameters
          }
        }))
      })
    });

    const data: any = await res.json();
    const choice = data.choices?.[0]?.message;

    if (choice?.tool_calls) {
      return {
        reply: choice.content || "",
        toolCalls: choice.tool_calls.map((tc: any) => ({
          id: tc.id,
          name: tc.function.name,
          arguments: JSON.parse(tc.function.arguments || "{}")
        }))
      };
    }

    return {
      reply: choice?.content || ""
    };
  }
}

export function createAIProvider(providerType: string, env: any): AIProvider {
  switch (providerType) {
    case 'cloudflare':
    default:
      return new CloudflareAIProvider();
  }
}
