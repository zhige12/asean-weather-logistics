<script setup>
/**
 * 自然语言调度舱：大屏侧边栏里的对话框，把一句话翻成一次受控工具调用。
 * 与后端 CopilotService 的契约：
 *  - POST /api/copilot/chat    只读意图直接执行回数据；写意图回 pendingConfirm + preview
 *  - POST /api/copilot/execute 人点「确认执行」后才落地（AI 管分析、人管决策）
 * 解析通道如实标注：本地大模型 / 在线 API / 规则解析（模型不可用时的离线兜底），不冒充。
 */
import { nextTick, ref } from "vue";
import axios from "axios";

const props = defineProps({
  originId: { type: String, default: "NN" },
  destinationId: { type: String, default: "HN" },
});
// 写操作落地成功后通知大屏：刷新风险条/沙盘/日志（与沙盘手动注入同一联动路径）
const emit = defineEmits(["executed"]);

const messages = ref([
  { role: "assistant", say: "我是调度舱。可以问我：友谊关现在什么风险 / 规划去河内的候选路线 / 打开暴雨场景演练 / 模拟运河禁航。写操作会先请你确认。", channel: "rule" },
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
  "打开暴雨场景演练",
  "模拟运河禁航",
  "重置沙盘",
];

function channelLabel(ch) {
  if (!ch) return "规则解析";
  if (ch.startsWith("local")) return "本地大模型";
  if (ch.startsWith("online")) return "在线 API·非本地";
  if (ch === "cached") return "离线缓存文案";
  return "规则解析";
}
function channelClass(ch) {
  if (!ch) return "ch-rule";
  if (ch.startsWith("local")) return "ch-local";
  if (ch.startsWith("online")) return "ch-online";
  if (ch === "cached") return "ch-cached";
  return "ch-rule";
}

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
    };
    messages.value.push(item);
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
</script>

<template>
  <section class="card copilot">
    <h3>
      自然语言调度舱
      <span class="route-state">对话式</span>
    </h3>
    <div ref="listEl" class="cp-list">
      <div v-for="(m, i) in messages" :key="i" class="cp-msg" :class="m.role">
        <span v-if="m.channel && m.role === 'assistant'" class="cp-ch" :class="channelClass(m.channel)"
              :title="'指令解析来源：' + channelLabel(m.channel)">{{ channelLabel(m.channel) }}</span>
        <div class="cp-bubble" :class="{ err: m.error }">{{ m.say }}</div>
        <div v-if="m.summary" class="cp-summary">{{ m.summary }}</div>
      </div>
      <div v-if="busy" class="cp-msg assistant">
        <div class="cp-bubble thinking">解析执行中…</div>
      </div>
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
      <button class="btn primary" :disabled="busy || !input.trim()" @click="send()">
        {{ busy ? "…" : "发送" }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.copilot .cp-list {
  max-height: 260px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.cp-msg { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; }
.cp-msg.user { align-items: flex-end; }
.cp-bubble {
  max-width: 92%;
  padding: 6px 10px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  word-break: break-word;
}
.cp-msg.user .cp-bubble { background: rgba(64, 158, 255, 0.18); border-color: rgba(64, 158, 255, 0.35); }
.cp-bubble.err { border-color: rgba(245, 108, 108, 0.5); }
.cp-bubble.thinking { opacity: 0.7; font-style: italic; }
.cp-ch {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: #9aa4b2;
}
.cp-ch.ch-local { color: #67c23a; border-color: rgba(103, 194, 58, 0.5); }
.cp-ch.ch-online { color: #e6a23c; border-color: rgba(230, 162, 60, 0.5); }
.cp-ch.ch-cached { color: #909399; border-color: rgba(144, 147, 153, 0.5); }
.cp-ch.ch-rule { color: #409eff; border-color: rgba(64, 158, 255, 0.5); }
.cp-summary { font-size: 11px; color: #8b95a5; padding-left: 2px; }
.cp-confirm {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 6px 8px;
  border: 1px dashed rgba(230, 162, 60, 0.6);
  border-radius: 8px;
  color: #e6a23c;
  margin-bottom: 6px;
}
.cp-confirm span { flex: 1; }
.cp-quick { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.cp-chip {
  font-size: 11px;
  padding: 3px 8px;
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
  padding: 7px 10px;
  font-size: 13px;
}
.cp-input:focus { outline: none; border-color: #409eff; }
</style>
