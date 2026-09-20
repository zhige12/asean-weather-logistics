<template>
  <section class="card sandbox-card">
    <h3>
      决策沙盘
      <span class="route-state" :class="pushed ? (anyFused ? 'danger' : 'ok') : ''">
        {{ pushed ? (anyFused ? "存在熔断" : "全线可通行") : "等待官方气象数据" }}
      </span>
    </h3>
    <div class="muted data-source">数据来源：可在「实时气象」卡片切换（比赛官方 CRA40 / 真实气象 Open-Meteo / 离线模拟）</div>

    <div class="row push-row">
      <button class="btn primary" :disabled="pushing" @click="pushOfficial">
        {{ pushing ? "推送中…" : "📡 模拟官方气象推送（85/62mm）" }}
      </button>
      <button class="btn outline" v-if="pushed" @click="reset">重置沙盘</button>
    </div>

    <div v-if="pushed" class="section-list">
      <div v-for="s in sections" :key="s.id" class="section-item" :class="'st-' + s.status">
        <div class="section-head">
          <span class="section-name">{{ s.name }}</span>
          <span class="section-badge" :class="badgeClass(s)">{{ badgeText(s) }}</span>
        </div>

        <div class="slider-row">
          <input
            type="range"
            min="0"
            max="120"
            step="1"
            class="rain-slider"
            :class="{ fused: s.fused }"
            :value="s.forecastMm"
            :style="sliderStyle(s)"
            @input="onSlide(s.id, $event)"
            @change="commitSlide"
          />
          <span class="rain-value" :class="{ danger: s.fused }">{{ Math.round(s.forecastMm) }}mm</span>
        </div>

        <div class="section-info">
          <div class="info-line" :class="{ danger: s.fused }">
            {{ s.fused ? "⚠️ " + s.name + "已熔断" : s.statusText }}
          </div>
          <div class="info-sub">{{ s.boundaryText }}</div>
          <div class="info-sub muted">
            边坡：{{ s.slopeSoil }} · 含水量 {{ s.moisturePct }}% · 风险{{ s.riskLevel }}
          </div>
        </div>
      </div>
    </div>
    <div v-else class="muted empty-hint">
      点击上方按钮模拟官方气象接口推送，或收到推送后拖动滑块观察决策边界。<br />
      不是黑箱，是可交互的决策边界。
    </div>

    <!-- 水运断面（场景B：双向切换的另一半） -->
    <div v-if="pushed && water" class="water-section" :class="{ blocked: water.blocked }">
      <div class="section-head">
        <span class="section-name">🚢 {{ water.name }}</span>
        <span class="section-badge" :class="water.blocked ? 'fused' : 'clear'">
          {{ water.blocked ? "已禁航" : "通航正常" }}
        </span>
      </div>

      <!-- 禁航弹窗横幅（演示步骤5.3：平陆运河变红 + 禁航提示） -->
      <div v-if="water.blocked" class="water-alert">
        🚫 {{ water.blockedReason || "通航条件超限" }}，{{ water.name }}禁航
      </div>

      <div class="water-conditions">
        <div class="cond-item" :class="{ breach: water.visibilityM < water.redlines?.visibilityMinM }">
          能见度 {{ Math.round(water.visibilityM) }}m
          <span class="cond-line">红线 &lt;{{ water.redlines?.visibilityMinM }}m</span>
        </div>
        <div class="cond-item" :class="{ breach: water.currentSpeedMs > water.redlines?.currentMaxMs }">
          流速 {{ water.currentSpeedMs?.toFixed(1) }}m/s
          <span class="cond-line">红线 &gt;{{ water.redlines?.currentMaxMs }}m/s</span>
        </div>
        <div class="cond-item" :class="{ breach: water.waveHeightM > water.redlines?.waveMaxM }">
          浪高 {{ water.waveHeightM?.toFixed(1) }}m
          <span class="cond-line">红线 &gt;{{ water.redlines?.waveMaxM }}m</span>
        </div>
      </div>

      <div class="row water-actions">
        <button v-if="!water.blocked" class="btn danger-outline" :disabled="waterBusy" @click="blockWater">
          🌊 模拟水运禁航（能见度 900m）
        </button>
        <button v-else class="btn outline" :disabled="waterBusy" @click="clearWater">
          恢复通航
        </button>
      </div>

      <!-- 双向切换方向提示 -->
      <div v-if="directionText" class="direction-line">{{ directionText }}</div>
    </div>

    <div v-if="pushed" class="threshold-line">
      当前熔断阈值：友谊关 <b>{{ state.fuseThresholdMm }}mm</b>（{{ thresholdName }}）· 芒街 70mm · 滞后恢复 5mm
    </div>

    <!-- 参数双向映射（4.2）：公路版参数 ⇄ 运河版参数 -->
    <div v-if="pushed" class="mapping-line">
      参数双向映射：降雨量 &gt;<b>{{ state.fuseThresholdMm }}mm/h → 熔断</b> ⇄
      能见度 &lt;1000m / 流速 &gt;2m/s / 浪高 &gt;2m → <b>禁航预警</b><br />
      决策输出：公路断切水运，水运禁航切公路
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import axios from "axios";

const emit = defineEmits(["sandbox-change"]);

const state = ref({ pushed: false, fuseThresholdMm: 80, riskProfile: "CONSERVATIVE", sections: [] });
const pushing = ref(false);
const waterBusy = ref(false);

const pushed = computed(() => state.value.pushed);
const sections = computed(() => state.value.sections || []);
const anyFused = computed(() => sections.value.some((s) => s.fused));
const water = computed(() => state.value.water);
// 双向切换方向（3.4）：公路⇄水运互为备选
const directionText = computed(() => {
  const map = {
    road_to_water: "🔀 双向切换：公路已熔断 → 系统已分析水运方案（切水运/绕行芒街/等待）",
    water_to_road: "🔀 双向切换：水运已禁航 → 系统已分析公路方案（切公路/锚泊等待/绕行其他口岸）",
    dual_risk: "⚠️ 双线风险：公路与水运同时受阻 → 建议原地等待/延迟发车",
  };
  return map[state.value.direction] || "";
});
const thresholdName = computed(() => {
  const names = { CONSERVATIVE: "保守", BALANCED: "平衡", AGGRESSIVE: "激进" };
  return names[state.value.riskProfile] || state.value.riskProfile;
});

function badgeClass(s) {
  if (s.fused) return "fused";
  if (s.status === "CAUTION") return "caution";
  return "clear";
}
function badgeText(s) {
  if (s.fused) return "已熔断";
  if (s.status === "CAUTION") return "可用·关注";
  return "通行正常";
}
function sliderStyle(s) {
  const pct = Math.min(100, (s.forecastMm / 120) * 100);
  const color = s.fused ? "#e11d48" : s.forecastMm >= 50 ? "#d97706" : "#22a35a";
  return {
    background: `linear-gradient(to right, ${color} 0%, ${color} ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)`,
  };
}

// 拖动中只更新本地数值（视觉即时反馈），松手才提交后端（熔断判定 + 重算 + SSE）
const localMm = ref({});
function onSlide(id, e) {
  const v = Number(e.target.value);
  localMm.value[id] = v;
  const sec = sections.value.find((x) => x.id === id);
  if (sec) sec.forecastMm = v;
}

let commitTimer = null;
async function commitSlide() {
  clearTimeout(commitTimer);
  commitTimer = setTimeout(async () => {
    const body = {};
    if (localMm.value.YGG != null) body.yggMm = localMm.value.YGG;
    if (localMm.value.MC != null) body.mcMm = localMm.value.MC;
    localMm.value = {};
    if (!Object.keys(body).length) return;
    try {
      const { data } = await axios.post("/api/sandbox/rainfall", body);
      applyState(data);
    } catch (e) {
      console.error("sandbox rainfall failed", e);
    }
  }, 120);
}

async function pushOfficial() {
  pushing.value = true;
  try {
    const { data } = await axios.post("/api/sandbox/push", null, { params: { yggMm: 85, mcMm: 62 } });
    applyState(data);
  } catch (e) {
    console.error("sandbox push failed", e);
  } finally {
    pushing.value = false;
  }
}

async function reset() {
  try {
    const { data } = await axios.post("/api/sandbox/reset");
    applyState(data);
  } catch (e) {
    console.error("sandbox reset failed", e);
  }
}

// 场景B：一键模拟水运禁航（演示步骤5.3）
async function blockWater() {
  waterBusy.value = true;
  try {
    const { data } = await axios.post("/api/sandbox/water/block");
    applyState(data);
  } catch (e) {
    console.error("water block failed", e);
  } finally {
    waterBusy.value = false;
  }
}

async function clearWater() {
  waterBusy.value = true;
  try {
    const { data } = await axios.post("/api/sandbox/water/clear");
    applyState(data);
  } catch (e) {
    console.error("water clear failed", e);
  } finally {
    waterBusy.value = false;
  }
}

async function refresh() {
  try {
    const { data } = await axios.get("/api/sandbox/state");
    state.value = data;
  } catch (e) {
    /* 静默 */
  }
}

function applyState(data) {
  state.value = data;
  emit("sandbox-change", data);
}

onMounted(refresh);

// 供父组件调用：外部（配置器改阈值）触发后刷新沙盘
defineExpose({ refresh });
</script>

<style scoped>
.sandbox-card {
  border: 1px solid rgba(22, 163, 74, 0.35);
}
.data-source {
  font-size: 11px;
  margin-bottom: 8px;
}
.push-row {
  margin-bottom: 10px;
}
.section-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.section-item {
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.60);
  transition: border-color 0.3s, background 0.3s;
}
.section-item.st-FUSED {
  border-color: rgba(225, 29, 72, 0.6);
  background: rgba(225, 29, 72, 0.08);
}
.section-item.st-CAUTION {
  border-color: rgba(217, 119, 6, 0.45);
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.section-name {
  font-weight: 600;
  color: var(--text-h, #1c2a44);
}
.section-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 600;
}
.section-badge.fused {
  background: rgba(225, 29, 72, 0.2);
  color: #e11d48;
}
.section-badge.caution {
  background: rgba(217, 119, 6, 0.18);
  color: #d97706;
}
.section-badge.clear {
  background: rgba(22, 163, 74, 0.18);
  color: #16a34a;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rain-slider {
  flex: 1;
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  border-radius: 3px;
  outline: none;
  cursor: pointer;
}
.rain-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #888;
  cursor: grab;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}
.rain-slider.fused::-webkit-slider-thumb {
  border-color: #e11d48;
}
.rain-value {
  min-width: 52px;
  text-align: right;
  font-weight: 700;
  font-family: var(--mono, monospace);
  color: var(--text-h, #1c2a44);
}
.rain-value.danger {
  color: #e11d48;
}
.section-info {
  margin-top: 8px;
}
.info-line {
  font-size: 13px;
  font-weight: 600;
  color: #16a34a;
}
.info-line.danger {
  color: #e11d48;
}
.info-sub {
  font-size: 11px;
  margin-top: 2px;
  color: #5b6b85;
}
.empty-hint {
  font-size: 12px;
  line-height: 1.8;
}
.threshold-line {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #5b6b85;
}
.threshold-line b {
  color: #d97706;
}
.water-section {
  margin-top: 10px;
  border: 1px solid rgba(94, 234, 219, 0.3);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(94, 234, 219, 0.04);
  transition: border-color 0.3s, background 0.3s;
}
.water-section.blocked {
  border-color: rgba(225, 29, 72, 0.6);
  background: rgba(225, 29, 72, 0.08);
}
.water-alert {
  margin: 8px 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(225, 29, 72, 0.18);
  border: 1px solid rgba(225, 29, 72, 0.5);
  color: #e11d48;
  font-size: 12px;
  font-weight: 700;
  animation: blink 1.2s infinite alternate;
}
@keyframes blink {
  from { opacity: 1; }
  to { opacity: 0.65; }
}
.water-conditions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.cond-item {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: #33415c;
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 8px;
  padding: 6px 8px;
  text-align: center;
}
.cond-item .cond-line {
  display: block;
  font-size: 10px;
  font-weight: 400;
  color: #64748f;
  margin-top: 2px;
}
.cond-item.breach {
  border-color: rgba(225, 29, 72, 0.6);
  color: #e11d48;
}
.cond-item.breach .cond-line {
  color: #e11d48;
}
.water-actions {
  margin-top: 10px;
}
.btn.danger-outline {
  background: transparent;
  border: 1px solid rgba(225, 29, 72, 0.6);
  color: #e11d48;
}
.btn.danger-outline:hover:not(:disabled) {
  background: rgba(225, 29, 72, 0.12);
}
.direction-line {
  margin-top: 10px;
  font-size: 11px;
  color: #4f6df5;
  line-height: 1.6;
}
.mapping-line {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #5b6b85;
  line-height: 1.8;
}
.mapping-line b {
  color: #0e9bb0;
}
</style>
