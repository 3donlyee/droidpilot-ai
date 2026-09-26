/**
 * AIMINOS AGENT TEAM
 * ==================
 * One brain, five specialists. Each agent = persona + tool subset + step budget.
 * Routing is deterministic (keyword heuristics) — no extra AI call wasted.
 * The user can also address an agent directly via agent_id (Web UI team cards).
 */
import type { ToolSchema } from "./types";
import { TOOL_SCHEMAS } from "./tools";

export interface AgentEntry {
  id: string;
  name: string;
  emoji: string;
  tagline: string;
  /** Extra persona lines appended to the shared base rules. */
  persona: string;
  /** Tool names allowed for this agent. "*" = all. */
  tools: string[] | "*";
  max_steps: number;
  /** Routing keywords (lowercase, Arabic + Darija + English). */
  keywords: string[];
}

const BASE_RULES = [
  "You are Aiminos — عقل مدبر يتحكم بهاتف المستخدم عبر أدوات تُنفَّذ على الجهاز.",
  "قواعد التنفيذ:",
  "1. أصدِر أمر أداة واحدًا في كل خطوة، وانتظر نتيجته قبل الخطوة التالية.",
  "2. قبل أي ضغطة: نادِ get_screen_nodes واختر element_id الصحيح.",
  "3. بعد open_app تحقق بـ get_current_package.",
  "4. عند فشل أداة أعد الملاحظة وجرّب استراتيجية مختلفة؛ لا تكرر نفس الفاشلة مرتين.",
  "5. لا تنفّذ أي شيء مدمّر أو غير آمن.",
  "6. ارد بلغة المستخدم (عربية غالبًا) وباختصار شديد وبدون تفاصيل مملة.",
  "7. عند تحقيق الهدف أكد بإيجاز وتوقف.",
  "8. إذا طلب المستخدم حفظ شيء مهم استخدم memory_save فورًا، وإذا احتجت معلومة محفوظة ابحث في الذاكرة المعطاة لك في الأعلى قبل أن تسأل المستخدم.",
].join("\n");

export const AGENTS: AgentEntry[] = [
  {
    id: "general",
    name: "المُدَبِّر",
    emoji: "🧠",
    tagline: "العقل المدبّر — يفهم المهمة وينجزها من البداية للنهاية",
    persona: "أنت الوكيل العام: تفهم المهمة كاملة وتختار أفضل مسار بنفسك.",
    tools: "*",
    max_steps: 20,
    keywords: [],
  },
  {
    id: "memorizer",
    name: "الأمين",
    emoji: "🔐",
    tagline: "حارس الذاكرة — يحفظ ويسترجع وينسى بأمان",
    persona: "أنت أمين الذاكرة: مهمتك فقط الحفظ والاسترجاع والحذف عبر أدوات memory_. أكد للمستخدم ما حفظته بكلمة واحدة لطيفة.",
    tools: ["memory_save", "memory_list", "memory_forget", "get_device_info"],
    max_steps: 6,
    keywords: ["تذكر", "احفظ", "خزن", "سجل عندك", "منشن", "ذاكرتك", "ماذا تعرف", "انسي", "انسي", "منسي", "remember", "منشنها"],
  },
  {
    id: "interactor",
    name: "المُتفاعِل",
    emoji: "⌨️",
    tagline: "الكاتب — تعليقات ورسائل وبحث وكل ما يحتاج كتابة",
    persona: "أنت وكيل التفاعل: متخصص في الضغط والكتابة وإرسال النصوص (تعليقات، رسائل، بحث). اكتب النص المطلوب حرفيًا كما طلبه المستخدم، ثم أرسله.",
    tools: ["open_app", "get_current_package", "get_screen_nodes", "tap_element", "type_text", "press_back", "swipe_up", "take_screenshot"],
    max_steps: 16,
    keywords: ["اكتب", "تعليق", "علّق", "علق", "رسالة", "أرسل", "ابعث", "صندوق", "ابحث", "كومنتار", "react", "comment", "type"],
  },
  {
    id: "observer",
    name: "المُراقِب",
    emoji: "👁️",
    tagline: "العيون — يقرأ الشاشة ويحلل دون أن يلمس شيئًا",
    persona: "أنت وكيل الملاحظة: لا تلمس شيئًا أبدًا. اقرأ الشاشة عبر get_screen_nodes (والصورة عبر take_screenshot عند الضرورة) واشرح ما تراه بإيجاز.",
    tools: ["get_screen_nodes", "take_screenshot", "get_current_package", "get_device_info", "get_logs"],
    max_steps: 8,
    keywords: ["ماذا ترى", "شكون", "واش فالشاشة", "اقرأ", "اشرح الشاشة", "حلل", "شاشتي", "what do you see", "اقرا"],
  },
  {
    id: "navigator",
    name: "المُتصفِّح",
    emoji: "🧭",
    tagline: "اليد الخفيفة — فتح التطبيقات والتنقل والتمرير",
    persona: "أنت وكيل التنقل: تفتح التطبيقات وتتنقل وتمرّر. لا تكتب نصوصًا إلا للضرورة القصوى.",
    tools: ["open_app", "get_current_package", "get_screen_nodes", "tap_element", "swipe_up", "swipe_down", "press_back", "take_screenshot"],
    max_steps: 12,
    keywords: ["افتح", "شغّل", "شغل", "انتقل", "الفيديو التالي", "التالي", "مرّر", "مرر", "ارجع للخلف", "زور", "browse", "open"],
  },
];

/** Routing priority: memorizer → interactor → observer → navigator → general. */
const ROUTE_ORDER = ["memorizer", "interactor", "observer", "navigator", "general"];

export function getAgent(id: string | null | undefined): AgentEntry {
  return AGENTS.find((a) => a.id === id) ?? AGENTS[0];
}

export function pickAgent(message: string, override?: string | null): AgentEntry {
  if (override) return getAgent(override);
  const m = message.toLowerCase();
  for (const id of ROUTE_ORDER) {
    const a = AGENTS.find((x) => x.id === id)!;
    if (a.keywords.some((k) => m.includes(k))) return a;
  }
  return AGENTS[0];
}

export function toolsForAgent(a: AgentEntry): ToolSchema[] {
  if (a.tools === "*") return TOOL_SCHEMAS;
  const set = new Set(a.tools);
  return TOOL_SCHEMAS.filter((t) => set.has(t.function.name));
}

/** Full system prompt for a turn. */
export function systemPrompt(agent: AgentEntry, memoryText: string, deviceInfo?: { model?: string; android_version?: string }): string {
  const dev = deviceInfo?.model ? `\nالجهاز المستهدف: ${deviceInfo.model} (Android ${deviceInfo.android_version ?? "?"}).` : "";
  const mem = memoryText ? `\n\n=== ذاكرة Aiminos ===\n${memoryText}\n=== نهاية الذاكرة ===` : "";
  return `${BASE_RULES}\n\nهويتك الآن: ${agent.emoji} ${agent.name} — ${agent.tagline}\n${agent.persona}${dev}${mem}`;
}

/** Public projection for the Web UI "الفريق" tab. */
export function publicAgents(): any[] {
  return AGENTS.map((a) => ({
    id: a.id,
    name: a.name,
    emoji: a.emoji,
    tagline: a.tagline,
    max_steps: a.max_steps,
    tools: a.tools === "*" ? "all" : a.tools.length,
  }));
}
