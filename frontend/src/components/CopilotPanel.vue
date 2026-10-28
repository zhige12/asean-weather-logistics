<script setup>
/**
 * 自然语言调度舱：收在右下角「AI志宁」悬浮小球里的对话框，把一句话翻成一次受控工具调用。
 * 与后端 CopilotService 的契约：
 *  - POST /api/copilot/chat    只读意图直接执行回数据；写意图回 pendingConfirm + preview
 *  - POST /api/copilot/execute 人点「确认执行」后才落地（AI 管分析、人管决策）
 * 解析通道（本地大模型 / 在线 API / 规则解析）不再逐条写在气泡上，而是：
 *  - 气泡 title 保留每条的溯源（悬停可查）；
 *  - 面板底部**常驻一行**当前通道 + 本次 token/耗时 + 累计量（/api/ai/metrics）。
 * 前者去噪，后者把「是不是本地跑的」这个核心主张留在屏幕上可当场核，不变成黑盒。
 */
import { computed, nextTick, onMounted, ref } from "vue";
import axios from "axios";
import VoiceInputButton from "./VoiceInputButton.vue";
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
});
// 写操作落地成功后通知大屏：刷新风险条/沙盘/日志（与沙盘手动注入同一联动路径）
const emit = defineEmits(["executed", "navigate"]);

const messages = ref([
  { role: "assistant", say: "我是 AI志宁。可以对我说：友谊关现在什么风险 / 规划去河内的候选路线 / 打开暴雨场景演练 / 模拟运河禁航。写操作会先请你确认。", channel: "rule" },
]);
const input = ref("");
const busy = ref(false);
// 待确认的写操作：{tool,args,preview}，确认后清空
const pending = ref(null);
const listEl = ref(null);

const QUICK = [
  "友谊关现在什么风险",
  "规划去河内的候选路线",
  "跑一下 AI 决策分析",
  "冷链货物通关要什么单证",
  "打开暴雨场景演练",
  "模拟运河禁航",
  "重置沙盘",
];

function channelTip(m) {
  return m.role === "assistant" && m.channel ? "指令解析来源：" + channelLabel(m.channel) : null;
}

// ---- 底部状态行：最近一次回复的通道与开销，以及进程累计量 ----
// 开场白是规则模板写的，所以初值就是 rule，不是空——空会让这一行在首屏默默缺位。
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
  pending.value = null;
  busy.value = true;
  await scrollDown();
  try {
    const { data } = await axios.post("/api/copilot/chat", {
      message: msg,
      originId: props.originId,
      destinationId: props.destinationId,
    }, { timeout: 90000 });
    const item = {
      role: "assistant",
      say: data.say || "",
      channel: data.channel,
      tool: data.tool,
      summary: data.data && data.data.summary ? data.data.summary : "",
      // RAG 的出处：知识库问答会把实际检索到的条目编号与标题带回来，
      // 没出处就说明这条回答不是检索增强产生的，界面上不得假装它有依据。
      sources: data.data && Array.isArray(data.data.sources) ? data.data.sources : [],
    };
    messages.value.push(item);
    // 状态行跟着本次回复更新；累计量顺手重拉一次（只在发完指令后拉，不做轮询骚扰）
    if (data.channel !== undefined) lastChannel.value = data.channel;
    lastCost.value = data.cost || null;
    refreshPool();
    // 导航意图：把抽取到的目的地透出给大屏，由大屏落到起终点输入并触发一次规划
    if (data.tool === "navigate_to" && data.data && data.data.destination) {
      emit("navigate", { destination: data.data.destination });
    }
    if (data.pendingConfirm) {
      pending.value = {
        tool: data.tool,
        args: data.args || {},
        preview: data.preview || data.say || "",
      };
    }
  } catch (e) {
    messages.value.push({ role: "assistant", say: "调度舱请求失败：" + (e.message || e), channel: null, error: true });
  } finally {
    busy.value = false;
    await scrollDown();
  }
}

async function confirmExec() {
  if (!pending.value || busy.value) return;
  const p = pending.value;
  pending.value = null;
  busy.value = true;
  await scrollDown();
  try {
    const { data } = await axios.post("/api/copilot/execute", {
      tool: p.tool,
      args: p.args,
      originId: props.originId,
      destinationId: props.destinationId,
    }, { timeout: 90000 });
    messages.value.push({
      role: "assistant",
      say: (data.ok ? "✅ " : "⚠ ") + (data.summary || "已执行"),
      channel: "rule",
      tool: p.tool,
    });
    if (data.ok) emit("executed", { tool: p.tool, data: data.data });
  } catch (e) {
    messages.value.push({ role: "assistant", say: "执行失败：" + (e.message || e), channel: null, error: true });
  } finally {
    busy.value = false;
    await scrollDown();
  }
}

function cancelPending() {
  if (pending.value) {
    messages.value.push({ role: "assistant", say: "已取消，未做任何改动。", channel: "rule" });
    pending.value = null;
  }
}

// ---- 语音输入：识别中的中间结果实时回显到输入框，松手即直接发送这一句 ----
function onVoiceInterim(text) {
  // 正在跑上一发指令时不去改输入框，避免覆盖调度员手改的内容
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
  <section class="copilot">
    <div ref="listEl" class="cp-list">
      <div v-for="(m, i) in messages" :key="i" class="cp-msg" :class="m.role">
        <!-- 思考过程在答案之上：先「思考中」过程回话（say），再主气泡给结构化结论（summary），先读过程后看结果 -->
        <div v-if="m.summary && m.say" class="cp-think">思考中 · {{ m.say }}</div>
        <div class="cp-bubble" :class="{ err: m.error }" :title="channelTip(m)">{{ m.summary || m.say }}</div>
        <div v-if="m.sources && m.sources.length" class="cp-sources" title="本条回答由知识库检索增强产生，下列为实际命中的条目">
          依据：<span v-for="s in m.sources" :key="s.id" class="cp-src">{{ s.id }} {{ s.title }}</span>
        </div>
      </div>
      <div v-if="busy" class="cp-msg assistant">
        <div class="cp-bubble thinking">解析执行中…</div>
      </div>
    </div>

    <!-- 底部常驻一行：当前解析通道 + 本次开销 + 累计量（溯源可见，但不逐条重复） -->
    <div class="cp-foot" title="本行是最近一次回复的解析来源与开销；悬停任一条气泡可查那条自己的来源">
      <span class="cp-dot" :class="channelKind(lastChannel)"></span>
      <span>{{ channelLabel(lastChannel) }}</span>
      <template v-if="costText">
        <span class="cp-foot-sep">·</span>
        <span>本次 {{ costText }}</span>
      </template>
      <template v-if="poolText">
        <span class="cp-foot-sep">·</span>
        <span class="cp-foot-dim">{{ poolText }}</span>
      </template>
    </div>

    <!-- 写操作确认条：AI 只给预览，落不落地由人决定 -->
    <div v-if="pending" class="cp-confirm">
      <span>待确认操作：<b>{{ pending.preview }}</b></span>
      <button class="btn tiny primary" :disabled="busy" @click="confirmExec">确认执行</button>
      <button class="btn tiny outline" :disabled="busy" @click="cancelPending">取消</button>
    </div>

    <div class="cp-quick">
      <button v-for="q in QUICK" :key="q" class="cp-chip" :disabled="busy" @click="send(q)">{{ q }}</button>
    </div>
    <div class="row" style="margin-top:6px">
      <input
        v-model="input"
        class="cp-input"
        type="text"
        placeholder="用一句话下指令，如：把友谊关降雨调到 90mm"
        :disabled="busy"
        @keyup.enter="send()"
      />
      <VoiceInputButton compact :disabled="busy" @result="onVoiceResult" @interim="onVoiceInterim" @error="onVoiceError" />
      <button class="btn primary" :disabled="busy || !input.trim()" @click="send()">
        {{ busy ? "…" : "发送" }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.copilot .cp-list {
  /* 字号变大后一行装不下那么多字了，列表高度跟着放宽（小屏上不会顶出面板） */
  max-height: min(44vh, 380px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}
.cp-msg { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.cp-msg.user { align-items: flex-end; }
.cp-bubble {
  max-width: 94%;
  padding: 9px 13px;
  border-radius: 12px;
  font-size: 15px;
  line-height: 1.6;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  word-break: break-word;
}
.cp-msg.user .cp-bubble { background: rgba(64, 158, 255, 0.18); border-color: rgba(64, 158, 255, 0.35); }
.cp-bubble.err { border-color: rgba(245, 108, 108, 0.5); }
.cp-bubble.thinking { opacity: 0.7; font-style: italic; }
/* 过程回话（“好的，正在查询…”）降级为思考轨迹：真正的答案已上提到主气泡，这里只作小字注释，比答案小且淡 */
.cp-think {
  font-size: 12.5px;
  line-height: 1.5;
  color: #7f8ba0;
  font-style: italic;
  padding: 0 13px;
  max-width: 94%;
  word-break: break-word;
}
/* RAG 出处行：比结论行更小、靠右不抢视线，但必须常驻——“引了哪几条”是 RAG 可核验的部分 */
.cp-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  align-items: center;
  padding: 2px 13px 0;
  max-width: 94%;
  font-size: 12px;
  color: #7f8ba0;
}
.cp-src {
  padding: 1px 7px;
  border-radius: 8px;
  background: rgba(99, 102, 241, 0.14);
  border: 1px solid rgba(129, 140, 248, 0.28);
  color: #a9b6f5;
}
/* 底部状态行：这是元信息，故意比结论行小且淡——分工是「大字给答案，小字给出处」 */
.cp-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  font-size: 12px;
  color: #8b95a5;
  margin: -2px 0 8px;
  user-select: none;
}
.cp-foot-dim { opacity: 0.75; }
.cp-foot-sep { opacity: 0.5; }
/* 本地=绿（数据不出境），在线=橙（提示词已离开本机，该警示不能只靠一行字）*/
.cp-dot { width: 7px; height: 7px; border-radius: 50%; background: #94a3b8; flex: none; }
.cp-dot.local { background: #22c55e; box-shadow: 0 0 6px rgba(34, 197, 94, 0.6); }
.cp-dot.online { background: #f59e0b; box-shadow: 0 0 6px rgba(245, 158, 11, 0.55); }
.cp-dot.cached { background: #38bdf8; }
.cp-confirm {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 6px 8px;
  border: 1px dashed rgba(230, 162, 60, 0.6);
  border-radius: 8px;
  color: #e6a23c;
  margin-bottom: 6px;
}
.cp-confirm span { flex: 1; }
.cp-quick { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.cp-chip {
  font-size: 12.5px;
  padding: 4px 10px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: transparent;
  color: #b8c1cc;
  cursor: pointer;
}
.cp-chip:hover:not(:disabled) { border-color: #409eff; color: #409eff; }
.cp-chip:disabled { opacity: 0.5; cursor: not-allowed; }
.cp-input {
  flex: 1;
  min-width: 0;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  color: #e5e9f0;
  padding: 8px 11px;
  font-size: 14.5px;
}
.cp-input:focus { outline: none; border-color: #409eff; }
</style>
