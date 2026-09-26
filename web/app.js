const API_BASE = window.location.origin;

function addLog(msg, type = 'sys') {
  const terminal = document.getElementById('logTerminal');
  const time = new Date().toLocaleTimeString();
  const div = document.createElement('div');
  div.className = `log-entry log-${type}`;
  div.textContent = `[${time}] ${msg}`;
  terminal.appendChild(div);
  terminal.scrollTop = terminal.scrollHeight;
}

function clearLogs() {
  document.getElementById('logTerminal').innerHTML = '';
  addLog('Logs cleared.', 'sys');
}

async function pairDevice() {
  const pinInput = document.getElementById('pinInput');
  const pin = pinInput.value.trim();
  if (!pin || pin.length < 4) {
    alert('الرجاء إدخال رمز PIN المكون من 6 أرقام');
    return;
  }

  addLog(`USER: Pairing request with PIN: ${pin}`, 'user');

  try {
    const res = await fetch(`${API_BASE}/api/web/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin })
    });
    const data = await res.json();

    if (data.success) {
      document.getElementById('deviceBadge').textContent = `● Device: ${data.device.model}`;
      document.getElementById('deviceBadge').style.color = '#10B981';
      document.getElementById('deviceBadge').style.borderColor = 'rgba(16, 185, 129, 0.4)';

      document.getElementById('pairedDeviceInfo').innerHTML = `
        <div><b>الجهاز:</b> ${data.device.model} (ID: ${data.device.deviceId})</div>
        <div><b>النظام:</b> Android ${data.device.androidVersion}</div>
        <div><b>الحالة:</b> متصل وجاهز لتنفيذ الأوامر ✓</div>
      `;

      addLog(`ANDROID: Connected to ${data.device.model} [${data.device.deviceId}]`, 'tool');
    } else {
      alert(`فشل الاقتران: ${data.error || 'تأكد من إدخال رمز PIN الصحيح'}`);
      addLog(`SYSTEM: Pairing failed - ${data.error}`, 'sys');
    }
  } catch (err) {
    addLog(`ERROR: Connection failed: ${err.message}`, 'sys');
  }
}

function triggerPreset(text) {
  sendPrompt(text);
}

function sendMessage() {
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  sendPrompt(text);
}

async function sendPrompt(text) {
  const chatMessages = document.getElementById('chatMessages');

  // Append user bubble
  const userDiv = document.createElement('div');
  userDiv.className = 'chat-bubble user-bubble';
  userDiv.textContent = text;
  chatMessages.appendChild(userDiv);
  addLog(`USER: ${text}`, 'user');

  const model = document.getElementById('modelSelector').value;
  addLog(`AI: Invoking model ${model} with autonomous tool-loop...`, 'ai');

  try {
    const res = await fetch(`${API_BASE}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, model })
    });
    const data = await res.json();

    if (data.tools && data.tools.length > 0) {
      data.tools.forEach(t => {
        const toolDiv = document.createElement('div');
        toolDiv.className = 'chat-bubble tool-bubble';
        toolDiv.textContent = `⚙️ Tool Call: ${t} -> SUCCESS ✓`;
        chatMessages.appendChild(toolDiv);
        addLog(`TOOL: ${t} -> execution success`, 'tool');
      });
    }

    const aiDiv = document.createElement('div');
    aiDiv.className = 'chat-bubble ai-bubble';
    aiDiv.textContent = data.reply || 'تم تنفيذ العملية بنجاح!';
    chatMessages.appendChild(aiDiv);
    addLog(`AI: ${data.reply || 'Completed.'}`, 'ai');

    chatMessages.scrollTop = chatMessages.scrollHeight;
  } catch (err) {
    addLog(`ERROR: ${err.message}`, 'sys');
  }
}

// Enter key binding
document.getElementById('chatInput')?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendMessage();
});
