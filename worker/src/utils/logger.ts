/**
 * Structured logger.
 *
 * Writes one JSON line per log entry to `console.log/warn/error` (which
 * Cloudflare's runtime forwards to `wrangler tail` and the dashboard).
 *
 * SECURITY: filters out known secret field names before serialization, so it
 * is safe to pass arbitrary context objects (e.g. request bodies, headers)
 * as the `fields` argument.
 */

type LogLevel = "debug" | "info" | "warn" | "error";

const SECRET_FIELDS = new Set<string>([
  "secret",
  "device_secret",
  "deviceSecret",
  "secret_key",
  "token",
  "access_token",
  "refresh_token",
  "authorization",
  "auth",
  "apikey",
  "api_key",
  "apiKey",
  "password",
  "passwd",
  "pairing_code",
  "pairingCode",
  "pin",
  "cookie",
]);

function redact(value: unknown, depth = 0): unknown {
  if (value === null || value === undefined) return value;
  if (depth > 6) return "[truncated]";
  if (typeof value !== "object") return value;
  if (Array.isArray(value)) {
    return value.map((v) => redact(v, depth + 1));
  }
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (SECRET_FIELDS.has(k.toLowerCase())) {
      out[k] = "[redacted]";
    } else {
      out[k] = redact(v, depth + 1);
    }
  }
  return out;
}

function log(level: LogLevel, msg: string, fields?: Record<string, unknown>): void {
  const entry: Record<string, unknown> = {
    level,
    msg,
    ts: new Date().toISOString(),
  };
  if (fields && Object.keys(fields).length > 0) {
    Object.assign(entry, redact(fields) as Record<string, unknown>);
  }
  const line = JSON.stringify(entry);
  if (level === "error") console.error(line);
  else if (level === "warn") console.warn(line);
  else console.log(line);
}

export const logger = {
  debug: (msg: string, fields?: Record<string, unknown>) => log("debug", msg, fields),
  info: (msg: string, fields?: Record<string, unknown>) => log("info", msg, fields),
  warn: (msg: string, fields?: Record<string, unknown>) => log("warn", msg, fields),
  error: (msg: string, fields?: Record<string, unknown>) => log("error", msg, fields),
};
