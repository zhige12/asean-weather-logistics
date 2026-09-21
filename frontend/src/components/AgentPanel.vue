<template>
  <section class="card agent-card">
    <h3>
      AI 决策分析 · 多智能体协同
      <span v-if="result" class="route-state" :class="result.aiPowered ? 'ok' : ''">
        {{ result.cacheHit ? "⚡ 预生成缓存" : result.aiPowered ? "本地大模型" : "规则模板" }}
      </span>
    </h3>
    <div class="muted agent-hint">
      三个智能体协同工作：风险研判 → 方案生成 → 触达，前一个的输出作为后一个的输入
    </div>

    <div class="row">
      <button class="btn primary" :disabled="running" @click="runAnalysis(false)">
        {{ running ? "智能体推理中…" : "🧠 AI 决策分析" }}
      </button>
      <button class="btn outline" :disabled="running" @click="runAnalysis(true)" title="读取预生成缓存，演示 2 秒出结果">
        ⚡ 缓存模式
      </button>
    </div>

    <!-- 三智能体状态条（真实 SSE 进度驱动，依次点亮） -->
    <div class="agent-flow" :class="{ running }">
      <template v-for="(a, i) in agentFlow" :key="a.id">
        <div class="agent-node" :class="a.status">
          <div class="agent-icon">{{ a.icon }}</div>
          <div class="agent-name">{{ a.name }}</div>
          <div class="agent-meta">
            <span v-if="a.status === 'RUNNING'" class="spinner"></span>
            <span v-else-if="a.status === 'DONE'">✅ {{ a.elapsedMs ? (a.elapsedMs / 1000).toFixed(1) + "s" : "" }}</span>
            <span v-else>待命</span>
          </div>
          <div v-if="a.output" class="agent-output">{{ a.output }}</div>
        </div>
        <div v-if="i < agentFlow.length - 1" class="agent-arrow" :class="{ lit: agentFlow[i].status === 'DONE' }">→</div>
      </template>
    </div>

    <template v-if="result">
      <!-- 风险研判输出 -->
      <div class="block">
        <div class="block-title">🔵 风险研判 · 决策解释</div>
        <div class="explain-box">
          <div class="explain-item" :class="{ danger: result.explanation?.ygg?.fused }">
            {{ result.explanation?.ygg?.verdict }}
          </div>
          <div class="explain-item">
            {{ result.explanation?.mc?.verdict }}
          </div>
          <div class="explain-boundary">{{ result.explanation?.boundary }}</div>
          <div v-if="result.explanation?.llm" class="llm-chip" title="本地大模型结构化研判输出">
            🧠 模型研判：{{ result.explanation.llm.riskLevel }}风险 · 决策边界 {{ result.explanation.llm.decisionBoundary }}
          </div>
        </div>
      </div>

      <!-- 三方案对比（六维分析 + 双向切换） -->
      <div class="block">
        <div class="block-title">🟢 方案生成 · 六维分析 + 双向切换</div>

        <!-- 风险已变化：旧推荐立即失效，等待新分析 -->
        <div v-if="stale" class="stale-banner">
          ⚠️ 风险状态已变化，以下旧方案已失效，正在重新分析绕行路线…
        </div>

        <!-- 双向切换方向横幅（3.4 决策机制） -->
        <div v-if="directionInfo" class="direction-banner" :class="'dir-' + result.direction">
          <div class="dir-title">{{ directionInfo.title }}</div>
          <div class="dir-sub">{{ directionInfo.sub }}</div>
        </div>

        <!-- 六维对比表：耗时 / 风险 / 货损率 / 油耗 / 综合费用 / 碳排放 -->
        <div v-if="metricPlans.length" class="metrics-wrap">
          <div class="mode-tip" :class="isReroute ? 'mode-reroute' : 'mode-normal'">
            {{ isReroute
              ? "已触发熔断/禁航：以下为相对原计划的增量（多花多少）"
              : "常态方案对比：以下为整趟运输的全程数值（南宁→河内）" }}
          </div>
          <table class="metrics-table">
            <thead>
              <tr>
                <th>对比维度</th>
                <th v-for="p in metricPlans" :key="p.id" :class="{ rec: p.id === recommendedId }">
                  {{ p.name }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>耗时</td>
                <td v-for="p in metricPlans" :key="p.id">
                  {{ isReroute ? fmtDelta(p.metrics.extraHours, "h") : fmtAbs(p.metrics.totalHours, "h") }}
                  <span v-if="isReroute && p.metrics.totalHours" class="abs-sub">
                    全程 {{ fmtAbs(p.metrics.totalHours, "h") }}
                  </span>
                </td>
              </tr>
              <tr>
                <td>风险等级</td>
                <td v-for="p in metricPlans" :key="p.id">
                  <span class="risk-chip" :class="'risk-' + riskLevelKey(p.metrics.risk)">{{ p.metrics.risk }}</span>
                </td>
              </tr>
              <tr>
                <td>货损率</td>
                <td v-for="p in metricPlans" :key="p.id">{{ p.metrics.cargoLossPct }}%</td>
              </tr>
              <tr>
                <td>油耗</td>
                <td v-for="p in metricPlans" :key="p.id">
                  {{ isReroute ? fmtDelta(p.metrics.fuelL, "L") : fmtAbs(p.metrics.absFuelL, "L") }}
                </td>
              </tr>
              <tr>
                <td>综合费用</td>
                <td v-for="p in metricPlans" :key="p.id" :class="isReroute ? costClass(p.metrics.costYuan) : ''">
                  {{ isReroute ? fmtDelta(p.metrics.costYuan, "元") : fmtAbs(p.metrics.absCostYuan, "元") }}
                </td>
              </tr>
              <tr>
                <td>碳排放</td>
                <td v-for="p in metricPlans" :key="p.id">
                  <span class="risk-chip" :class="'risk-' + riskLevelKey(carbonLevelOf(p.metrics))">
                    {{ carbonLevelOf(p.metrics) }}
                  </span>
                  <span class="carbon-kg">
                    {{ isReroute ? fmtDelta(p.metrics.carbonKg, "kg") : fmtAbs(p.metrics.absCarbonKg, "kg") }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="muted metrics-hint">
            AI 只提供分析，不替客户做选择。调度端根据风险偏好和合同要求人工确认。
          </div>
        </div>

        <div class="plan-list" :class="{ 'plan-stale': stale }">
          <div
            v-for="p in result.plans"
            :key="p.id"
            class="plan-item"
            :class="{ recommended: p.id === recommendedId, selected: p.id === selectedPlan }"
            @click="selectPlan(p.id)"
          >
            <div class="plan-head">
              <span class="plan-badge">{{ p.id }}</span>
              <span class="plan-name">{{ p.name }}</span>
              <span v-if="p.id === recommendedId" class="rec-tag">推荐</span>
            </div>
            <div class="plan-metrics">
              <span :class="deltaClass(p.extraHours)">⏱ {{ fmtDelta(p.extraHours, "h") }}</span>
              <span :class="costClass(p.costDeltaYuan)">💰 {{ fmtDelta(p.costDeltaYuan, "元") }}</span>
              <span class="risk-chip" :class="'risk-' + riskLevelKey(p.damageRisk)">货损风险：{{ p.damageRisk }}</span>
            </div>
            <div v-if="p.damageRatePct != null" class="plan-damage">
              货损率 {{ p.damageRatePct }}%（约 ¥{{ fmtNum(p.damageLossYuan) }}）
            </div>
            <div class="plan-note">{{ p.note || p.damageNote }}</div>
            <div v-if="p.legs" class="plan-legs">
              <span v-for="(leg, i) in p.legs" :key="i" class="leg-chip">{{ leg.mode }} {{ leg.from }}→{{ leg.to }}</span>
            </div>
            <!-- 多式联运“一口价”（§4.4）：货主面对单一打包总价，无需分别对接三方 -->
            <div v-if="p.flatPriceYuan != null" class="flat-price">
              <div class="fp-head">
                🏷️ 多式联运“一口价”
                <span class="fp-total">¥{{ fmtNum(p.flatPriceYuan) }}</span>
              </div>
              <div class="fp-parties">
                <span v-for="(pt, i) in p.flatPriceParties" :key="i" class="fp-chip" :title="pt.item">
                  {{ pt.party }} ¥{{ fmtNum(pt.costYuan) }}
                </span>
              </div>
              <div class="fp-note">{{ p.flatPriceNote }}</div>
            </div>
          </div>
        </div>
        <div class="recommend-box">💡 {{ result.recommendation }}</div>
      </div>

      <!-- 触达预览 -->
      <div class="block">
        <div class="block-title">🟡 触达智能体 · 角色专属指令（{{ result.outreachPreview?.count || 0 }} 个角色）</div>
        <div class="touch-list">
          <div v-for="t in result.outreachPreview?.targets || []" :key="t.role" class="touch-item">
            <span class="touch-role">{{ t.name }}</span>
            <span class="touch-instruction">{{ t.instruction }}</span>
          </div>
        </div>
      </div>

      <!-- 调度决策：确认方案 → 下发任务变更 -->
      <div class="row dispatch-row">
        <button class="btn confirm" :disabled="dispatching || !selectedPlan || stale" @click="dispatch">
          {{ dispatching ? "下发中…" : stale ? "⏳ 等待新分析结果…" : "✅ 确认切换方案" + (selectedPlan || "") + " · 下发任务变更指令" }}
        </button>
      </div>
      <div class="muted dispatch-hint">
        司机收到的是执行指令和权益保障包，不是"你想走公路还是水运"——切换运输方式是调度端的决策权限。
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref } from "vue";
import axios from "axios";

const emit = defineEmits(["dispatched"]);

const props = defineProps({
  originId: { type: String, default: "NN" },
  destinationId: { type: String, default: "HN" },
});

const running = ref(false);
const dispatching = ref(false);
const result = ref(null);
const selectedPlan = ref("");
// 风险变化后旧结果立即标记过期（不再展示旧推荐），并自动重算新方案
const stale = ref(false);
const rerunPending = ref(false);

// 三智能体实时状态（SSE agent-status 事件驱动 + 结果回填）
const agentFlow = ref([
  { id: "risk-assessment", name: "风险研判智能体", icon: "🔵", status: "IDLE", elapsedMs: 0, output: "" },
  { id: "plan-generation", name: "方案生成智能体", icon: "🟢", status: "IDLE", elapsedMs: 0, output: "" },
  { id: "outreach", name: "触达智能体", icon: "🟡", status: "IDLE", elapsedMs: 0, output: "" },
]);

const recommendedId = computed(() => {
  if (!result.value?.plans) return "";
  const rec = result.value.agents?.find((a) => a.id === "plan-generation");
  return rec?.detail?.recommended || (result.value.plans.find((p) => p.id === "B") ? "B" : "A");
});

// 带六维指标的方案（六维对比表数据源）
const metricPlans = computed(() => (result.value?.plans || []).filter((p) => p.metrics));

// 分析模式：reroute=已熔断/禁航（展示增量）；normal=常态（展示整趟运输绝对值）
const isReroute = computed(() => result.value?.analysisMode === "reroute");

// 全程碳排放等级（常态用绝对值标定；熔断沿用后端增量等级）
function carbonLevelOf(m) {
  if (isReroute.value) return m.carbonLevel;
  const kg = m.absCarbonKg || 0;
  return kg >= 450 ? "高" : kg >= 300 ? "中" : "低";
}

// 双向切换方向（3.4）：公路熔断⇄水运禁航互为备选
const directionInfo = computed(() => {
  const d = result.value?.direction;
  if (!d || d === "normal") return null;
  const reason = result.value?.waterBlockedReason || "通航条件超限";
  const map = {
    road_to_water: {
      title: "🔀 双向切换：公路熔断 → AI 已分析水运方案",
      sub: "友谊关暴雨触发熔断，可选：切水运（公水联运）/ 公路绕行芒街 / 原地等待",
    },
    water_to_road: {
      title: "🔀 双向切换：水运禁航 → AI 已分析公路方案",
      sub: `平陆运河${reason}，可选：切公路（绕行芒街）/ 锚泊等待 / 绕行其他口岸`,
    },
    dual_risk: {
      title: "⚠️ 双线风险：公路与水运同时受阻",
      sub: "两条线各自风险均不可控，建议原地等待 / 延迟发车，等待期间货损风险已标注",
    },
  };
  return map[d] || null;
});

function selectPlan(id) {
  selectedPlan.value = id;
}

function fmtDelta(v, unit) {
  if (v == null) return "不可用";
  const n = Number(v);
  const sign = n > 0 ? "+" : "";
  return sign + (Number.isInteger(n) ? n : n.toFixed(1)) + unit;
}
/** 全程绝对值格式化（不带正负号：整趟运输实际消耗） */
function fmtAbs(v, unit) {
  if (v == null) return "不可用";
  const n = Number(v);
  return (Number.isInteger(n) ? n : n.toFixed(1)) + unit;
}
function deltaClass(v) {
  if (v == null) return "muted";
  return v > 0 ? "delta-warn" : "delta-ok";
}
function costClass(v) {
  if (v == null) return "muted";
  return v > 0 ? "delta-warn" : "delta-ok";
}
function riskLevelKey(r) {
  return r === "高" ? "high" : r === "中" ? "mid" : "low";
}
function fmtNum(v) {
  return v == null ? "-" : Number(v).toLocaleString();
}

async function runAnalysis(useCache) {
  running.value = true;
  resetAgentFlow();
  try {
    const params = { originId: props.originId, destinationId: props.destinationId };
    if (useCache) params.scenarioId = "heavy_rain";
    const { data } = await axios.get("/api/agents/analysis", { params, timeout: useCache ? 30000 : 300000 });
    result.value = data;
    stale.value = false;
    // 回填最终状态（SSE 若未连接也能正确展示）
    (data.agents || []).forEach((a) => {
      const node = agentFlow.value.find((n) => n.id === a.id);
      if (node) {
        node.status = "DONE";
        node.elapsedMs = a.elapsedMs || 0;
      }
    });
    (data.agentStatus || []).forEach((row, i) => {
      if (agentFlow.value[i]) agentFlow.value[i].output = row.output;
    });
    // 默认选中推荐方案
    selectedPlan.value = recommendedId.value;
  } catch (e) {
    console.error("agent analysis failed", e);
    agentFlow.value.forEach((n) => {
      if (n.status === "RUNNING") n.status = "IDLE";
    });
  } finally {
    running.value = false;
    // 推理期间又有新的风险注入 → 结果一出就立即再算一轮
    if (rerunPending.value) {
      rerunPending.value = false;
      runAnalysis(false);
    }
  }
}

function resetAgentFlow() {
  agentFlow.value.forEach((n) => {
    n.status = "IDLE";
    n.elapsedMs = 0;
    n.output = "";
  });
}

/** 供父组件 SSE 调用：真实推理进度驱动点亮 */
function onSseStatus(payload) {
  const node = agentFlow.value.find((n) => n.id === payload.agentId);
  if (!node) return;
  node.status = payload.status;
  if (payload.elapsedMs) node.elapsedMs = payload.elapsedMs;
  if (payload.output) node.output = payload.output;
}

async function dispatch() {
  if (!selectedPlan.value) return;
  dispatching.value = true;
  try {
    const plan = result.value.plans.find((p) => p.id === selectedPlan.value);
    const { data } = await axios.post("/api/outreach/dispatch", {
      planId: selectedPlan.value,
      planName: plan ? plan.name : "",
    });
    emit("dispatched", data);
  } catch (e) {
    console.error("dispatch failed", e);
  } finally {
    dispatching.value = false;
  }
}

/**
 * 风险状态变化（场景注入/清除、沙盘雨量调整、水运禁航等）时由父组件调用：
 * 已有结果立即标记过期（界面上不再信任旧推荐），并自动重新计算新方案（防抖 1.2s）。
 * 正在推理则只排队，本轮结束后自动再算一轮。
 */
let staleTimer = null;
function notifyRiskChanged() {
  if (!result.value && !running.value) return; // 还没分析过就不打扰
  stale.value = true;
  if (staleTimer) clearTimeout(staleTimer);
  staleTimer = setTimeout(() => {
    if (running.value) {
      rerunPending.value = true;
      return;
    }
    runAnalysis(false);
  }, 1200);
}

defineExpose({ onSseStatus, notifyRiskChanged, runAnalysis });
</script>

<style scoped>
.agent-card {
  border: 1px solid rgba(79, 109, 245, 0.35);
}
.agent-hint {
  font-size: 11px;
  margin-bottom: 10px;
}
.agent-flow {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin: 12px 0;
  padding: 10px 6px;
  border-radius: 10px;
  background: rgba(255,255,255,0.60);
}
.agent-node {
  flex: 1;
  text-align: center;
  opacity: 0.45;
  transition: opacity 0.4s, transform 0.4s;
}
.agent-node.RUNNING {
  opacity: 1;
  transform: scale(1.06);
}
.agent-node.DONE {
  opacity: 1;
}
.agent-icon {
  font-size: 22px;
}
.agent-node.RUNNING .agent-icon {
  animation: pulse 1s infinite alternate;
}
@keyframes pulse {
  from { transform: scale(1); }
  to { transform: scale(1.25); }
}
.agent-name {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-h, #1c2a44);
  margin-top: 2px;
}
.agent-meta {
  font-size: 10px;
  color: #64748f;
  min-height: 14px;
}
.agent-output {
  font-size: 10px;
  color: #4f6df5;
  margin-top: 2px;
  line-height: 1.4;
}
.agent-arrow {
  align-self: center;
  color: #444;
  font-weight: 700;
  transition: color 0.4s;
}
.agent-arrow.lit {
  color: #4f6df5;
}
.spinner {
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 2px solid #4f6df5;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.block {
  margin-top: 12px;
}
.block-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
  margin-bottom: 6px;
}
.explain-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.explain-item {
  font-size: 12px;
  line-height: 1.6;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(22, 163, 74, 0.08);
  border-left: 3px solid #22a35a;
}
.explain-item.danger {
  background: rgba(225, 29, 72, 0.08);
  border-left-color: #e11d48;
}
.explain-boundary {
  font-size: 11px;
  color: #d97706;
  padding: 4px 10px;
}
.llm-chip {
  font-size: 11px;
  color: #7c5cff;
  padding: 2px 10px;
}
.plan-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plan-item {
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.plan-item:hover {
  border-color: rgba(79, 109, 245, 0.5);
}
.plan-item.recommended {
  border-color: rgba(22, 163, 74, 0.5);
}
.plan-item.selected {
  background: rgba(79, 109, 245, 0.1);
  border-color: #4f6df5;
}
.plan-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.plan-badge {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: rgba(79, 109, 245, 0.2);
  color: #4f6df5;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.plan-name {
  font-weight: 700;
  color: var(--text-h, #1c2a44);
  font-size: 13px;
}
.rec-tag {
  font-size: 10px;
  background: rgba(22, 163, 74, 0.2);
  color: #16a34a;
  padding: 1px 6px;
  border-radius: 8px;
}
.plan-metrics {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
}
.delta-warn {
  color: #d97706;
}
.delta-ok {
  color: #16a34a;
}
.risk-chip {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 8px;
}
.risk-chip.risk-high {
  background: rgba(225, 29, 72, 0.18);
  color: #e11d48;
}
.risk-chip.risk-mid {
  background: rgba(217, 119, 6, 0.18);
  color: #d97706;
}
.risk-chip.risk-low {
  background: rgba(22, 163, 74, 0.18);
  color: #16a34a;
}
.plan-damage {
  font-size: 11px;
  color: #e11d48;
  margin-top: 4px;
}
.plan-note {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 4px;
  line-height: 1.5;
}
.plan-legs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.leg-chip {
  font-size: 10px;
  background: rgba(139, 148, 158, 0.15);
  color: #64748f;
  padding: 2px 6px;
  border-radius: 6px;
}
.flat-price {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(22, 163, 74, 0.08);
  border: 1px dashed rgba(22, 163, 74, 0.4);
}
.fp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  color: #16a34a;
}
.fp-total {
  font-size: 15px;
}
.fp-parties {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.fp-chip {
  font-size: 10px;
  background: rgba(22, 163, 74, 0.14);
  color: #15803d;
  padding: 2px 6px;
  border-radius: 6px;
}
.fp-note {
  font-size: 10px;
  color: #5b6b85;
  margin-top: 6px;
  line-height: 1.5;
}
.recommend-box {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(188, 140, 255, 0.1);
  border-left: 3px solid #7c5cff;
  color: #8b5cf6;
}
.touch-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.touch-item {
  display: flex;
  gap: 8px;
  font-size: 12px;
  line-height: 1.5;
}
.touch-role {
  flex-shrink: 0;
  min-width: 64px;
  font-weight: 600;
  color: #4f6df5;
}
.touch-instruction {
  color: #5b6b85;
}
.direction-banner {
  border-radius: 10px;
  padding: 8px 12px;
  margin-bottom: 10px;
  border-left: 3px solid #4f6df5;
  background: rgba(79, 109, 245, 0.08);
}
.direction-banner.dir-dual_risk {
  border-left-color: #e11d48;
  background: rgba(225, 29, 72, 0.1);
}
.direction-banner.dir-water_to_road {
  border-left-color: #d97706;
  background: rgba(217, 119, 6, 0.08);
}
.dir-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
}
.dir-sub {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 3px;
  line-height: 1.5;
}
.mode-tip {
  font-size: 11px;
  padding: 5px 10px;
  border-radius: 6px;
  margin-bottom: 6px;
  line-height: 1.5;
}
.mode-tip.mode-normal {
  background: rgba(79, 109, 245, 0.10);
  color: #3556d4;
  border: 1px solid rgba(79, 109, 245, 0.25);
}
.mode-tip.mode-reroute {
  background: rgba(217, 119, 6, 0.10);
  color: #b45309;
  border: 1px solid rgba(217, 119, 6, 0.25);
}
.abs-sub {
  display: block;
  font-size: 10px;
  color: #93a2ba;
}
.metrics-wrap {
  margin-bottom: 10px;
  overflow-x: auto;
}
.metrics-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.metrics-table th,
.metrics-table td {
  border: 1px solid rgba(30, 50, 90, 0.10);
  padding: 5px 8px;
  text-align: center;
  color: #33415c;
}
.metrics-table th {
  background: rgba(79, 109, 245, 0.08);
  color: var(--text-h, #1c2a44);
  font-weight: 600;
  font-size: 11px;
}
.metrics-table th.rec {
  background: rgba(22, 163, 74, 0.15);
  color: #16a34a;
}
.metrics-table td:first-child {
  color: #64748f;
  font-size: 11px;
  white-space: nowrap;
}
.carbon-kg {
  font-size: 10px;
  color: #64748f;
}
.metrics-hint {
  font-size: 11px;
  margin-top: 6px;
  color: #d97706;
}
.stale-banner {
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(217, 119, 6, 0.12);
  border: 1px solid rgba(217, 119, 6, 0.4);
  color: #b45309;
  font-size: 12px;
  font-weight: 600;
  animation: blink 1s infinite alternate;
}
.plan-stale {
  opacity: 0.45;
  filter: saturate(0.6);
  pointer-events: none;
  transition: opacity 0.3s;
}
.dispatch-row {
  margin-top: 14px;
}
.btn.confirm {
  background: #1fa84a;
  color: #fff;
  width: 100%;
  font-weight: 700;
}
.btn.confirm:hover:not(:disabled) {
  background: #34d399;
}
.btn.confirm:disabled {
  opacity: 0.5;
}
.dispatch-hint {
  font-size: 11px;
  margin-top: 6px;
  line-height: 1.6;
}
</style>
