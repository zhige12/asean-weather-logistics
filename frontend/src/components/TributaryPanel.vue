<template>
  <section class="card tributary-card">
    <h3>
      平陆运河支流风险 · 差异化预测
      <span v-if="loaded" class="route-state">{{ mode === 'live' ? '实况驱动' : '未来6小时' }}</span>
    </h3>
    <div class="muted trib-hint">
      平陆运河沿线每条支流有独立的特征向量，输出的是概率，不是简单的熔断/不熔断
    </div>

    <div class="mode-row">
      <button class="mode-btn" :class="{ active: mode === 'live' }" @click="switchMode('live')">真实降雨</button>
      <button class="mode-btn" :class="{ active: mode === 'manual' }" @click="switchMode('manual')">手动剧本</button>
      <button v-if="mode === 'live'" class="refresh-btn" :disabled="liveLoading" @click="loadLive">
        {{ liveLoading ? '拉取中…' : '刷新' }}
      </button>
      <label class="link-toggle" :title="linkMainline ? '支流概率聚合为顶托流速，参与干流禁航红线判定' : '仅展示预测，不影响干流'">
        <input type="checkbox" v-model="linkMainline" @change="apply" />
        联动干流禁航
      </label>
    </div>

    <div v-if="mode === 'live' && live" class="live-info" :class="{ warn: !live.realtimeSource || !live.dataAvailable }">
      <span>源：{{ live.sourceLabel || '—' }}</span>
      <span v-if="live.dataAvailable && live.fetchedAt">· {{ fmtTime(live.fetchedAt) }} 拉取</span>
      <span v-if="!live.realtimeSource">· 当前气象源不是真实降雨</span>
      <span v-else class="live-eq">· {{ live.equivalentNote }}</span>
    </div>

    <div v-if="mode === 'manual'" class="slider-row">
      <span class="slider-label">平陆运河支流流域降雨量</span>
      <input
        type="range"
        min="0"
        max="100"
        step="5"
        v-model.number="rainfall"
        class="rain-slider"
        :style="sliderStyle"
        @change="load"
      />
      <span class="rain-value">{{ rainfall }}mm</span>
    </div>

    <div v-if="tributaries.length" class="trib-list">
      <div v-for="t in tributaries" :key="t.id" class="trib-item" :class="'lv-' + levelKey(t.level)">
        <div class="trib-head">
          <span class="trib-name">{{ t.name }}</span>
          <span class="trib-prob" :class="'prob-' + levelKey(t.level)">{{ t.probabilityPct }}%</span>
        </div>
        <div class="trib-risk">{{ t.riskType }} · 风险{{ t.level }}</div>
        <div v-if="mode === 'live'" class="trib-rain" :class="{ nodata: !t.data }">
          <template v-if="t.data">实时雨强 {{ t.realtimeMmH }}mm/h × {{ live?.horizonHours || 6 }}h ≈ {{ t.equivalentMm }}mm 过程降雨</template>
          <template v-else>无气象观测数据，按 0mm 保守处理</template>
        </div>
        <div class="prob-bar">
          <div class="prob-fill" :class="'prob-' + levelKey(t.level)" :style="{ width: t.probabilityPct + '%' }"></div>
        </div>
        <div class="trib-features">
          <span v-for="(v, k) in t.features" :key="k" class="feat-chip">{{ k }}：{{ v }}</span>
        </div>
      </div>
    </div>
    <div v-else class="muted">加载中…</div>

    <!-- 支流风险→干流响应：把差异化概率真正用起来（参与禁航判定 + 触发在途反馈链路） -->
    <div v-if="linkMainline && mainline" class="mainline-box" :class="{ blocked: mainline.blocked }">
      <div class="ml-head">
        <span class="ml-title">干流响应（支流涨水顶托）</span>
        <span class="ml-badge" :class="mainline.blocked ? 'fused' : 'clear'">
          {{ mainline.blocked ? '已触发禁航' : (nearRedline ? '接近红线' : '通航正常') }}
        </span>
      </div>
      <div class="ml-nums">
        判定流速 {{ mainline.currentSpeedMs?.toFixed(2) }}m/s（人工基准 {{ mainline.manualCurrentSpeedMs?.toFixed(1) }} 与
        支流顶托 {{ mainline.tributaryCurrentMs?.toFixed(2) }} 取更不利者）· 红线 &gt;{{ mainline.redlines?.currentMaxMs }}m/s
      </div>
      <div v-if="mainline.blocked" class="ml-reason">
        {{ mainline.blockedReason }}；已向在途水运司机与调度大屏推送禁航反馈，可在上方禁航卡一键换公路方案
      </div>
      <div v-else-if="tribImpact?.highRiskTributaries?.length" class="ml-warn">
        高风险支流：{{ tribImpact.highRiskTributaries.join('、') }}；<template v-if="mode === 'live'">距禁航红线余量 {{ redlineHeadroomMs?.toFixed(2) }}m/s</template><template v-else>再增
        {{ Math.max(0, Math.round((redlineHeadroomMm ?? 0) / 5) * 5) }}mm 左右降雨将越线禁航</template>
      </div>
      <div class="ml-formula">{{ tribImpact?.formula }}</div>
    </div>

    <div class="trib-note">
      <template v-if="mode === 'live' && live?.dataAvailable">基于当前真实雨强：{{ summaryText }}</template>
      <template v-else-if="mode === 'live'">暂无真实气象数据，概率按 0mm 保守展示</template>
      <template v-else>同样的 {{ rainfall }}mm 降雨：{{ summaryText }}</template>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";

// 双模式：live = 真实气象源实时雨强驱动；manual = 手动剧本滑杆覆写
const mode = ref("live");
const rainfall = ref(45);
const tributaries = ref([]);
const loaded = ref(false);
const live = ref(null);           // /tributaries/live 评估结果（含源/等效换算/聚合）
const liveLoading = ref(false);
// 联动干流：支流概率聚合为顶托流速后参与 WaterRiskEngine 禁航红线判定（默认开）
const linkMainline = ref(true);
const mainline = ref(null);      // 沙盘 state.water（含判定流速/人工基准/支流耦合）
const tribImpact = ref(null);    // tributaryImpact 明细（顶托流速/高风险支流/公式，两模式同位字段）
let liveTimer = null;            // 实况模式 5 分钟自动刷新（后端气象源另有 60s 节流保护）

// 距越线还有多少降雨余量（实测斜率≈0.0085 m/s / mm，即 1m/s ≈ 118mm，取倒数换算）
const redlineHeadroomMm = computed(() => {
  if (!mainline.value || mainline.value.blocked) return null;
  return marginMs() * 118;
});
// 实况模式不能“再加 Nmm”（降雨由气象定），余量直接按流速差展示
const redlineHeadroomMs = computed(() => {
  if (!mainline.value || mainline.value.blocked) return null;
  return marginMs();
});
function marginMs() {
  return (mainline.value.redlines?.currentMaxMs ?? 2.0) - mainline.value.currentSpeedMs;
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString("zh-CN", { hour12: false });
}

const sliderStyle = computed(() => {
  const pct = rainfall.value;
  return {
    background: `linear-gradient(to right, #4f6df5 0%, #4f6df5 ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)`,
  };
});

const summaryText = computed(() => {
  if (!tributaries.value.length) return "";
  const sorted = [...tributaries.value].sort((a, b) => b.probabilityPct - a.probabilityPct);
  const top = sorted[0];
  const bottom = sorted[sorted.length - 1];
  return `${top.name}${top.riskType}概率 ${top.probabilityPct}%，而${bottom.name}仅 ${bottom.probabilityPct}%——特征向量决定风险画像`;
});

const nearRedline = computed(() => {
  if (!mainline.value || mainline.value.blocked) return false;
  const max = mainline.value.redlines?.currentMaxMs ?? 2.0;
  return mainline.value.currentSpeedMs > max * 0.9;
});

function levelKey(lv) {
  return lv === "高" ? "high" : lv === "中" ? "mid" : "low";
}

// 手动模式：按滑杆剧本降雨量预测（GET /tributaries）
async function load() {
  try {
    const { data } = await axios.get("/api/canal/tributaries", { params: { rainfallMm: rainfall.value } });
    tributaries.value = data.tributaries || [];
    loaded.value = true;
    if (linkMainline.value) await apply();
  } catch (e) {
    console.error("tributaries failed", e);
  }
}

// 实况模式：拉真实气象评估（GET /tributaries/live）
async function loadLive() {
  liveLoading.value = true;
  try {
    const { data } = await axios.get("/api/canal/tributaries/live");
    live.value = data;
    tributaries.value = data.tributaries || [];
    loaded.value = true;
    if (linkMainline.value) await applyLive();
  } catch (e) {
    console.error("tributary live failed", e);
  } finally {
    liveLoading.value = false;
  }
}

async function applyLive() {
  try {
    const { data } = await axios.post("/api/canal/tributaries/apply-live");
    mainline.value = data.water || null;
    tribImpact.value = data.tributaryImpact || null;
  } catch (e) {
    console.error("tributary apply-live failed", e);
  }
}

// 统一应用入口：关开关→/apply 传 0 解除联动（两模式同一路径）；开→按模式分发
async function apply() {
  try {
    if (!linkMainline.value) {
      const { data } = await axios.post("/api/canal/tributaries/apply", null, { params: { rainfallMm: 0 } });
      mainline.value = data.water || null;
      tribImpact.value = null;
      return;
    }
    if (mode.value === "live") {
      await applyLive();
    } else {
      const { data } = await axios.post("/api/canal/tributaries/apply", null, { params: { rainfallMm: rainfall.value } });
      mainline.value = data.water || null;
      tribImpact.value = data.tributaryImpact || null;
    }
  } catch (e) {
    console.error("tributary apply failed", e);
  }
}

function switchMode(m) {
  if (mode.value === m) return;
  mode.value = m;
  if (m === "live") loadLive();
  else load();
}

onMounted(() => {
  if (mode.value === "live") loadLive();
  else load();
  liveTimer = setInterval(() => {
    if (mode.value === "live") loadLive();
  }, 5 * 60 * 1000);
});
onBeforeUnmount(() => {
  if (liveTimer) clearInterval(liveTimer);
});
</script>

<style scoped>
.tributary-card {
  border: 1px solid rgba(79, 109, 245, 0.3);
}
.trib-hint {
  font-size: 11px;
  margin-bottom: 10px;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.mode-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.mode-btn {
  border: 1px solid rgba(79, 109, 245, 0.35);
  background: rgba(79, 109, 245, 0.06);
  color: #4f6df5;
  font-size: 11px;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 8px;
  cursor: pointer;
}
.mode-btn.active {
  background: #4f6df5;
  color: #fff;
}
.refresh-btn {
  border: 1px solid rgba(30, 50, 90, 0.18);
  background: rgba(255, 255, 255, 0.7);
  color: #5b6b85;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 8px;
  cursor: pointer;
}
.refresh-btn:disabled {
  opacity: 0.55;
  cursor: wait;
}
.mode-row .link-toggle {
  margin-left: auto;
}
.live-info {
  font-size: 10px;
  color: #5b6b85;
  margin-bottom: 8px;
  line-height: 1.6;
}
.live-info.warn {
  color: #d97706;
}
.trib-rain {
  font-size: 10px;
  color: #0e7490;
  margin-top: 3px;
  font-family: var(--mono, monospace);
}
.trib-rain.nodata {
  color: #8a94a6;
}
.slider-label {
  font-size: 11px;
  color: #64748f;
  white-space: nowrap;
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
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #4f6df5;
  cursor: grab;
}
.rain-value {
  min-width: 46px;
  text-align: right;
  font-weight: 700;
  font-family: var(--mono, monospace);
  color: #4f6df5;
}
.link-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #4f6df5;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
}
.link-toggle input {
  accent-color: #4f6df5;
  cursor: pointer;
}
.mainline-box {
  margin-top: 10px;
  border: 1px solid rgba(34, 163, 90, 0.35);
  border-radius: 10px;
  padding: 8px 10px;
  background: rgba(34, 163, 90, 0.07);
}
.mainline-box.blocked {
  border-color: rgba(225, 29, 72, 0.45);
  background: rgba(225, 29, 72, 0.07);
}
.ml-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ml-title {
  font-weight: 700;
  font-size: 12px;
  color: var(--text-h, #1c2a44);
}
.ml-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 8px;
}
.ml-badge.clear { color: #16a34a; background: rgba(34, 163, 90, 0.14); }
.ml-badge.fused { color: #e11d48; background: rgba(225, 29, 72, 0.14); }
.ml-nums {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 4px;
  font-family: var(--mono, monospace);
}
.ml-reason {
  font-size: 11px;
  color: #e11d48;
  margin-top: 4px;
  line-height: 1.6;
}
.ml-warn {
  font-size: 11px;
  color: #d97706;
  margin-top: 4px;
  line-height: 1.6;
}
.ml-formula {
  font-size: 10px;
  color: #8a94a6;
  margin-top: 4px;
}
.trib-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.trib-item {
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  padding: 8px 10px;
  background: rgba(255,255,255,0.60);
}
.trib-item.lv-high {
  border-color: rgba(225, 29, 72, 0.45);
}
.trib-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.trib-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-h, #1c2a44);
}
.trib-prob {
  font-size: 18px;
  font-weight: 800;
  font-family: var(--mono, monospace);
}
.prob-high { color: #e11d48; }
.prob-mid { color: #d97706; }
.prob-low { color: #16a34a; }
.trib-risk {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 2px;
}
.prob-bar {
  height: 5px;
  border-radius: 3px;
  background: rgba(30, 50, 90, 0.10);
  margin-top: 6px;
  overflow: hidden;
}
.prob-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s;
}
.prob-fill.prob-high { background: #e11d48; }
.prob-fill.prob-mid { background: #d97706; }
.prob-fill.prob-low { background: #22a35a; }
.trib-features {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.feat-chip {
  font-size: 10px;
  background: rgba(139, 148, 158, 0.15);
  color: #64748f;
  padding: 2px 6px;
  border-radius: 6px;
}
.trib-note {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #4f6df5;
  line-height: 1.6;
}
</style>
