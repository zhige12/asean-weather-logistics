<script setup>
/**
 * 「长按说话」语音输入按钮：AI 志宁两端（调度大屏 + 司机端）共用。
 *
 * 交互：
 *  - 按住不放 → 开始识别，松手即把这句话发给 AI（与参考图「长按说话」一致）；
 *  - 点一下就走（按住 <250ms）→ 保持聆听，变成「点一下开始、再点一下结束」，
 *    照顾桌面鼠标党（按住拖一下就容易误松手）；
 *  - 识别中的中间结果通过 interim 事件实时回显，用户看得到自己说的字，不是对着空气讲。
 *
 * 通道由 utils/speechInput.js 决定（原生优先、浏览器降级）；两条都不可用时按钮置灰，
 * 并在 title 里如实说明原因——不做「点了没反应」的假功能。
 */
import { onBeforeUnmount, onMounted, ref } from "vue";
import {
  resolveSpeechChannel,
  startListening,
  stopListening,
  channelUnavailableText,
} from "../utils/speechInput";

const props = defineProps({
  lang: { type: String, default: "zh-CN" },
  disabled: { type: Boolean, default: false },
  // 紧凑模式：聊天输入行里用图标按钮，不占文字宽度
  compact: { type: Boolean, default: false },
});
const emit = defineEmits(["result", "interim", "error"]);

const listening = ref(false);
// 服务端通道专有：松手后录音已停、正在上传识别（无中间结果，靠这个态告知用户“在处理”）
const processing = ref(false);
const supported = ref(true);
const channel = ref("");
// 长按起始时刻：用普通变量就够，不需要响应式（只在手势回调里读写）
let holdStartAt = 0;
// 短按转成的「点一下开始/再点结束」模式
const toggleMode = ref(false);
const hint = ref("");

const channelName = () => (
  channel.value === "native" ? "系统语音识别"
    : channel.value === "server" ? "云端语音识别"
    : channel.value === "web" ? "浏览器语音识别" : "");

onMounted(async () => {
  const ch = await resolveSpeechChannel();
  supported.value = !!ch;
  channel.value = ch || "";
});

// 组件卸载时必须收尾，否则原生 start() 的 Promise 与监听器会泄漏到下一个页面
onBeforeUnmount(() => {
  if (listening.value) stopListening();
});

async function begin() {
  if (listening.value || processing.value || props.disabled) return;
  if (!supported.value) {
    const msg = channelUnavailableText();
    hint.value = msg;
    emit("error", msg);
    return;
  }
  hint.value = "";
  listening.value = true;
  toggleMode.value = false;
  try {
    const { text } = await startListening({
      lang: props.lang,
      onInterim: (t) => emit("interim", t),
      onProcessing: () => { processing.value = true; },
      onEnd: () => { listening.value = false; toggleMode.value = false; },
    });
    emit("result", String(text || "").trim());
  } catch (e) {
    const msg = (e && e.message) || "语音识别失败";
    if (msg !== "已取消" && msg !== "上一次识别还没结束") {
      hint.value = msg;
      emit("error", msg);
    }
  } finally {
    listening.value = false;
    processing.value = false;
    toggleMode.value = false;
  }
}

function onPointerDown(ev) {
  if (ev.button !== undefined && ev.button !== 0) return;
  ev.preventDefault();
  if (listening.value && toggleMode.value) {
    // 短按模式下的第二下：结束并发送
    toggleMode.value = false;
    stopListening();
    return;
  }
  if (!listening.value) {
    holdStartAt = Date.now();
    begin();
  }
}

function onPointerUp() {
  if (!listening.value) return;
  const held = Date.now() - holdStartAt;
  if (held >= 250) {
    // 正常长按：松手即结束本句
    stopListening();
  } else {
    // 手速太快：不掐断，转成点触模式继续聆听
    toggleMode.value = true;
  }
}

function onPointerCancel() {
  if (listening.value && !toggleMode.value) stopListening();
}

// 键盘无障碍：pointerdown 里 preventDefault() 会吃掉鼠标/触摸产生的 click，
// 所以进这里的只会是 Enter/Space 合成的 click（event.detail === 0）。
// 键盘没法定住不放，直接走「按一下开始、再按一下结束」。
function onClick(ev) {
  if (ev.detail !== 0) return;
  if (listening.value) {
    toggleMode.value = false;
    stopListening();
    return;
  }
  holdStartAt = Date.now();
  begin();
  toggleMode.value = true;
}

// 按住说话时不要让页面跟着滚动
function noop(e) { e.preventDefault(); }
</script>

<template>
  <span class="vb-wrap">
    <button
      class="vb"
      :class="{ listening: listening && !processing, processing, off: !supported, compact: props.compact }"
      :disabled="props.disabled"
      :title="!supported ? channelUnavailableText() : (processing ? '识别中，请稍候…' : (listening ? (toggleMode ? '正在聆听，点一下结束发送' : '松开手指即发送') : ('长按说话' + (channelName() ? ' · ' + channelName() : ''))))"
      @pointerdown="onPointerDown"
      @pointerup="onPointerUp"
      @pointercancel="onPointerCancel"
      @click="onClick"
      @contextmenu="noop"
      @dragstart="noop"
    >
      <svg v-if="!processing" class="vb-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="9" y="2.6" width="6" height="11.2" rx="3" />
        <path d="M5.2 11.6a6.8 6.8 0 0 0 13.6 0" />
        <path d="M12 18.4v3" />
        <path d="M8.5 21.4h7" />
      </svg>
      <span v-else class="vb-spin" aria-hidden="true"></span>
      <span v-if="!props.compact" class="vb-text">
        {{ !supported ? '不可说' : (processing ? '识别中…' : (listening ? (toggleMode ? '点一下结束' : '松开发送') : '长按说话')) }}
      </span>
      <span v-if="listening && !processing" class="vb-wave"><i></i><i></i><i></i></span>
    </button>
    <span v-if="hint" class="vb-hint">{{ hint }}</span>
  </span>
</template>

<style scoped>
.vb-wrap { display: inline-flex; flex-direction: column; align-items: center; gap: 3px; }
.vb {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #e1e7f0;
  background: #f7f9fc;
  color: #4f6ef7;
  border-radius: 12px;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  transition: background 0.15s, border-color 0.15s, color 0.15s, box-shadow 0.15s;
}
.vb.compact { padding: 8px 10px; }
.vb:hover:not(:disabled) { border-color: #4f6ef7; background: #eef2ff; }
.vb:disabled { opacity: 0.5; cursor: not-allowed; }
.vb.off { color: #a3adbd; background: #f4f5f7; border-color: #e8eaee; }
.vb.listening {
  color: #fff;
  background: linear-gradient(120deg, #6366f1, #ec4899);
  border-color: transparent;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.22);
}
.vb.processing {
  color: #4f6ef7;
  background: #eef2ff;
  border-color: #c7d2fe;
  cursor: progress;
}
.vb-ico { width: 17px; height: 17px; flex-shrink: 0; }
.vb-spin {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border: 2px solid #c7d2fe;
  border-top-color: #4f6ef7;
  border-radius: 50%;
  animation: vb-spin 0.7s linear infinite;
}
@keyframes vb-spin {
  to { transform: rotate(360deg); }
}
.vb-text { white-space: nowrap; }
.vb-wave { display: inline-flex; align-items: flex-end; gap: 2px; height: 14px; margin-left: 2px; }
.vb-wave i {
  width: 2px;
  height: 5px;
  border-radius: 2px;
  background: currentColor;
  animation: vb-wave 0.9s ease-in-out infinite;
}
.vb-wave i:nth-child(2) { animation-delay: 0.15s; }
.vb-wave i:nth-child(3) { animation-delay: 0.3s; }
@keyframes vb-wave {
  0%, 100% { height: 5px; opacity: 0.6; }
  50% { height: 13px; opacity: 1; }
}
.vb-hint {
  font-size: 11px;
  color: #c2410c;
  max-width: 220px;
  line-height: 1.4;
  text-align: center;
}
</style>
