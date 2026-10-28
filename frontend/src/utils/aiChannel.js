/**
 * AI 通道口径与调用计量（大屏 CopilotPanel 与司机端 DriverCopilot 共用）。
 *
 * 为什么收在一处：通道标签的措辞是合规口径（本地=数据不出境，在线=提示词已离开本机），
 * 两端各写一份 `channelLabel()` 迟早会改一漏一，出现「同一件事两种说法」。
 *
 * 为什么要把通道放回界面上一行：之前为了去掉每条回复前的胶囊噪声，把溯源收进气泡 title，
 * 代价是屏幕上再没有「这次到底是不是本地跑的」的可见证据——而本地模型正是核心主张。
 * 折中是：气泡上不写，但面板底部**常驻一行当前通道 + 本次开销**，一处占位、可当场核，
 * 不再逐条重复。
 */
import axios from "axios";

/** 通道归属：local / online / cached / rule，供状态点着色。 */
export function channelKind(ch) {
  if (!ch) return "rule";
  if (ch.startsWith("local")) return "local";
  if (ch.startsWith("online")) return "online";
  if (ch === "cached") return "cached";
  return "rule";
}

/** 通道的完整中文口径（tooltip / 状态行都用它，不另设简写，避免两套说法）。 */
export function channelLabel(ch) {
  const kind = channelKind(ch);
  if (kind === "local") return "本地大模型";
  if (kind === "online") return "在线 API·非本地";
  if (kind === "cached") return "离线缓存文案";
  return "规则解析";
}

/** 把一次调用的开销翻成一行短文案；没走模型（规则兜底）时返回空串，不显示 0 tokens。 */
export function formatCost(cost) {
  if (!cost) return "";
  const ms = Number(cost.elapsedMs) || 0;
  const tokens = Number(cost.tokens) || 0;
  if (!ms && !tokens) return "";
  const secs = ms >= 1000 ? (ms / 1000).toFixed(1) + "s" : ms + "ms";
  return tokens ? `${tokens} tokens · ${secs}` : `耗时 ${secs}`;
}

/**
 * 拉进程累计量（/api/ai/metrics）。失败一律返回 null 而不是抛错——
 * 这行状态是附加信息，取不到时整段不显示即可，不能把对话功能一起标红。
 */
export async function fetchAiMetrics() {
  try {
    const { data } = await axios.get("/api/ai/metrics", { timeout: 8000 });
    return data && typeof data === "object" ? data : null;
  } catch (e) {
    return null;
  }
}

/** 累计量的一行人话：只报能核的数（token、峰值并发），单价没填时不提钱。 */
export function formatPool(m) {
  if (!m) return "";
  const tokens = Number(m.tokens) || 0;
  const parts = [];
  if (tokens) parts.push(`累计 ${tokens >= 10000 ? (tokens / 1000).toFixed(1) + "k" : tokens} tokens`);
  if (m.peakInFlight) parts.push(`峰值并发 ${m.peakInFlight}`);
  if (m.costConfigured && Number(m.costCny) > 0) parts.push(`≈¥${m.costCny}`);
  return parts.join(" · ");
}
