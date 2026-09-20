<template>
  <section class="card outreach-card">
    <h3>
      多角色触达 · 分级叫应
      <span v-if="status.total" class="route-state" :class="status.pending === 0 ? 'ok' : ''">
        {{ status.confirmed }}/{{ status.total }} 已确认
      </span>
    </h3>
    <div class="muted outreach-hint">
      从预警生成到确认反馈的闭环：未确认的自动升级触达方式（语音外呼）
    </div>

    <div v-if="!status.total" class="muted empty-hint">
      在上方「AI 决策分析」中选择方案并点击「确认切换方案」，任务变更指令将下发至各角色。
    </div>

    <template v-else>
      <!-- 角色消息卡（司机含中越双语） -->
      <div class="msg-tabs">
        <button
          v-for="t in roleTargets"
          :key="t.id"
          class="msg-tab"
          :class="{ active: activeTargetId === t.id, confirmed: t.confirmed, escalated: t.escalated && !t.confirmed }"
          @click="selectTarget(t.id)"
        >
          {{ t.name }}
          <span v-if="t.confirmed">✅</span>
          <span v-else-if="t.escalated" class="esc-dot" title="已升级语音外呼">📞</span>
        </button>
      </div>

      <div v-if="activeMessage" class="msg-box">
        <div class="msg-head">
          <span class="msg-channel">{{ activeMessage.channel }}</span>
          <span class="msg-state" :class="activeMessage.confirmed ? 'ok' : 'pending'">
            {{ activeMessage.confirmed ? "已确认" : activeMessage.escalated ? "语音外呼中…" : "待确认" }}
          </span>
        </div>
        <pre class="msg-text">{{ showVi && activeMessage.messageVi ? activeMessage.messageVi : activeMessage.message }}</pre>
        <div class="msg-actions">
          <button v-if="activeMessage.messageVi" class="btn tiny outline" @click="showVi = !showVi">
            {{ showVi ? "🇨🇳 中文" : "🇻🇳 Tiếng Việt" }}
          </button>
          <button
            v-if="activeMessage.messageVi"
            class="btn tiny outline"
            :disabled="speaking"
            @click="speakVi"
            title="本地 TTS 越南语语音播报（离线）"
          >
            {{ speaking ? "播报中…" : "🔊 越南语播报" }}
          </button>
          <button
            v-if="!activeMessage.confirmed && canConfirm(activeMessage.role)"
            class="btn tiny confirm-btn"
            @click="confirm(activeMessage.id)"
          >
            确认接收
          </button>
        </div>
      </div>

      <!-- 触达状态总表 -->
      <div class="status-table">
        <div class="status-row head">
          <span>角色</span><span>送达</span><span>确认</span><span>通道</span>
        </div>
        <div v-for="t in status.targets" :key="t.id" class="status-row" :class="{ escalated: t.escalated && !t.confirmed }">
          <span class="st-name">{{ t.name }}</span>
          <span>{{ t.delivered ? "✅" : "—" }}</span>
          <span :class="{ pending: !t.confirmed }">{{ t.confirmed ? "✅" : t.escalated ? "📞 二次呼叫" : "⏳" }}</span>
          <span class="st-channel">{{ t.channel }}</span>
        </div>
      </div>

      <div class="row">
        <button class="btn tiny muted" @click="reset">清空触达批次</button>
        <span class="muted tiny-hint">未确认目标 {{ escalateCountdown }}s 后自动升级语音外呼</span>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from "vue";
import axios from "axios";

const props = defineProps({
  status: { type: Object, default: () => ({ targets: [], total: 0, confirmed: 0, pending: 0 }) },
});
const emit = defineEmits(["refresh"]);

const activeTargetId = ref("");
const activeMessage = ref(null);
const showVi = ref(false);
const speaking = ref(false);
const escalateCountdown = ref(8);

// 主要角色（非百姓群体）放消息页签
const roleTargets = computed(() =>
  (props.status.targets || []).filter((t) => t.role !== "PUBLIC")
);

watch(
  () => props.status.targets,
  (list) => {
    if (!list || !list.length) {
      activeTargetId.value = "";
      activeMessage.value = null;
      return;
    }
    // 自动选中第一个未确认的角色目标（演示动线自然落在司机身上）
    if (!activeTargetId.value || !list.find((t) => t.id === activeTargetId.value)) {
      const first = roleTargets.value.find((t) => !t.confirmed) || roleTargets.value[0];
      if (first) selectTarget(first.id);
    }
    // 同步刷新已打开消息的确认状态
    if (activeTargetId.value && activeMessage.value) {
      const cur = list.find((t) => t.id === activeTargetId.value);
      if (cur) {
        activeMessage.value = { ...activeMessage.value, confirmed: cur.confirmed, escalated: cur.escalated, channel: cur.channel };
      }
    }
  },
  { deep: true }
);

async function selectTarget(id) {
  activeTargetId.value = id;
  showVi.value = false;
  try {
    const { data } = await axios.get("/api/outreach/message", { params: { targetId: id } });
    activeMessage.value = data;
  } catch (e) {
    console.error("load message failed", e);
  }
}

function canConfirm(role) {
  return role === "DRIVER" || role === "SHIPOWNER";
}

async function confirm(id) {
  try {
    await axios.post("/api/outreach/confirm", { targetId: id });
    emit("refresh");
  } catch (e) {
    console.error("confirm failed", e);
  }
}

async function reset() {
  try {
    await axios.post("/api/outreach/reset");
    activeMessage.value = null;
    activeTargetId.value = "";
    emit("refresh");
  } catch (e) {
    console.error("reset failed", e);
  }
}

// 越南语 TTS：优先 Capacitor 本地离线 TTS，降级浏览器 speechSynthesis
async function speakVi() {
  const text = activeMessage.value?.messageVi;
  if (!text || speaking.value) return;
  speaking.value = true;
  try {
    const { TextToSpeech } = await import("@capacitor-community/text-to-speech");
    await TextToSpeech.speak({ text, lang: "vi-VN", rate: 1.0 });
  } catch {
    try {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = "vi-VN";
      u.onend = () => (speaking.value = false);
      speechSynthesis.speak(u);
      return;
    } catch (e) {
      console.error("tts failed", e);
    }
  }
  speaking.value = false;
}

// 叫应倒计时（与后端编排 8s 升级同步的展示用）
let timer = null;
watch(
  () => props.status.dispatchedAt,
  (ts) => {
    clearInterval(timer);
    if (!ts) return;
    const update = () => {
      const left = 8 - Math.floor((Date.now() - ts) / 1000);
      escalateCountdown.value = Math.max(0, left);
      if (left <= 0) clearInterval(timer);
    };
    update();
    timer = setInterval(update, 1000);
  }
);
onUnmounted(() => clearInterval(timer));
</script>

<style scoped>
.outreach-card {
  border: 1px solid rgba(217, 119, 6, 0.35);
}
.outreach-hint {
  font-size: 11px;
  margin-bottom: 8px;
}
.empty-hint {
  font-size: 12px;
  line-height: 1.8;
}
.msg-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.msg-tab {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(255, 255, 255, 0.04);
  color: #5b6b85;
  cursor: pointer;
}
.msg-tab.active {
  border-color: #4f6df5;
  color: #4f6df5;
}
.msg-tab.confirmed {
  border-color: rgba(22, 163, 74, 0.5);
}
.msg-tab.escalated {
  border-color: rgba(225, 29, 72, 0.6);
  animation: blink 1s infinite alternate;
}
@keyframes blink {
  from { box-shadow: 0 0 0 rgba(225, 29, 72, 0); }
  to { box-shadow: 0 0 8px rgba(225, 29, 72, 0.6); }
}
.esc-dot {
  font-size: 10px;
}
.msg-box {
  border: 1px solid rgba(30, 50, 90, 0.14);
  border-radius: 10px;
  padding: 10px;
  background: rgba(255,255,255,0.60);
}
.msg-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}
.msg-channel {
  font-size: 11px;
  color: #64748f;
}
.msg-state {
  font-size: 11px;
  font-weight: 700;
}
.msg-state.ok {
  color: #16a34a;
}
.msg-state.pending {
  color: #d97706;
}
.msg-text {
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  max-height: 260px;
  overflow-y: auto;
  color: var(--text-h, #1c2a44);
  font-family: inherit;
}
.msg-actions {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.confirm-btn {
  background: #1fa84a;
  color: #fff;
}
.status-table {
  margin-top: 12px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 220px;
  overflow-y: auto;
}
.status-row {
  display: grid;
  grid-template-columns: 1fr 40px 84px 76px;
  font-size: 11px;
  align-items: center;
}
.status-row.head {
  color: #64748f;
  font-weight: 600;
}
.status-row.escalated .st-name {
  color: #e11d48;
}
.st-name {
  color: var(--text-h, #1c2a44);
}
.st-channel {
  color: #64748f;
}
.pending {
  color: #d97706;
}
.tiny-hint {
  font-size: 10px;
  align-self: center;
}
.btn.tiny {
  font-size: 11px;
  padding: 3px 8px;
}
</style>
