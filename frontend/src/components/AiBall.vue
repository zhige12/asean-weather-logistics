<script setup>
/**
 * AI 志宁悬浮小球：把「自然语言调度舱 / 司机端语音助手」收进一颗可点开的圆球。
 *  - 圆球用「AI」字样 + 星芒（无人像头像），下方标注「AI志宁」；
 *  - 点一下展开对话面板（面板内容由默认插槽提供，两端各自放入自己的聊天组件）；
 *  - pos='fixed' 用于调度大屏（相对视口右下角），pos='absolute' 用于司机端（相对 .phone 容器）。
 * 只负责「球 + 展开外壳」，不掺和具体指令逻辑，保持与两端聊天解耦。
 */
import { ref } from "vue";

const props = defineProps({
  label: { type: String, default: "AI志宁" },
  // fixed：大屏悬浮于视口右下；absolute：司机端悬浮于手机容器右下
  pos: { type: String, default: "fixed" },
  right: { type: String, default: "18px" },
  bottom: { type: String, default: "22px" },
  // 展开面板宽度（手机窄列需收窄）
  panelWidth: { type: String, default: "360px" },
});

const open = ref(false);
function toggle() {
  open.value = !open.value;
}
function close() {
  open.value = false;
}

defineExpose({
  // 供宿主页面主动弹开/收起（如司机端搜索框那个麦克风图标就指向 show()）
  show: () => { open.value = true; },
  hide: close,
  toggle,
});
</script>

<template>
  <div class="ai-fab" :class="pos" :style="{ right: props.right, bottom: props.bottom }">
    <!-- 展开的对话面板：点空白处或右上角关闭收起 -->
    <transition name="ai-pop">
      <div v-show="open" class="ai-panel" :style="{ width: props.panelWidth }">
        <div class="ai-panel-head">
          <span class="ai-badge-sm">AI</span>
          <span class="ai-panel-title">{{ props.label }}</span>
          <button class="ai-panel-close" title="收起" @click="close">✕</button>
        </div>
        <div class="ai-panel-body">
          <!-- 默认插槽：调度大屏放 CopilotPanel，司机端放 DriverCopilot -->
          <slot />
        </div>
      </div>
    </transition>

    <!-- 悬浮小球：AI 字样 + 星芒，无人像；下方「AI志宁」标签 -->
    <button class="ai-ball" :class="{ active: open }" :title="props.label" @click="toggle">
      <span class="ai-ball-orb">
        <span class="ai-ball-text">AI</span>
        <svg class="ai-ball-spark" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 2c.5 4.6 2.4 6.5 7 7-4.6.5-6.5 2.4-7 7-.5-4.6-2.4-6.5-7-7 4.6-.5 6.5-2.4 7-7Z"/>
        </svg>
      </span>
      <span class="ai-ball-label">{{ props.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.ai-fab {
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}
.ai-fab.fixed { position: fixed; }
.ai-fab.absolute { position: absolute; }

/* ---- 小球 ---- */
.ai-ball {
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}
.ai-ball-orb {
  position: relative;
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: radial-gradient(circle at 32% 28%, #7c8cff 0%, #4f6ef7 42%, #6d3ff0 100%);
  box-shadow: 0 8px 22px rgba(79, 70, 229, 0.45), inset 0 1px 2px rgba(255, 255, 255, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.ai-ball:hover .ai-ball-orb { transform: scale(1.06); }
.ai-ball:active .ai-ball-orb { transform: scale(0.96); }
.ai-ball.active .ai-ball-orb {
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.28), 0 8px 22px rgba(79, 70, 229, 0.5);
}
.ai-ball-text {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0.5px;
  color: #fff;
  line-height: 1;
  text-shadow: 0 1px 3px rgba(20, 20, 60, 0.35);
}
.ai-ball-spark {
  position: absolute;
  top: 8px;
  right: 9px;
  width: 14px;
  height: 14px;
  fill: #fff;
  filter: drop-shadow(0 0 3px rgba(255, 255, 255, 0.8));
  animation: ai-spark 2.4s ease-in-out infinite;
}
@keyframes ai-spark {
  0%, 100% { opacity: 0.55; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.12); }
}
.ai-ball-label {
  font-size: 12px;
  font-weight: 700;
  color: #4f46e5;
  background: rgba(255, 255, 255, 0.86);
  border-radius: 999px;
  padding: 1px 9px;
  box-shadow: 0 2px 8px rgba(12, 26, 45, 0.14);
}

/* ---- 展开面板 ---- */
.ai-panel {
  max-width: calc(100vw - 24px);
  /* 聊天字号调大后面板变高，小笔记本（768px 高）上不能顶出视口：整体封顶，超出部分由 body 滚动 */
  max-height: calc(100vh - 110px);
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 18px 46px rgba(12, 26, 45, 0.28);
  overflow: hidden;
  border: 1px solid rgba(99, 102, 241, 0.16);
}
.ai-panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: linear-gradient(120deg, #6366f1 0%, #38bdf8 60%, #a855f7 100%);
  color: #fff;
}
.ai-badge-sm {
  font-size: 12px;
  font-weight: 800;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 6px;
  padding: 1px 6px;
}
.ai-panel-title {
  flex: 1;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.ai-panel-close {
  border: none;
  background: transparent;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  width: 26px;
  height: 26px;
  border-radius: 8px;
}
.ai-panel-close:hover { background: rgba(255, 255, 255, 0.18); }
.ai-panel-body { padding: 10px 12px; overflow-y: auto; }

/* 大屏深色主题下，插槽里的 CopilotPanel 本身就是深色卡片，这里给个深色底避免白边突兀 */
.ai-fab.fixed .ai-panel { background: #12161f; border-color: rgba(255, 255, 255, 0.08); }
.ai-fab.fixed .ai-panel-body { color: #e5e9f0; }

/* 展开/收起动画 */
.ai-pop-enter-active, .ai-pop-leave-active {
  transition: opacity 0.18s ease, transform 0.22s cubic-bezier(0.2, 0.9, 0.3, 1.1);
  transform-origin: bottom right;
}
.ai-pop-enter-from, .ai-pop-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.92);
}
</style>
