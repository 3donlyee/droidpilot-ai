import { logger } from "../utils/logger";

/**
 * DeviceRegistry — tracks live WebSocket connections to Android devices.
 *
 * This is an in-memory map (per Worker isolate). Because the WebSocket must
 * persist between requests, we keep it as a module-level singleton. The same
 * isolate will handle all requests for a given connected device until the
 * isolate is evicted (which closes the WS — the device will reconnect).
 *
 * Send-command flow:
 *   1. Worker sends `{type:"command", id, tool, arguments}` to the device WS.
 *   2. Device executes the tool and replies with `{type:"result", id, success, ...}`.
 *   3. We match the result by id to a pending Promise and resolve it.
 *   4. If no reply within 30s, the Promise resolves with a TIMEOUT error envelope.
 */

export interface CommandEnvelope {
  type: "command";
  id: string;
  tool: string;
  arguments: Record<string, unknown>;
}

export interface ResultEnvelope {
  type: "result";
  id: string;
  success: boolean;
  data?: Record<string, unknown> | null;
  error?: { code: string; message: string };
}

interface PendingResolver {
  resolve: (r: ResultEnvelope) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

const COMMAND_TIMEOUT_MS = 30_000;

export class DeviceRegistry {
  /** deviceId → open WebSocket. */
  private devices = new Map<string, WebSocket>();
  /** `${deviceId}:${cmdId}` → pending resolver. */
  private pending = new Map<string, PendingResolver>();

  /**
   * Register a device's WebSocket. Closes any previous connection for the
   * same deviceId (single-session-per-device).
   */
  register(deviceId: string, ws: WebSocket): void {
    const existing = this.devices.get(deviceId);
    if (existing && existing !== ws) {
      try {
        existing.close(1000, "replaced");
      } catch {
        /* noop */
      }
    }
    this.devices.set(deviceId, ws);

    const onMessage = (event: MessageEvent) => this.onDeviceMessage(deviceId, event);
    const onClose = () => this.cleanupDevice(deviceId);

    ws.addEventListener("message", onMessage);
    ws.addEventListener("close", onClose);
    ws.addEventListener("error", onClose);

    logger.info("device registered", { deviceId });
  }

  /** Manually unregister a device (rare — usually triggered by WS close). */
  unregister(deviceId: string): void {
    this.cleanupDevice(deviceId);
  }

  isOnline(deviceId: string): boolean {
    const ws = this.devices.get(deviceId);
    return !!ws && ws.readyState === 1; // OPEN
  }

  listOnline(): string[] {
    return Array.from(this.devices.keys());
  }

  /**
   * Send a command to the device and resolve when the result arrives
   * (or 30s elapses). Never throws — failures are returned as a
   * `success:false` ResultEnvelope with an `error` field.
   */
  async sendCommand(deviceId: string, command: CommandEnvelope): Promise<ResultEnvelope> {
    const ws = this.devices.get(deviceId);
    if (!ws || ws.readyState !== 1 /* OPEN */) {
      return {
        type: "result",
        id: command.id,
        success: false,
        error: {
          code: "DEVICE_OFFLINE",
          message: `Device ${deviceId} is not connected`,
        },
      };
    }

    return new Promise<ResultEnvelope>((resolve, reject) => {
      const key = `${deviceId}:${command.id}`;

      const timer = setTimeout(() => {
        const p = this.pending.get(key);
        if (p) {
          this.pending.delete(key);
          resolve({
            type: "result",
            id: command.id,
            success: false,
            error: {
              code: "TIMEOUT",
              message: `Device did not respond within ${COMMAND_TIMEOUT_MS}ms`,
            },
          });
        }
      }, COMMAND_TIMEOUT_MS);

      this.pending.set(key, { resolve, reject, timer });

      try {
        ws.send(JSON.stringify(command));
      } catch (e) {
        clearTimeout(timer);
        this.pending.delete(key);
        const msg = e instanceof Error ? e.message : String(e);
        resolve({
          type: "result",
          id: command.id,
          success: false,
          error: { code: "TRANSPORT_ERROR", message: msg },
        });
      }
    });
  }

  /** Handle an inbound message on a device WS. */
  private onDeviceMessage(deviceId: string, event: MessageEvent): void {
    let data: unknown;
    try {
      data = typeof event.data === "string" ? JSON.parse(event.data) : null;
    } catch {
      logger.warn("device ws: invalid JSON frame", { deviceId });
      return;
    }
    if (!data || typeof data !== "object") return;
    const env = data as Partial<ResultEnvelope>;
    if (env.type !== "result" || !env.id) return;

    const key = `${deviceId}:${env.id}`;
    const pending = this.pending.get(key);
    if (!pending) {
      logger.warn("device ws: result for unknown command id", {
        deviceId,
        commandId: env.id,
      });
      return;
    }
    clearTimeout(pending.timer);
    this.pending.delete(key);
    pending.resolve(env as ResultEnvelope);
  }

  /** Remove a device and reject all its pending commands. */
  private cleanupDevice(deviceId: string): void {
    const ws = this.devices.get(deviceId);
    if (!ws) return;
    this.devices.delete(deviceId);

    const prefix = `${deviceId}:`;
    const toReject: PendingResolver[] = [];
    for (const [key, p] of Array.from(this.pending.entries())) {
      if (key.startsWith(prefix)) {
        toReject.push(p);
        this.pending.delete(key);
      }
    }
    for (const p of toReject) {
      clearTimeout(p.timer);
      p.reject(new Error(`Device ${deviceId} disconnected`));
    }
    logger.info("device unregistered", { deviceId });
  }
}
