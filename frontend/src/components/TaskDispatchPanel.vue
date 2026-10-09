<template>
  <section class="card task-card">
    <h3>
      公司派单
      <span class="task-state" :class="stateClass">{{ stateText }}</span>
    </h3>
    <div class="muted note">
      派单分两步：先填单并触发开始导航前的 AI 气象决策分析（「AI 决策分析」面板会给出多条路线与六维对比），
      再在该面板的『开始导航派单』模板里选一条路线派单给司机；司机端收到后可预览订单、接单自动按选定路线导航或拒单退回普通导航。
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

    <!-- 触发出车前 AI 气象分析：路线比选与派单入口在「AI 决策分析」面板 -->
    <div class="row actions">
      <button class="btn primary" @click="analyze">
        <!-- 细线放大镜：不用 emoji（各系统渲染不一致），stroke 用 currentColor 跟随按钮文字色 -->
        <svg class="btn-icon" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="7" />
          <line x1="15.9" y1="15.9" x2="21" y2="21" />
        </svg>
        AI 气象分析并规划路线
      </button>
      <button v-if="task" class="btn outline" :disabled="busy" @click="reset">撤销派单</button>
    </div>

    <!-- 分析已触发：路线比选与派单入口在「AI 决策分析」面板的『开始导航派单』模板里 -->
    <div v-if="analyzed" class="analyze-box">
      <div class="analyze-head">
        <span>出车前 AI 气象分析已触发</span>
        <span class="muted od-tag">{{ form.originId }} → {{ form.destinationId }}</span>
      </div>
      <div class="analyze-summary">
        多路线候选与六维对比已生成在「AI 决策分析 · 多智能体协同」面板。
        请在其中『开始导航派单』模板里选一条路线，点击「确认选择此路线并派单给司机」。
      </div>
    </div>

    <div v-if="task" class="task-detail">
      <div class="detail-line">
        <b>{{ task.taskId }}</b> · {{ task.plate }} · {{ task.driverName }}承运 ·
        {{ task.cargoName }} {{ task.weightT }}t（{{ task.temp }}）
      </div>
      <div class="detail-line muted">
        {{ task.originId }} → {{ task.destinationId }} · 时限 {{ task.deadline }}
      </div>
      <div v-if="task.routeLabel" class="detail-line muted">
        已下发路线：{{ task.routeLabel }}<span v-if="task.hazardProbability >= 0"> · 灾害概率 {{ task.hazardProbability }}%</span>
      </div>
      <div class="detail-line" :class="statusClass">
        {{ statusLine }}
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
const emit = defineEmits(["refresh", "analyze"]);

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
// 是否已触发出车前 AI 分析（触发后提示路线比选与派单入口）
const analyzed = ref(false);

// 本地兜底 + 父组件 SSE 推送，两者取先到者
const task = computed(() => props.taskStatus?.task || localTask.value);
const stateText = computed(() => {
  if (!task.value) return "未派单 · 司机端为普通导航模式";
  if (task.value.status === "ACCEPTED") return "已接单";
  if (task.value.status === "REJECTED") return "司机已拒单";
  return "已派单 · 待接单";
});
const stateClass = computed(() => {
  if (!task.value) return "";
  if (task.value.status === "ACCEPTED") return "ok";
  if (task.value.status === "REJECTED") return "bad";
  return "wait";
});
// 任务详情区的状态行（含拒单）
const statusLine = computed(() => {
  const s = task.value?.status;
  if (s === "ACCEPTED") return "司机已接单";
  if (s === "REJECTED") return "司机已拒单，可改派或撤销";
  return "已派单，等待司机接单";
});
const statusClass = computed(() => {
  const s = task.value?.status;
  if (s === "ACCEPTED") return "ok";
  if (s === "REJECTED") return "bad";
  return "wait";
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

// 触发出车前 AI 分析：多智能体链路（常态模板）在「AI 决策分析」面板完成，
// 路线比选与派单入口也在那里；这里只把订单信息递上去
function analyze() {
  emit("analyze", { ...form });
  analyzed.value = true;
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
.btn-icon {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  vertical-align: -2px;
  margin-right: 4px;
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
.task-state.bad {
  background: rgba(220, 38, 38, 0.16);
  color: #dc2626;
}
.analyze-box {
  margin-top: 4px;
}
.analyze-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: #33415c;
  margin-bottom: 6px;
}
.od-tag {
  font-weight: 400;
  font-size: 11px;
}
.analyze-summary {
  font-size: 11px;
  line-height: 1.7;
  color: #475569;
  background: rgba(79, 109, 245, 0.07);
  border: 1px solid rgba(79, 109, 245, 0.18);
  border-radius: 8px;
  padding: 7px 9px;
  margin-bottom: 8px;
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
.detail-line.bad {
  color: #dc2626;
  font-weight: 600;
}
</style>