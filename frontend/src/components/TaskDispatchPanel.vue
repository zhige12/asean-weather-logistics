<template>
  <section class="card task-card">
    <h3>
      公司派单
      <span class="task-state" :class="stateClass">{{ stateText }}</span>
    </h3>
    <div class="muted note">
      派单一步入口：填好单后点「进入路线规划」——它会先确认起终点、规划主线与多路线候选，再触发出车前的 AI 气象决策分析
      （「AI 决策分析」面板给出多条路线与六维对比），随后在该面板的『开始导航派单』模板里选一条路线派单给司机；
      司机端收到后可预览订单、接单自动按选定路线导航或拒单退回普通导航。
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
    </div>

    <!-- 起终点输入框与地图选点/清空/确认规划按钮：由 App.vue 经 route-picker 插槽注入
         （内容编译于父作用域，PlaceInput 的 ref 与相关方法照常工作） -->
    <slot name="route-picker" />

    <!-- 单一入口「进入路线规划」：原「确认选择并规划」（在起终点插槽里）与本按钮合并，
         调度员不必为同一件事点两次，也不会出现「只规划了没分析 / 只分析了没规划」的中间态。
         配色沿用合并前「确认选择并规划」那颗的蓝色渐变（见本组件底部 .btn.primary 说明）。 -->
    <div class="row actions">
      <button class="btn primary" :disabled="planning" @click="analyze">
        <!-- 细线放大镜：不用 emoji（各系统渲染不一致），stroke 用 currentColor 跟随按钮文字色 -->
        <svg class="btn-icon" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="7" />
          <line x1="15.9" y1="15.9" x2="21" y2="21" />
        </svg>
        {{ planning ? '路线规划中…' : '进入路线规划' }}
      </button>
      <button v-if="task" class="btn outline" :disabled="busy" @click="reset">撤销派单</button>
    </div>

    <!-- 分析已触发：路线比选与派单入口在「AI 决策分析」面板的『开始导航派单』模板里 -->
    <div v-if="analyzed" class="analyze-box">
      <div class="analyze-head">
        <span>路线已规划，出车前 AI 气象分析已触发</span>
        <span class="muted od-tag">{{ originId }} → {{ destinationId }}</span>
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
  // 由 App.vue 经 SSE（task-assigned）实时推送 + 挂载时补拉一次；null = 服务端态还没取到
  taskStatus: { type: Object, default: null },
  // 起终点已上提到 App.vue 的 PlaceInput（经 route-picker 插槽渲染）：这里只接过来做展示回显
  originId: { type: String, default: "" },
  destinationId: { type: String, default: "" },
});
const emit = defineEmits(["refresh", "analyze"]);

// 起终点不再由本面板的下拉框控制（已并入 App.vue 的 PlaceInput，经 route-picker 插槽注入）；
// 本表单只留订单字段，analyze 时递上去，起终点由父组件解析后回填
const form = reactive({
  plate: "桂A·D12345",
  driverName: "阿明",
  cargoType: "DRAGON_FRUIT",
  weightT: 18,
});
const cargoTypes = ref({ DRAGON_FRUIT: { name: "冷链火龙果" }, ELECTRONICS: { name: "电子元件" } });
const busy = ref(false);
// 规划中：与 busy（撤销派单）分开，避免一次算路把「撤销派单」一起禁掉
const planning = ref(false);
const localTask = ref(null);
// 是否已触发出车前 AI 分析（触发后提示路线比选与派单入口）
const analyzed = ref(false);

// 服务端态一到就是唯一权威。旧写法 `props.taskStatus?.task || localTask.value` 在
// 司机退出接单 / 撤销派单后（服务端推的是 task:null）会回落到本组件 mount 时抓的
// 过期快照 localTask，大屏永远停在「已接单」不动，只有刷新页面才恢复——
// 这就是「司机端状态变了大屏半天不跟着变」的根因。
// localTask 只在父组件还没拿到任何服务端态之前（taskStatus 仍为 null）当兜底。
const task = computed(() => (props.taskStatus ? props.taskStatus.task : localTask.value));
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
    // REST 回包同时上报父组件：SSE 静默掉线（EventSource 断流不会报错到业务层）时，
    // 这条通道是两端唯一的对账手段，不能只写本地快照
    if (data && (data.task !== undefined || data.status !== undefined)) emit("refresh", data);
  } catch (e) {
    /* 静默 */
  }
}

// 「进入路线规划」：一次点击完成「确认起终点 → 规划主线与多路线候选 → 触发出车前 AI 分析」。
// 父组件（App.vue.onPreDispatchAnalysis）异步执行并回传 done 布尔：
// 只有真成功才点亮 analyze-box，失败（起终点解析不通 / 算路异常）不能把提示说成已完成。
// 看门狗兜底：父链路若在中途抛异常，done 永远不会被回调，按钮不能永久转圈。
async function analyze() {
  if (planning.value) return;
  planning.value = true;
  analyzed.value = false;
  let settled = false;
  const finish = (ok) => {
    if (settled) return;
    settled = true;
    clearTimeout(watchdog);
    planning.value = false;
    if (ok) analyzed.value = true;
  };
  const watchdog = setTimeout(() => finish(false), 120000);
  try {
    await new Promise((resolve) => emit("analyze", { ...form, done: (v) => { finish(v !== false); resolve(); } }));
  } catch (e) {
    console.error('进入路线规划失败', e);
    finish(false);
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
/* 本组件模板里的按钮必须自带 .btn 样式：App.vue 的 .btn/.btn.primary 写在 <style scoped> 里，
   编译后是 .btn.primary[data-v-父]，只有父作用域的元素（如 route-picker 插槽内容）才带那个标记；
   本组件自己模板里的按钮拿不到——合并前「AI 气象分析并规划路线」就是靠浏览器默认样式渲染的。
   合并后这颗要「保留确认选择并规划的蓝色」，所以把 App.vue 那条渐变原样搬过来。 */
.btn {
  padding: 8px 16px; border-radius: 8px; border: none;
  color: #fff; cursor: pointer; font-size: 12px; font-weight: 600;
  transition: all .2s ease;
}
.btn.primary {
  background: linear-gradient(135deg, #4f6df5, #22b8cf);
  box-shadow: 0 4px 18px rgba(79, 109, 245, 0.35);
}
.btn.primary:hover { box-shadow: 0 6px 24px rgba(79, 109, 245, 0.45); }
.btn.outline { background: transparent; color: #0e9bb0; border: 1px solid rgba(14, 155, 176, 0.4); }
.btn.outline:hover { border-color: #0e9bb0; background: rgba(14, 155, 176, 0.08); }
.btn:disabled { opacity: .6; cursor: not-allowed; box-shadow: none; }
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