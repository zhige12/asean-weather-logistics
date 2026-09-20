<template>
  <section class="card task-card">
    <h3>
      公司派单
      <span class="task-state" :class="stateClass">{{ stateText }}</span>
    </h3>
    <div class="muted note">
      派单后司机端自动从「普通导航模式」切到「物流任务模式」，
      车牌、货物、起终点以派单为准，司机不再手填。
    </div>

    <div class="form-grid">
      <label class="fld">
        <span class="fld-label">车牌</span>
        <input v-model="form.plate" class="fld-input" placeholder="桂A·D12345" />
      </label>
      <label class="fld">
        <span class="fld-label">司机</span>
        <input v-model="form.driverName" class="fld-input" placeholder="阿明" />
      </label>
      <label class="fld">
        <span class="fld-label">货物</span>
        <select v-model="form.cargoType" class="fld-input">
          <option v-for="(c, key) in cargoTypes" :key="key" :value="key">{{ c.name }}</option>
        </select>
      </label>
      <label class="fld">
        <span class="fld-label">重量(t)</span>
        <input v-model.number="form.weightT" class="fld-input" type="number" min="1" step="1" />
      </label>
      <label class="fld">
        <span class="fld-label">起点</span>
        <select v-model="form.originId" class="fld-input">
          <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.name }}</option>
        </select>
      </label>
      <label class="fld">
        <span class="fld-label">终点</span>
        <select v-model="form.destinationId" class="fld-input">
          <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.name }}</option>
        </select>
      </label>
    </div>

    <div class="row actions">
      <button class="btn primary" :disabled="busy" @click="dispatch">
        {{ busy ? "派单中…" : "📋 派单给司机" }}
      </button>
      <button v-if="task" class="btn outline" :disabled="busy" @click="reset">撤销派单</button>
    </div>

    <div v-if="task" class="task-detail">
      <div class="detail-line">
        <b>{{ task.taskId }}</b> · {{ task.plate }} · {{ task.driverName }}承运 ·
        {{ task.cargoName }} {{ task.weightT }}t（{{ task.temp }}）
      </div>
      <div class="detail-line muted">
        {{ task.originId }} → {{ task.destinationId }} · 时限 {{ task.deadline }}
      </div>
      <div class="detail-line" :class="task.status === 'ACCEPTED' ? 'ok' : 'wait'">
        {{ task.status === "ACCEPTED" ? "✅ 司机已接单" : "⏳ 已派单，等待司机接单" }}
        · 越方接力司机 {{ task.driverNameVn }}
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import axios from "axios";

const props = defineProps({
  // 由 App.vue 经 SSE（task-assigned）实时传入；null 表示尚未取到
  taskStatus: { type: Object, default: null },
});
const emit = defineEmits(["refresh"]);

// 面板可选起终点：只列演示确定存在的节点，避免编造路网 ID
const nodes = [
  { id: "NN", name: "南宁（NN）" },
  { id: "LJ", name: "南宁港六景作业区（LJ）" },
  { id: "HN", name: "河内（HN）" },
  { id: "PX", name: "凭祥（PX）" },
];

const form = reactive({
  plate: "桂A·D12345",
  driverName: "阿明",
  cargoType: "DRAGON_FRUIT",
  weightT: 18,
  originId: "NN",
  destinationId: "HN",
});
const cargoTypes = ref({ DRAGON_FRUIT: { name: "冷链火龙果" }, ELECTRONICS: { name: "电子元件" } });
const busy = ref(false);
const localTask = ref(null);

// 本地兜底 + 父组件 SSE 推送，两者取先到者
const task = computed(() => props.taskStatus?.task || localTask.value);
const stateText = computed(() => {
  if (!task.value) return "未派单 · 司机端为普通导航模式";
  return task.value.status === "ACCEPTED" ? "已接单" : "已派单 · 待接单";
});
const stateClass = computed(() => {
  if (!task.value) return "";
  return task.value.status === "ACCEPTED" ? "ok" : "wait";
});

async function loadConfig() {
  try {
    const { data } = await axios.get("/api/platform/config");
    if (data?.cargoTypes) cargoTypes.value = data.cargoTypes;
  } catch (e) {
    /* 静默：退回内置货物类型 */
  }
}

async function refresh() {
  try {
    const { data } = await axios.get("/api/task/current");
    localTask.value = data?.task || null;
  } catch (e) {
    /* 静默 */
  }
}

async function dispatch() {
  busy.value = true;
  try {
    const { data } = await axios.post("/api/task/dispatch", { ...form });
    localTask.value = data?.task || null;
    emit("refresh", data);
  } catch (e) {
    console.error("task dispatch failed", e);
  } finally {
    busy.value = false;
  }
}

async function reset() {
  busy.value = true;
  try {
    const { data } = await axios.post("/api/task/reset");
    localTask.value = data?.task || null;
    emit("refresh", data);
  } catch (e) {
    console.error("task reset failed", e);
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  loadConfig();
  refresh();
});

defineExpose({ refresh });
</script>

<style scoped>
.task-card {
  border: 1px solid rgba(79, 109, 245, 0.35);
}
.note {
  font-size: 11px;
  line-height: 1.7;
  margin-bottom: 10px;
}
.task-state {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 600;
  background: rgba(30, 50, 90, 0.1);
  color: #5b6b85;
}
.task-state.wait {
  background: rgba(217, 119, 6, 0.18);
  color: #d97706;
}
.task-state.ok {
  background: rgba(22, 163, 74, 0.18);
  color: #16a34a;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.fld {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.fld-label {
  font-size: 11px;
  color: #5b6b85;
}
.fld-input {
  width: 100%;
  padding: 5px 8px;
  border: 1px solid rgba(30, 50, 90, 0.18);
  border-radius: 7px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--text-h, #1c2a44);
}
.actions {
  margin-top: 10px;
}
.task-detail {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
}
.detail-line {
  font-size: 12px;
  line-height: 1.7;
  color: #33415c;
}
.detail-line.ok {
  color: #16a34a;
  font-weight: 600;
}
.detail-line.wait {
  color: #d97706;
  font-weight: 600;
}
</style>