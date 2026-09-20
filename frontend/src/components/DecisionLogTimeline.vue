<template>
  <section class="card log-card">
    <h3>
      决策日志
      <span class="route-state">{{ entries.length }} 条留痕</span>
      <button class="btn tiny outline refresh-btn" @click="load" title="刷新">↻</button>
    </h3>
    <div class="muted log-hint">熔断/恢复、沙盘调整、AI 分析、任务下发、确认、叫应升级，全程可回溯</div>

    <div v-if="entries.length" class="log-list">
      <div v-for="(e, i) in entries" :key="i" class="log-item" :class="'cat-' + e.category">
        <span class="log-time">{{ e.time }}</span>
        <span class="log-dot"></span>
        <div class="log-body">
          <div class="log-event">{{ e.event }}</div>
          <div class="log-detail">{{ e.detail }}</div>
        </div>
      </div>
    </div>
    <div v-else class="muted">暂无决策日志</div>
  </section>
</template>

<script setup>
import { onMounted, ref } from "vue";
import axios from "axios";

const entries = ref([]);

async function load() {
  try {
    const { data } = await axios.get("/api/outreach/log", { params: { limit: 60 } });
    entries.value = [...(data || [])].reverse();
  } catch (e) {
    /* 静默 */
  }
}

onMounted(load);
defineExpose({ load });
</script>

<style scoped>
.log-card {
  border: 1px solid rgba(255, 255, 255, 0.15);
}
.refresh-btn {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 8px;
}
.log-hint {
  font-size: 11px;
  margin-bottom: 8px;
}
.log-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 260px;
  overflow-y: auto;
}
.log-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 3px 0;
}
.log-time {
  font-size: 10px;
  color: #64748f;
  font-family: var(--mono, monospace);
  min-width: 52px;
  padding-top: 2px;
}
.log-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
  background: #64748f;
}
.cat-FUSE .log-dot { background: #e11d48; }
.cat-UNFUSE .log-dot { background: #22a35a; }
.cat-AGENT .log-dot { background: #7c5cff; }
.cat-DISPATCH .log-dot { background: #4f6df5; }
.cat-CONFIRM .log-dot { background: #22a35a; }
.cat-ESCALATE .log-dot { background: #d97706; }
.cat-CONFIG .log-dot { background: #8b5cf6; }
.cat-WEATHER .log-dot { background: #79c0ff; }
.log-event {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-h, #1c2a44);
}
.log-detail {
  font-size: 11px;
  color: #64748f;
  line-height: 1.5;
}
</style>
