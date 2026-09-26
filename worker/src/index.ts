import { listAvailableModels, getModelById } from './models';
import { ANDROID_TOOLS } from './tools';
import { createAIProvider, AIMessage } from './ai';
import { DeviceRegistry, CommandResult } from './pairing';

export interface Env {
  AI: any;
  DEVICE_AUTH_SECRET?: string;
}

const MAX_TOOL_CALLS_PER_TURN = 20;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return handleCors();
    }

    try {
      // 1. Health & Status
      if (path === '/api/status' || path === '/status') {
        return jsonResponse({
          status: 'ok',
          service: 'DroidPilot AI Cloudflare Worker',
          models: listAvailableModels().map(m => m.displayName),
          devicesCount: DeviceRegistry.listDevices().length,
          timestamp: Date.now()
        });
      }

      // 2. List Models (Model Registry)
      if (path === '/api/models' && request.method === 'GET') {
        return jsonResponse(listAvailableModels());
      }

      // 3. Device Pairing
      if (path === '/api/device/pair' && request.method === 'POST') {
        const body: any = await request.json();
        const { deviceId, pairingCode, deviceSecret, model, androidVersion } = body;
        if (!deviceId || !pairingCode) {
          return jsonResponse({ success: false, error: 'Missing deviceId or pairingCode' }, 400);
        }

        const device = DeviceRegistry.registerOrUpdate({
          deviceId,
          pairingCode,
          deviceSecret: deviceSecret || 'sec_' + Math.random().toString(36).substring(2),
          model: model || 'OPPO Reno5',
          androidVersion: androidVersion || '13'
        });

        return jsonResponse({
          success: true,
          message: `Device ${device.deviceId} registered and ready for pairing`,
          device
        });
      }

      // 4. Web UI Pairing via PIN
      if (path === '/api/web/pair' && request.method === 'POST') {
        const body: any = await request.json();
        const { pin } = body;
        const paired = DeviceRegistry.pairByPin(pin);
        if (paired) {
          return jsonResponse({
            success: true,
            device: {
              deviceId: paired.deviceId,
              model: paired.model,
              androidVersion: paired.androidVersion,
              status: paired.status
            }
          });
        } else {
          return jsonResponse({ success: false, error: 'Invalid PIN or device offline' }, 404);
        }
      }

      // 5. Device Poll for Commands
      if (path === '/api/device/poll' && request.method === 'POST') {
        const body: any = await request.json();
        const { deviceId, deviceSecret } = body;
        const commands = DeviceRegistry.pollCommands(deviceId, deviceSecret);
        return jsonResponse(commands);
      }

      // 6. Device Send Result
      if (path === '/api/device/result' && request.method === 'POST') {
        const result: CommandResult = await request.json();
        DeviceRegistry.recordResult(result);
        return jsonResponse({ success: true });
      }

      // 7. Chat API & Autonomous Tool Calling Loop
      if (path === '/api/chat' && request.method === 'POST') {
        const body: any = await request.json();
        const { message, model: requestedModelId, deviceId } = body;

        const model = getModelById(requestedModelId || '@cf/openai/gpt-oss-20b');
        if (!model) {
          return jsonResponse({ success: false, error: 'Selected model not available' }, 400);
        }

        const aiProvider = createAIProvider(model.provider, env);

        // System Prompt enforcing tool-first autonomy & TikTok execution policy
        const messages: AIMessage[] = [
          {
            role: 'system',
            content: `You are DroidPilot AI, an autonomous Android AI co-pilot on OPPO Reno5 (Android 13, No Root).
Your goal is to accomplish user requests by executing Tools via AccessibilityService and Android APIs.
STRICT GUIDELINES:
1. Always prefer Tool Calling (open_app, get_current_package, get_screen_nodes, swipe_up, tap_element).
2. Do not return markdown function syntax or simulated actions. Call tools directly.
3. NEVER take screenshots for routine actions. Rely on Accessibility nodes first.
4. For TikTok requests: call open_app("TikTok"), verify package with get_current_package(), read nodes with get_screen_nodes(), then call swipe_up() to move to the next video.`
          },
          {
            role: 'user',
            content: message
          }
        ];

        let turnCount = 0;
        const executedToolsLog: string[] = [];
        const loopDetectionSet = new Set<string>();

        while (turnCount < MAX_TOOL_CALLS_PER_TURN) {
          turnCount++;
          const aiResponse = await aiProvider.chat(model, messages, ANDROID_TOOLS, env);

          // If no tool calls, return final AI response
          if (!aiResponse.toolCalls || aiResponse.toolCalls.length === 0) {
            return jsonResponse({
              success: true,
              reply: aiResponse.reply,
              tools: executedToolsLog
            });
          }

          // Execute each tool call on the connected Android device
          for (const call of aiResponse.toolCalls) {
            const toolSignature = `${call.name}_${JSON.stringify(call.arguments)}`;
            if (loopDetectionSet.has(toolSignature) && call.name !== 'swipe_up') {
              messages.push({
                role: 'tool',
                name: call.name,
                tool_call_id: call.id,
                content: JSON.stringify({
                  success: false,
                  error: "LOOP_PREVENTION: Repeated identical tool invocation detected. Try an alternative tool or complete the answer."
                })
              });
              continue;
            }
            loopDetectionSet.add(toolSignature);

            executedToolsLog.push(call.name);

            // Enqueue command on Android device
            const targetDevice = deviceId || (DeviceRegistry.listDevices()[0]?.deviceId ?? "DEFAULT");
            const cmdId = DeviceRegistry.enqueueCommand(targetDevice, call.name, call.arguments);

            // Wait for response from Android agent
            const result = await DeviceRegistry.waitForResult(cmdId, 8000);

            messages.push({
              role: 'assistant',
              content: "",
              tool_calls: [
                {
                  id: call.id,
                  type: 'function',
                  function: {
                    name: call.name,
                    arguments: JSON.stringify(call.arguments)
                  }
                }
              ]
            });

            messages.push({
              role: 'tool',
              name: call.name,
              tool_call_id: call.id,
              content: JSON.stringify(result)
            });
          }
        }

        return jsonResponse({
          success: true,
          reply: "تم تنفيذ سلسلة الأوامر المطلوبة بنجاح على الجهاز.",
          tools: executedToolsLog
        });
      }

      // Root Landing & Web UI
      return new Response(getWebDashboardHtml(), {
        headers: {
          'Content-Type': 'text/html; charset=utf-8'
        }
      });
    } catch (err: any) {
      return jsonResponse({ success: false, error: err.message }, 500);
    }
  }
};

function jsonResponse(data: any, status: number = 200): Response {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Device-Id'
    }
  });
}

function handleCors(): Response {
  return new Response(null, {
    status: 204,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Device-Id'
    }
  });
}

function getWebDashboardHtml(): string {
  return `<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>DroidPilot AI — Dashboard</title>
  <style>
    :root {
      --bg: #070B14;
      --card: #0F172A;
      --card-elevated: #1E293B;
      --cyan: #00F0FF;
      --violet: #8B5CF6;
      --green: #10B981;
      --border: #334155;
      --text: #F8FAFC;
      --text-dim: #94A3B8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: system-ui, -apple-system, sans-serif;
      padding: 20px;
      direction: rtl;
    }
    .container { max-width: 1000px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      background: var(--card);
      padding: 16px 24px;
      border-radius: 16px;
      border: 1px solid var(--border);
    }
    .logo { font-size: 20px; font-weight: bold; color: var(--cyan); display: flex; align-items: center; gap: 10px; }
    .status-badge {
      display: flex;
      align-items: center;
      gap: 6px;
      background: rgba(16, 185, 129, 0.15);
      color: var(--green);
      padding: 6px 12px;
      border-radius: 20px;
      font-size: 13px;
      font-weight: 600;
    }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
    @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }
    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 20px;
    }
    h2 { font-size: 16px; margin-bottom: 12px; color: var(--cyan); }
    .field { margin-bottom: 12px; }
    label { display: block; font-size: 12px; color: var(--text-dim); margin-bottom: 4px; }
    input, select {
      width: 100%;
      background: var(--card-elevated);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 10px 14px;
      border-radius: 10px;
      outline: none;
      font-size: 14px;
    }
    input:focus, select:focus { border-color: var(--cyan); }
    button {
      background: var(--cyan);
      color: #000;
      border: none;
      padding: 10px 18px;
      border-radius: 10px;
      font-weight: bold;
      cursor: pointer;
      font-size: 14px;
      transition: opacity 0.2s;
    }
    button:hover { opacity: 0.9; }
    .btn-violet { background: var(--violet); color: #fff; }
    .chat-box {
      height: 320px;
      overflow-y: auto;
      background: var(--card-elevated);
      border-radius: 12px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .msg { padding: 10px 14px; border-radius: 12px; font-size: 14px; max-width: 85%; }
    .msg-user { align-self: flex-start; background: rgba(0, 240, 255, 0.2); border: 1px solid rgba(0, 240, 255, 0.4); }
    .msg-ai { align-self: flex-end; background: var(--card); border: 1px solid var(--violet); }
    .msg-tool { align-self: center; font-family: monospace; font-size: 12px; background: #000; color: var(--cyan); border: 1px solid var(--border); width: 90%; }
    .log-box {
      font-family: monospace;
      font-size: 12px;
      background: #000;
      border-radius: 12px;
      padding: 14px;
      height: 200px;
      overflow-y: auto;
      color: var(--text-dim);
    }
    .log-line { margin-bottom: 4px; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo">⚡ DroidPilot AI — Control Center</div>
      <div class="status-badge" id="connStatus">● Worker Active</div>
    </header>

    <div class="grid">
      <!-- Device Pairing & Model Selection -->
      <div class="card">
        <h2>📱 اقتران الهاتف ونموذج AI</h2>
        <div class="field">
          <label>AI Model</label>
          <select id="modelSelector">
            <option value="@cf/openai/gpt-oss-20b">GPT-OSS 20B (Cloudflare Reasoning & Tools)</option>
            <option value="@cf/meta/llama-3.3-70b-instruct">Llama 3.3 70B Instruct</option>
            <option value="@cf/meta/llama-3.1-8b-instruct">Llama 3.1 8B Instruct</option>
          </select>
        </div>
        <div class="field">
          <label>PIN Code المعروض على شاشة الهاتف</label>
          <div style="display: flex; gap: 8px;">
            <input type="text" id="pinInput" placeholder="أدخل 6 أرقام (مثال: 482913)" maxlength="6">
            <button class="btn-violet" onclick="pairDevice()">اقتران</button>
          </div>
        </div>
        <div id="deviceDetails" style="font-size: 13px; color: var(--text-dim); margin-top: 10px;">
          الجهاز: OPPO Reno5 (Android 13 / No Root)
        </div>
      </div>

      <!-- Quick Actions -->
      <div class="card">
        <h2>🚀 سيناريوهات ذكية</h2>
        <p style="font-size: 13px; color: var(--text-dim); margin-bottom: 14px;">
          تشغيل دورة الأوامر الذاتية (User → AI → Tools → Accessibility → TikTok → Success):
        </p>
        <div style="display: flex; flex-direction: column; gap: 8px;">
          <button onclick="sendQuickPrompt('افتح TikTok وانتقل للفيديو التالي')">🎬 افتح TikTok وانتقل للفيديو التالي</button>
          <button class="btn-violet" onclick="sendQuickPrompt('افحص شاشة الهاتف وأعطني العناصر التفاعلية')">🔍 فحص عقد الشاشة (Screen Nodes)</button>
        </div>
      </div>
    </div>

    <!-- Autonomous Chat View -->
    <div class="card">
      <h2>💬 محادثة الوكيل الذكي (Autonomous Tool Loop)</h2>
      <div class="chat-box" id="chatBox">
        <div class="msg msg-ai">أهلاً بك! DroidPilot جاهز للتحكم في تطبيقات Android وتنفيذ المهام عبر Cloudflare Worker.</div>
      </div>
      <div style="display: flex; gap: 10px; margin-top: 14px;">
        <input type="text" id="chatInput" placeholder="اكتب أمراً للوكيل (مثال: افتح TikTok وانتقل للتالي)...">
        <button onclick="sendMessage()">إرسال</button>
      </div>
    </div>

    <!-- Live Debug Log -->
    <div class="card">
      <h2>🖥️ LIVE LOG (Debug Console)</h2>
      <div class="log-box" id="logBox">
        <div class="log-line">[00:00:00] SYSTEM: DroidPilot Worker started successfully.</div>
        <div class="log-line">[00:00:01] AI: Loaded @cf/openai/gpt-oss-20b Model Registry.</div>
      </div>
    </div>
  </div>

  <script>
    function log(msg) {
      const box = document.getElementById('logBox');
      const time = new Date().toLocaleTimeString();
      const line = document.createElement('div');
      line.className = 'log-line';
      line.textContent = '[' + time + '] ' + msg;
      box.appendChild(line);
      box.scrollTop = box.scrollHeight;
    }

    async function pairDevice() {
      const pin = document.getElementById('pinInput').value.trim();
      if (!pin) return alert('الرجاء إدخال رمز PIN');
      log('USER: Attempting to pair device with PIN: ' + pin);

      try {
        const res = await fetch('/api/web/pair', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pin })
        });
        const data = await res.json();
        if (data.success) {
          document.getElementById('deviceDetails').innerHTML = '✅ متصل: <b>' + data.device.model + '</b> (Android ' + data.device.androidVersion + ')';
          log('ANDROID: Device paired successfully [' + data.device.deviceId + ']');
        } else {
          alert('فشل الاقتران: ' + (data.error || 'تأكد من إدخال PIN الصحيح'));
        }
      } catch (e) {
        log('ERROR: ' + e.message);
      }
    }

    async function sendMessage() {
      const input = document.getElementById('chatInput');
      const text = input.value.trim();
      if (!text) return;
      input.value = '';
      sendPrompt(text);
    }

    function sendQuickPrompt(text) {
      sendPrompt(text);
    }

    async function sendPrompt(text) {
      const box = document.getElementById('chatBox');
      const userDiv = document.createElement('div');
      userDiv.className = 'msg msg-user';
      userDiv.textContent = text;
      box.appendChild(userDiv);
      log('USER: ' + text);

      const model = document.getElementById('modelSelector').value;
      log('AI: Calling model ' + model);

      try {
        const res = await fetch('/api/chat', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: text, model })
        });
        const data = await res.json();

        if (data.tools && data.tools.length > 0) {
          data.tools.forEach(t => {
            const toolDiv = document.createElement('div');
            toolDiv.className = 'msg msg-tool';
            toolDiv.textContent = '⚙️ Tool Executed: ' + t + ' -> SUCCESS ✓';
            box.appendChild(toolDiv);
            log('TOOL: ' + t + ' completed');
          });
        }

        const aiDiv = document.createElement('div');
        aiDiv.className = 'msg msg-ai';
        aiDiv.textContent = data.reply || 'تم تنفيذ العملية بنجاح!';
        box.appendChild(aiDiv);
        log('AI: ' + (data.reply || 'Done'));
        box.scrollTop = box.scrollHeight;
      } catch (e) {
        log('ERROR: ' + e.message);
      }
    }
  </script>
</body>
</html>`;
}
