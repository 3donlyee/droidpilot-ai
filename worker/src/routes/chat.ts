import { z } from "zod";
import type { Env } from "../env";
import type { DeviceRegistry } from "../device/DeviceRegistry";
import { AuthManager } from "../auth/AuthManager";
import { AgentEngine, type AgentEvent } from "../ai/AgentEngine";
import { CloudflareAIProvider } from "../ai/CloudflareAIProvider";
import { OpenAIProvider } from "../ai/OpenAIProvider";
import type { AIProvider } from "../ai/AIProvider";
import { getDefaultModel, getModelById } from "../models/registry";
import { checkChat } from "../utils/rateLimit";
import { errorResponse } from "../utils/response";
import { logger } from "../utils/logger";
import { extractBearer, clientIp, parseJsonBody } from "../utils/request";

/**
 * Chat route — SSE stream of agent events.
 *
 *   POST /api/chat
 *   Authorization: Bearer <session JWT>
 *   Content-Type: application/json
 *   Body: { "message": string, "model"?: string }
 *
 * Response: text/event-stream
 *   data: {"type":"tool_call","data":{"call":{...}}}
 *   data: {"type":"tool_result","data":{"callId":"...","success":true,...}}
 *   data: {"type":"assistant","data":{"text":"..."}}
 *   data: {"type":"done","data":{"reason":"stop","totalToolCalls":N}}
 */
export interface ChatContext {
  env: Env;
  auth: AuthManager;
  devices: DeviceRegistry;
}

const chatSchema = z.object({
  message: z.string().min(1).max(8000),
  model: z.string().max(120).optional(),
});

export async function handleChat(req: Request, ctx: ChatContext): Promise<Response> {
  const env = ctx.env;
  const ip = clientIp(req);

  // Rate limit (per-IP, 60/min).
  const allowed = await checkChat(env, ip);
  if (!allowed) {
    return errorResponse("rate_limited", 429, env, req);
  }

  // Auth.
  const token = extractBearer(req);
  if (!token) return errorResponse("unauthorized", 401, env, req);
  const session = await ctx.auth.verify(token);
  if (!session) return errorResponse("invalid_or_expired_token", 401, env, req);

  // Body.
  const body = await parseJsonBody<unknown>(req);
  if (body === null) return errorResponse("invalid_json", 400, env, req);
  const parsed = chatSchema.safeParse(body);
  if (!parsed.success) {
    return errorResponse(
      `invalid_request: ${parsed.error.issues.map((i) => i.path.join(".") + ":" + i.message).join("; ")}`,
      400,
      env,
      req,
    );
  }

  const userMessage = parsed.data.message;

  // Model selection.
  const model = parsed.data.model ? getModelById(parsed.data.model) : getDefaultModel(env);
  if (!model || !model.enabled) {
    return errorResponse("model_not_found_or_disabled", 400, env, req);
  }

  // Provider resolution.
  let provider: AIProvider;
  if (model.provider === "cloudflare") {
    provider = new CloudflareAIProvider(env);
  } else if (model.provider === "openai") {
    if (!env.OPENAI_API_KEY) {
      return errorResponse("openai_provider_not_configured", 503, env, req);
    }
    provider = new OpenAIProvider({
      apiKey: env.OPENAI_API_KEY,
      baseURL: "https://api.openai.com/v1",
      name: "openai",
    });
  } else if (model.provider === "groq") {
    if (!env.OPENAI_API_KEY) {
      return errorResponse("groq_provider_not_configured", 503, env, req);
    }
    provider = new OpenAIProvider({
      apiKey: env.OPENAI_API_KEY,
      baseURL: "https://api.groq.com/openai/v1",
      name: "groq",
    });
  } else {
    return errorResponse("provider_not_implemented", 501, env, req);
  }

  // Device online?
  if (!ctx.devices.isOnline(session.deviceId)) {
    return errorResponse("device_offline", 503, env, req);
  }

  const engine = new AgentEngine(env, provider, model);

  // SSE stream.
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const encoder = new TextEncoder();
      const send = (obj: AgentEvent | { type: string; data: unknown }) => {
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(obj)}\n\n`));
        } catch {
          /* controller may be closed */
        }
      };

      try {
        for await (const ev of engine.run(userMessage, {
          deviceId: session.deviceId,
          sendCommand: (deviceId, command) => ctx.devices.sendCommand(deviceId, command),
        })) {
          send(ev);
          if (ev.type === "done" || ev.type === "error") break;
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        logger.error("chat stream error", { error: msg });
        send({ type: "error", data: { message: msg } });
      } finally {
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      }
    },
  });

  return new Response(stream, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      "Connection": "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
