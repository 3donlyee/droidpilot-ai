export interface PairedDevice {
  deviceId: string;
  pairingCode: string;
  deviceSecret: string;
  model: string;
  androidVersion: string;
  status: 'PENDING' | 'CONNECTED';
  lastSeen: number;
}

export interface QueuedCommand {
  id: string;
  tool: string;
  arguments: Record<string, any>;
  createdAt: number;
}

export interface CommandResult {
  id: string;
  tool: string;
  success: boolean;
  data?: any;
  error?: string;
  timestamp: number;
}

// In-memory runtime session store for Worker execution
const devices = new Map<string, PairedDevice>();
const commandQueues = new Map<string, QueuedCommand[]>();
const commandResults = new Map<string, CommandResult>();
const pendingResolvers = new Map<string, (result: CommandResult) => void>();

export const DeviceRegistry = {
  registerOrUpdate(info: {
    deviceId: string;
    pairingCode: string;
    deviceSecret: string;
    model: string;
    androidVersion: string;
  }): PairedDevice {
    const existing = devices.get(info.deviceId);
    const updated: PairedDevice = {
      deviceId: info.deviceId,
      pairingCode: info.pairingCode,
      deviceSecret: info.deviceSecret,
      model: info.model || "OPPO Reno5",
      androidVersion: info.androidVersion || "13",
      status: 'CONNECTED',
      lastSeen: Date.now()
    };
    devices.set(info.deviceId, updated);
    return updated;
  },

  pairByPin(pin: string): PairedDevice | null {
    for (const [_, dev] of devices.entries()) {
      if (dev.pairingCode === pin) {
        dev.status = 'CONNECTED';
        dev.lastSeen = Date.now();
        return dev;
      }
    }
    return null;
  },

  getDevice(deviceId: string): PairedDevice | undefined {
    return devices.get(deviceId);
  },

  listDevices(): PairedDevice[] {
    return Array.from(devices.values());
  },

  enqueueCommand(deviceId: string, tool: string, args: Record<string, any>): string {
    const cmdId = `cmd_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const queue = commandQueues.get(deviceId) || [];
    queue.push({
      id: cmdId,
      tool,
      arguments: args,
      createdAt: Date.now()
    });
    commandQueues.set(deviceId, queue);
    return cmdId;
  },

  pollCommands(deviceId: string, secret: string): QueuedCommand[] {
    const dev = devices.get(deviceId);
    if (!dev || dev.deviceSecret !== secret) {
      return [];
    }
    dev.lastSeen = Date.now();
    const queue = commandQueues.get(deviceId) || [];
    commandQueues.set(deviceId, []);
    return queue;
  },

  recordResult(result: CommandResult) {
    commandResults.set(result.id, result);
    const resolver = pendingResolvers.get(result.id);
    if (resolver) {
      resolver(result);
      pendingResolvers.delete(result.id);
    }
  },

  async waitForResult(commandId: string, timeoutMs: number = 10000): Promise<CommandResult> {
    const existing = commandResults.get(commandId);
    if (existing) return existing;

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        pendingResolvers.delete(commandId);
        resolve({
          id: commandId,
          tool: "timeout",
          success: false,
          error: "TIMEOUT: Device did not return response within deadline",
          timestamp: Date.now()
        });
      }, timeoutMs);

      pendingResolvers.set(commandId, (res) => {
        clearTimeout(timer);
        resolve(res);
      });
    });
  }
};
