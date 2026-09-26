# DroidPilot AI — Architecture & Specification

## 1. System Overview

DroidPilot AI is an autonomous Android AI Agent designed to interact directly with mobile apps (e.g., TikTok, Chrome, Settings) using Android AccessibilityService APIs, intelligent gesture dispatching, and Cloudflare Workers AI orchestration.

```
USER
  │
  ▼
DroidPilot Web / Android Chat
  │
  ▼
Cloudflare Worker (Router & Session Manager)
  │
  ▼
AI Model (@cf/openai/gpt-oss-20b, Llama 3.3 70B)
  │
  ▼ (Function / Tool Calling)
Worker Command Queue
  │
  ▼ (HTTPS / WebSocket)
Android DroidPilot (Agent & Accessibility Service)
  │
  ▼
Accessibility APIs & Gestures (No Root / OPPO Reno5)
  │
  ▼
Target Application (e.g. TikTok)
  │
  ▼
Result & UI Hierarchy Nodes
  │
  ▼
AI Model Evaluates Next Step (Max 20 turns)
  │
  ▼
Final Response to User
```

---

## 2. Core Components

### A. Android Agent (`app/`)
1. **DroidPilotAccessibilityService**:
   - Dispatches gestures (`swipeUp`, `swipeDown`, `tapCoordinates`, `typeText`, `pressBack`)
   - Traverses accessibility node tree (`get_screen_nodes`)
   - Tracks active foreground package (`currentForegroundPackage`)
   - Captures on-demand screenshots when strictly needed
2. **DroidPilotAgentService**:
   - Persistent foreground service displaying an explicit ongoing notification
   - Polls or maintains long-lived channel with Cloudflare Worker
   - Handles command dispatching and result reporting
3. **DeviceManager**:
   - Gathers hardware metrics (OPPO Reno5 CPH2159, Android 13, Battery, Screen)
   - Generates persistent `deviceId` and 6-digit `pairingCode`
4. **ToolExecutor**:
   - Implements the complete set of tool handlers (`open_app`, `swipe_up`, `get_screen_nodes`, `run_shell`, etc.)
5. **CommandValidator**:
   - Strict command policy: `SAFE`, `REQUIRES_CONFIRMATION`, `BLOCKED`

### B. Cloudflare Worker (`worker/`)
1. **Model Registry (`models.ts`)**:
   - Model definitions and capabilities matrix (`@cf/openai/gpt-oss-20b`, `Llama 3.3 70B`, etc.)
2. **AI Provider Abstraction (`ai.ts`)**:
   - Decoupled interface supporting Cloudflare Workers AI (`env.AI`) and OpenAI-compatible backends
3. **Session & Pairing Manager (`pairing.ts`)**:
   - Handles PIN verification, device command queues, and asynchronous result promises
4. **Autonomous Tool Loop (`index.ts`)**:
   - Orchestrates multi-turn function calling (up to 20 turns per prompt) with duplicate loop prevention

### C. Web Dashboard (`web/`)
- Modern cybernetic dashboard with live device connection badge, model selector, chat, and real-time debug console.
