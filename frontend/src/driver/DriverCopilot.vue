<script setup>
/**
 * 司机端 AI 志宁对话舱：悬浮小球展开后的聊天面板。
 * 复用后端 /api/copilot/chat 的意图解析（本地大模型 / 在线 API / 规则兜底三通道），
 * 通道归属不逐条写在气泡上，而是气泡 title 可查 + 面板底部常驻一行状态（与大屏同一口径）。
 * 底部那行还带本次 token 与耗时，数据来自后端 usage 回包与 /api/ai/metrics，不是估的。
 * 把司机的一句话翻成一次受控动作：
 *  - navigate_to / plan_routes 这类需要改动司机端界面的，emit('command') 交给父组件执行
 *    （父组件据此设置终点并进入路线规划，AI 只出意图、真正的算路/导航仍走司机端既有链路）；
 *  - query_risk / query_customs / run_analysis 只读工具，后端已回数据，这里直接把结论展示出来。
 * 浅色主题，贴合司机端手机界面（与大屏深色 CopilotPanel 区分）。
 */
import { computed, nextTick, onMounted, ref } from "vue";
import axios from "axios";
import VoiceInputButton from "../components/VoiceInputButton.vue";
import {
  channelKind,
  channelLabel,
  formatCost,
  formatPool,
  fetchAiMetrics,
} from "../utils/aiChannel";

const props = defineProps({
  originId: { type: String, default: "NN" },
  destinationId: { type: String, default: "HN" },
  // 识别语言跟随司机端整机语言（中文 / 越南语）
  lang: { type: String, default: "zh-CN" },
});
// command：需要父组件落地的端侧动作（导航 / 规划）
const emit = defineEmits(["command"]);

const messages = ref([
  {
    role: "assistant",
    say: "我是 AI志宁。可以说：导航去河内 / 查一下天气 / 友谊关现在什么风险 / 各口岸通关时效。",
    channel: "rule",
  },
]);
const input = ref("");
const busy = ref(false);
const listEl = ref(null);

const QUICK = [
  "导航去河内",
  "查一下天气",
  "友谊关现在什么风险",
  "各口岸通关时效",
  "冷链货物要什么单证",
  "跑一下 AI 决策分析",
];

// 需要交给父组件执行的端侧动作工具
const ACTIONABLE = new Set(["navigate_to", "plan_routes"]);

// 通道口径与计量文案统一放在 utils/aiChannel.js，两端共用，避免两处各写一份后改一漏一
function channelTip(m) {
  return m.role === "assistant" && m.channel ? "指令解析来源：" + channelLabel(m.channel) : null;
}

// ---- 底部状态行：最近一次回复的通道与开销，以及进程累计量 ----
const lastChannel = ref("rule");
const lastCost = ref(null);
const pool = ref(null);
const costText = computed(() => formatCost(lastCost.value));
const poolText = computed(() => formatPool(pool.value));

async function refreshPool() {
  pool.value = await fetchAiMetrics();
}
onMounted(refreshPool);

async function scrollDown() {
  await nextTick();
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight;
}

async function send(text) {
  const msg = (text ?? input.value).trim();
  if (!msg || busy.value) return;
  input.value = "";
  messages.value.push({ role: "user", say: msg });
  busy.value = true;
  await scrollDown();
  try {
    const { data } = await axios.post(
      "/api/copilot/chat",
      { message: msg, originId: props.originId, destinationId: props.destinationId },
      { timeout: 90000 }
    );
    const item = {
      role: "assistant",
      say: data.say || "",
      channel: data.channel,
      tool: data.tool,
      summary: data.data && data.data.summary ? data.data.summary : "",
      // 知识库问答的出处（条目编号+标题），与大屏同一口径
      sources: data.data && Array.isArray(data.data.sources) ? data.data.sources : [],
    };
    messages.value.push(item);
    if (data.channel !== undefined) lastChannel.value = data.channel;
    lastCost.value = data.cost || null;
    refreshPool();
    if (ACTIONABLE.has(data.tool)) {
      emit("command", {
        tool: data.tool,
        args: data.args || {},
        destination: data.data && data.data.destination ? data.data.destination : (data.args && data.args.destination) || "",
      });
    }
  } catch (e) {
    messages.value.push({ role: "assistant", say: "AI志宁请求失败：" + (e.message || e), channel: null, error: true });
  } finally {
    busy.value = false;
    await scrollDown();
  }
}

// ---- 语音输入（长按说话）：中间结果上屏，松手直接发这一句 ----
function onVoiceInterim(text) {
  if (!busy.value) input.value = text;
}
async function onVoiceResult(text) {
  const said = (text || "").trim();
  if (!said) return;
  input.value = said;
  await send();
}
function onVoiceError(msg) {
  messages.value.push({ role: "assistant", say: "语音输入不可用：" + msg, channel: null, error: true });
  scrollDown();
}
</script>

<template>
  <section class="dcp">
    <div ref="listEl" class="dcp-list">
      <div v-for="(m, i) in messages" :key="i" class="dcp-msg" :class="m.role">
        <!-- 思考过程在答案之上：先「思考中」过程回话（say），再主气泡给结构化结论（summary） -->
        <div v-if="m.summary && m.say" class="dcp-think">思考中 · {{ m.say }}</div>
        <div class="dcp-bubble" :class="{ err: m.error }" :title="channelTip(m)">{{ m.summary || m.say }}</div>
        <div v-if="m.sources && m.sources.length" class="dcp-sources" title="本条回答由知识库检索增强产生，下列为实际命中的条目">
          依据：<span v-for="s in m.sources" :key="s.id" class="dcp-src">{{ s.id }} {{ s.title }}</span>
        </div>
      </div>
      <div v-if="busy" class="dcp-msg assistant">
        <div class="dcp-bubble thinking">解析执行中…</div>
      </div>
    </div>

    <div class="dcp-foot" title="本行是最近一次回复的解析来源与开销；长按任一条气泡可查那条自己的来源">
      <span class="dcp-dot" :class="channelKind(lastChannel)"></span>
      <span>{{ channelLabel(lastChannel) }}</span>
      <template v-if="costText">
        <span class="dcp-foot-sep">·</span>
        <span>本次 {{ costText }}</span>
      </template>
      <template v-if="poolText">
        <span class="dcp-foot-sep">·</span>
        <span class="dcp-foot-dim">{{ poolText }}</span>
      </template>
    </div>

    <div class="dcp-quick">
      <button v-for="q in QUICK" :key="q" class="dcp-chip" :disabled="busy" @click="send(q)">{{ q }}</button>
    </div>
    <div class="dcp-input-row">
      <input
        v-model="input"
        class="dcp-input"
        type="text"
        placeholder="说一句，如：导航去河内"
        :disabled="busy"
        @keyup.enter="send()"
      />
      <VoiceInputButton compact :lang="props.lang" :disabled="busy"
                        @result="onVoiceResult" @interim="onVoiceInterim" @error="onVoiceError" />
      <button class="dcp-send" :disabled="busy || !input.trim()" @click="send()">
        {{ busy ? "…" : "发送" }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.dcp { display: flex; flex-direction: column; gap: 8px; color: #1a1a2e; }
.dcp-list {
  max-height: min(42vh, 340px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dcp-msg { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.dcp-msg.user { align-items: flex-end; }
.dcp-bubble {
  max-width: 94%;
  padding: 9px 13px;
  border-radius: 14px;
  font-size: 15.5px;
  line-height: 1.6;
  background: #f1f4f9;
  border: 1px solid #e5eaf1;
  word-break: break-word;
}
.dcp-msg.user .dcp-bubble { background: linear-gradient(120deg, #6366f1, #4f7df7); color: #fff; border-color: transparent; }
.dcp-bubble.err { border-color: rgba(225, 29, 72, 0.5); color: #be123c; }
.dcp-bubble.thinking { opacity: 0.7; font-style: italic; }
/* 过程回话（“好的，正在查询…”）降级为思考轨迹：真正的答案已上提到主气泡，这里只作小字注释 */
.dcp-think {
  font-size: 12.5px;
  line-height: 1.5;
  color: #9ca3af;
  font-style: italic;
  padding: 0 13px;
  max-width: 94%;
  word-break: break-word;
}
/* RAG 出处行：比结论行更小更淡，但不得缺席——“引了哪几条”是检索增强可核验的部分 */
.dcp-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  align-items: center;
  padding: 2px 13px 0;
  max-width: 94%;
  font-size: 12px;
  color: #6b7280;
}
.dcp-src {
  padding: 1px 7px;
  border-radius: 8px;
  background: rgba(79, 125, 247, 0.1);
  border: 1px solid rgba(79, 125, 247, 0.25);
  color: #3b5bb5;
}
/* 底部状态行：元信息，比司机要看的内容小且淡，不抢规 */
.dcp-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  font-size: 12px;
  color: #6b7280;
  user-select: none;
}
.dcp-foot-dim { opacity: 0.8; }
.dcp-foot-sep { opacity: 0.5; }
.dcp-dot { width: 7px; height: 7px; border-radius: 50%; background: #9ca3af; flex: none; }
.dcp-dot.local { background: #16a34a; }
.dcp-dot.online { background: #d97706; }
.dcp-dot.cached { background: #0284c7; }
.dcp-quick { display: flex; flex-wrap: wrap; gap: 6px; }
.dcp-chip {
  font-size: 13px;
  padding: 5px 11px;
  border-radius: 14px;
  border: 1px solid #e1e7f0;
  background: #f7f9fc;
  color: #4f6ef7;
  cursor: pointer;
}
.dcp-chip:hover:not(:disabled) { border-color: #4f6ef7; background: #eef2ff; }
.dcp-chip:disabled { opacity: 0.5; cursor: not-allowed; }
.dcp-input-row { display: flex; gap: 8px; }
.dcp-input {
  flex: 1;
  min-width: 0;
  background: #f5f7fa;
  border: 1px solid #e1e7f0;
  border-radius: 10px;
  color: #1a1a2e;
  padding: 9px 12px;
  font-size: 15px;
}
.dcp-input:focus { outline: none; border-color: #4f6ef7; }
.dcp-send {
  border: none;
  border-radius: 10px;
  padding: 0 16px;
  background: linear-gradient(120deg, #6366f1, #4f7df7);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.dcp-send:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
