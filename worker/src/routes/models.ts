import type { Env } from "../env";
import { getEnabledModels, getModelById } from "../models/registry";
import { jsonResponse, errorResponse } from "../utils/response";

/**
 * Model registry routes (public, no auth).
 *
 *   GET /api/models       — list enabled models.
 *   GET /api/models/:id   — get one model by id (works for disabled too).
 */

/** Request type extended with itty-router params. */
type APIRequest = Request & { params?: Record<string, string> };

/** GET /api/models — list enabled models. */
export function handleListModels(req: Request, env: Env): Response {
  return jsonResponse({ models: getEnabledModels() }, 200, env, req);
}

/** GET /api/models/:id — get one model. */
export function handleGetModel(req: APIRequest, env: Env): Response {
  const id = req.params?.id ?? "";
  if (!id) return errorResponse("missing_model_id", 400, env, req);
  const model = getModelById(id);
  if (!model) return errorResponse("model_not_found", 404, env, req);
  return jsonResponse({ model }, 200, env, req);
}
