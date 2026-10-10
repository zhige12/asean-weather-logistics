<template>
  <section class="card sandbox-card">
    <h3>
      决策沙盘
      <span class="route-state" :class="pushed ? (anyFused ? 'danger' : 'ok') : ''">
        {{ pushed ? (anyFused ? "存在熔断" : "全线可通行") : "等待气象数据" }}
      </span>
    </h3>
    <div class="muted data-source">
      实时气象源：可在「实时气象」卡片切换（CRA40 / Open-Meteo / 离线模拟）
    </div>
    <!-- 降雨来源如实标注：剧本值就说剧本值，只有真从 GOWFS 拉到才标真实预报 -->
    <div v-if="pushed" class="rain-source" :class="liveRain ? 'live' : 'script'">
      沙盘降雨来源：{{ dataSource }}
      <span v-if="dataSourceDetail" class="src-detail">（{{ dataSourceDetail }}）</span>
    </div>

    <!-- 剧本推送：暴雨 / 台风 / 两者叠加 三选一，切换场景会自动清掉上一幕的台风状态 -->
    <div class="row push-row">
      <button class="btn live" :disabled="liveBusy || simBusy" @click="pushLive">
        {{ liveBusy ? "拉取中…" : "拉取 GOWFS 数据" }}
      </button>
      <!-- 模拟入口只在本次页面点过「拉取 GOWFS 数据」且真实数据到手后才出现：
           用本地标记而非后端 liveLoaded（后者是单例字段，跨页面刷新会残留），
           保证“刷新后必须先点绿色按钮”才解锁调参 -->
      <button v-if="!isSimMode && simUnlocked" class="btn primary" :disabled="simBusy" @click="enterSim">
        {{ simBusy ? "切换中…" : "模拟" }}
      </button>
      <button v-if="isSimMode" class="btn typhoon" :disabled="simBusy" @click="exitSim">
        {{ simBusy ? "切换中…" : "退出模拟" }}
      </button>
      <button class="btn outline" v-if="pushed" @click="reset">重置沙盘</button>
    </div>

    <!-- 模式说明条：本次页面拉取到真实数据、或处于模拟模式时才显示（未拉取前不占位） -->
    <div v-if="isSimMode || simUnlocked" class="mode-bar" :class="isSimMode ? 'sim' : 'live'">
      <span v-if="isSimMode">
        <b>模拟模式</b>：气象参数可调，拖到阈值自动涌现灾害
        <span class="muted">（公路熔断 / 台风熔断 / 水运禁航，判定规则与真实数据完全相同）</span>
      </span>
      <span v-else>
        <b>真实数据</b>：GOWFS 预报，参数<b>不可调节</b>；点击「模拟」可切换为手动调参
      </span>
    </div>

    <!-- 拉不到真实预报时说明原因，避免看起来像"点了没反应" -->
    <div v-if="forecastMessage" class="forecast-warn">
      未取到真实预报：{{ forecastMessage }}（沙盘沿用当前数值）
    </div>
    <div v-else-if="forecastStatus && !forecastStatus.configured" class="forecast-warn">
      GOWFS 预报通道未启用（需配置 weather.real.base-url / token）
    </div>

    <!-- 快速模拟灾害注入：点一下即把该灾害写进路网触发熔断/重算，无需慢慢拖参数。
         入口只在点过「模拟」进入模拟模式后才出现：注入本质是模拟行为，真实数据模式下不给入口 -->
    <div v-if="isSimMode" class="quick-inject">
      <button class="btn quick-toggle" :class="{ open: quickOpen }" @click="quickOpen = !quickOpen">
        快速模拟灾害注入
        <span class="quick-count" v-if="activeScenarios.length">已注入 {{ activeScenarios.length }}</span>
        <span class="quick-caret">{{ quickOpen ? '▲' : '▼' }}</span>
      </button>
      <div v-if="quickOpen" class="quick-body">
        <div class="muted quick-tip">
          点击卡片直接注入对应灾害（等效于把参数拉过阈值），立即触发熔断与智能体重算；再点一次撤销。
        </div>
        <div v-if="scenarios.length" class="scenario-list">
          <button
            v-for="s in scenarios"
            :key="s.id"
            class="btn scenario-btn"
            :class="{ active: activeScenarios.includes(s.id) }"
            :disabled="quickBusy"
            @click="toggleScenario(s)"
          >
            <span class="scenario-name">{{ s.name }}</span>
            <span class="scenario-desc">{{ s.description }}</span>
          </button>
        </div>
        <div v-else class="muted">加载中…</div>
        <div class="row" style="margin-top:8px">
          <button class="btn muted" :disabled="quickBusy || !activeScenarios.length" @click="clearAllScenarios">
            清除全部风险
          </button>
        </div>
      </div>
    </div>

    <div v-if="pushed" class="section-list">
      <div v-for="s in sections" :key="s.id" class="section-item" :class="'st-' + s.status">
        <div class="section-head">
          <span class="section-name">{{ s.name }}</span>
          <span class="head-badges">
            <span
              v-if="s.roadRisk"
              class="risk-badge"
              :style="riskBadgeStyle(s.roadRisk.colorKey)"
              :title="s.roadRisk.advice"
            >
              路面风险 {{ s.roadRisk.score }} · {{ s.roadRisk.level }}
            </span>
            <span class="section-badge" :class="badgeClass(s)">{{ badgeText(s) }}</span>
          </span>
        </div>

        <!-- 降雨滑杆：真实模式下只读（禁用），模拟模式下可拖 -->
        <div class="slider-row">
          <span class="slider-label">降雨</span>
          <input
            type="range"
            min="0"
            max="120"
            step="1"
            class="rain-slider"
            :class="{ fused: s.fused }"
            :value="s.forecastMm"
            :disabled="!isSimMode"
            :style="sliderStyle(s)"
            @input="onSlide(s.id, $event)"
            @change="commitSlide"
          />
          <span class="rain-value" :class="{ danger: s.fused }">{{ Math.round(s.forecastMm) }}mm</span>
        </div>

        <!-- 气压 / 风速滑杆：拖到 气压≤990 且 风速≥60 自动涌现台风熔断 -->
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">气压</span>
          <input
            type="range" min="960" max="1020" step="1"
            class="rain-slider"
            :value="simParam(s.id, 'pressureHpa')"
            :style="pressureStyle(s)"
            @input="onParamSlide(s.id, 'pressureHpa', $event)"
            @change="commitParams"
          />
          <span class="rain-value">{{ Math.round(simParam(s.id, "pressureHpa")) }}hPa</span>
        </div>
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">风速</span>
          <input
            type="range" min="0" max="120" step="1"
            class="rain-slider"
            :value="simParam(s.id, 'windKph')"
            :style="windStyle(s)"
            @input="onParamSlide(s.id, 'windKph', $event)"
            @change="commitParams"
          />
          <span class="rain-value">{{ Math.round(simParam(s.id, "windKph")) }}km/h</span>
        </div>
        <!-- 温度滑杆：≤ 5℃ 配合雨雪→结冰；≥ 38℃ 高温→普货风险、≥ 30℃ 冷链货损 -->
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">温度</span>
          <input
            type="range" min="-5" max="45" step="0.5"
            class="rain-slider"
            :value="simParam(s.id, 'tempC')"
            :style="tempStyle(s)"
            @input="onParamSlide(s.id, 'tempC', $event)"
            @change="commitParams"
          />
          <span class="rain-value">{{ simParam(s.id, "tempC").toFixed(1) }}℃</span>
        </div>
        <!-- 湿度滑杆：≥ 85% 霉变/受潮，冷链货损与结冰判定用 -->
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">湿度</span>
          <input
            type="range" min="0" max="100" step="1"
            class="rain-slider"
            :value="simParam(s.id, 'humidityPct')"
            :style="humidStyle(s)"
            @input="onParamSlide(s.id, 'humidityPct', $event)"
            @change="commitParams"
          />
          <span class="rain-value">{{ Math.round(simParam(s.id, "humidityPct")) }}%</span>
        </div>
        <!-- 云量滑杆：≥ 70% 与气压/湿度共同构成转雨前兆 -->
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">云量</span>
          <input
            type="range" min="0" max="100" step="1"
            class="rain-slider"
            :value="simParam(s.id, 'cloudPct')"
            :style="cloudStyle(s)"
            @input="onParamSlide(s.id, 'cloudPct', $event)"
            @change="commitParams"
          />
          <span class="rain-value">{{ Math.round(simParam(s.id, "cloudPct")) }}%</span>
        </div>
        <!-- 能见度滑杆（公路侧）：越低越危险，越大越好；与风速联动会推高浪高→水运禁航 -->
        <div v-if="isSimMode" class="slider-row">
          <span class="slider-label">能见度</span>
          <input
            type="range" min="0" max="10000" step="100"
            class="rain-slider"
            :value="simParam(s.id, 'visibilityM')"
            :style="visStyle(s)"
            @input="onParamSlide(s.id, 'visibilityM', $event)"
            @change="commitParams"
          />
          <span class="rain-value" :class="{ danger: simParam(s.id, 'visibilityM') < 1000 }">
            {{ Math.round(simParam(s.id, "visibilityM")) }}m
          </span>
        </div>
        <!-- 台风双条件引导：只满足一个时提示还差多少，避免"拖了没反应" -->
        <div v-if="isSimMode && typhoonHint(s)" class="typhoon-hint" :class="{ armed: typhoonArmed(s) }">
          {{ typhoonHint(s) }}
        </div>

        <div class="section-info">
          <div class="info-line" :class="{ danger: s.fused }">
            {{ s.fused ? s.name + "已熔断" : s.statusText }}
          </div>
          <div class="info-sub">{{ s.boundaryText }}</div>
          <!-- 天气趋势预警：气压降/湿度升/云量增 命中两项以上→提前 1-2h 转雨预警 -->
          <div v-if="s.trend && s.trend.triggered" class="trend-banner">{{ s.trend.text }}</div>
          <!-- 路面综合风险：评分建议 + 命中因子（说清"到底危在哪"） -->
          <div v-if="s.roadRisk && s.roadRisk.factors && s.roadRisk.factors.length" class="risk-detail">
            <span class="risk-advice" :style="{ color: riskColor(s.roadRisk.colorKey) }">{{ s.roadRisk.advice }}</span>
            <ul class="risk-factors">
              <li v-for="(f, i) in s.roadRisk.factors" :key="i">{{ f }}</li>
            </ul>
          </div>
          <div class="info-sub muted">
            边坡：{{ s.slopeSoil }} · 含水量 {{ s.moisturePct }}% · 风险{{ s.riskLevel }}
          </div>
          <!-- 台风判据命中时标出，说明这次熔断是台风而非降雨造成 -->
          <div v-if="s.typhoonActive" class="info-sub typhoon-tag" :class="{ blocking: s.typhoonBlocking }">
            {{ s.typhoonVerdict || "台风模拟中" }}（{{ s.typhoonPressureHpa }}hPa / {{ s.typhoonWindKph }}km/h）
            <span v-if="!s.typhoonBlocking" class="muted">· 未达熔断级</span>
          </div>
          <!-- 官方预报返回的全部气象要素（仅"拉取 GOWFS 真实预报"后出现） -->
          <div v-if="elementList(s.elements).length" class="element-grid">
            <div v-for="el in elementList(s.elements, s.windDirectionText)" :key="el.key" class="element-item">
              <span class="el-label">{{ el.label }}</span>
              <span class="el-value">{{ el.value }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="muted empty-hint">
      点击上方「拉取 GOWFS 数据」获取真实气象，或收到推送后拖动滑块观察决策边界。<br />
      不是黑箱，是可交互的决策边界。
    </div>

    <!-- 货物安全指数（冷链货损）：温度+湿度+延误→货损风险与金额估计 -->
    <div v-if="pushed && cargoRisk" class="cargo-card" :class="'cargo-' + cargoRisk.colorKey">
      <div class="section-head">
        <span class="section-name">货物安全指数·{{ cargoRisk.cargoName }}{{ cargoRisk.coldChain ? "（冷链）" : "" }}</span>
        <span class="risk-badge" :style="riskBadgeStyle(cargoRisk.colorKey)">
          货损风险 {{ cargoRisk.score }} · {{ cargoRisk.level }}
        </span>
      </div>
      <div class="cargo-advice" :style="{ color: riskColor(cargoRisk.colorKey) }">{{ cargoRisk.advice }}</div>
      <ul v-if="cargoRisk.factors && cargoRisk.factors.length" class="risk-factors">
        <li v-for="(f, i) in cargoRisk.factors" :key="i">{{ f }}</li>
      </ul>
      <div v-if="cargoRisk.estimatedLossYuan > 0" class="cargo-loss">
        预估货损约 <b>￥{{ cargoRisk.estimatedLossYuan.toLocaleString() }}</b>
      </div>
    </div>

    <!-- 水运断面（场景B：双向切换的另一半） -->
    <div v-if="pushed && water" class="water-section" :class="{ blocked: water.blocked }">
      <div class="section-head">
        <span class="section-name">{{ water.name }}</span>
        <span class="section-badge" :class="water.blocked ? 'fused' : 'clear'">
          {{ water.blocked ? "已禁航" : "通航正常" }}
        </span>
      </div>

      <!-- 禁航弹窗横幅（演示步骤5.3：平陆运河变红 + 禁航提示） -->
      <div v-if="water.blocked" class="water-alert">
        {{ water.blockedReason || "通航条件超限" }}，{{ water.name }}禁航
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

      <!-- 水运参数滑杆：模拟模式下可直接拖到红线以下触发禁航 -->
      <div v-if="isSimMode" class="slider-row">
        <span class="slider-label">能见度</span>
        <input
          type="range" min="200" max="2000" step="50"
          class="rain-slider"
          :value="water.visibilityM"
          :style="waterSliderStyle(water.visibilityM, 200, 2000, water.redlines?.visibilityMinM, true)"
          @input="onWaterSlide('visibilityM', $event)"
          @change="commitWater"
        />
        <span class="rain-value" :class="{ danger: water.visibilityM < water.redlines?.visibilityMinM }">
          {{ Math.round(water.visibilityM) }}m
        </span>
      </div>

      <div class="row water-actions">
        <button v-if="!water.blocked" class="btn danger-outline" :disabled="waterBusy" @click="blockWater">
          模拟水运禁航（能见度 900m）
        </button>
        <button v-else class="btn outline" :disabled="waterBusy" @click="clearWater">
          恢复通航
        </button>
      </div>

      <!-- 双向切换方向提示 -->
      <div v-if="directionText" class="direction-line">{{ directionText }}</div>
    </div>

    <!-- 通关时效、支流风险并入决策沙盘：未点「模拟」只读展示真实数据，点「模拟」后才可编辑。
         通关时效的数据/逻辑仍在父组件，用作用域插槽把 simMode/pushed 传下去渲染；支流面板自带取数，直接内联。 -->
    <slot name="extra" :sim-mode="isSimMode" :pushed="pushed" />
    <TributaryPanel :sim-mode="isSimMode" :pushed="pushed" />

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

<script>
// 「模拟」解锁态放模块作用域：切标签/布局(移动⇄PC)/lite 等会让本组件重新挂载，
// 而沙盘数据来自后端单例（onMounted refresh 会恢复 pushed），若解锁态只是组件内 ref
// 就会被重置，表现为“数据还在、模拟按钮却消失”。模块作用域随整页刷新(JS 上下文重载)归零，
// 仍满足“刷新后必须先点绿色『拉取 GOWFS』才解锁调参”的演示约束。
const sandboxUnlock = { sim: false };
</script>

<script setup>
import { computed, onMounted, ref } from "vue";
import axios from "axios";
import TributaryPanel from "./TributaryPanel.vue";

const emit = defineEmits(["sandbox-change", "quick-inject"]);

const state = ref({ pushed: false, fuseThresholdMm: 80, riskProfile: "CONSERVATIVE", sections: [] });

const waterBusy = ref(false);
const liveBusy = ref(false);
const simBusy = ref(false);
/** 最近一次"拉取真实预报"的失败原因（成功则清空） */
const forecastMessage = ref("");

const pushed = computed(() => state.value.pushed);
const sections = computed(() => state.value.sections || []);
const anyFused = computed(() => sections.value.some((s) => s.fused));
const water = computed(() => state.value.water);
const dataSource = computed(() => state.value.dataSource || "演示剧本值（调度员手填）");
const dataSourceDetail = computed(() => state.value.dataSourceDetail || "");
/** 全局货损指数（冷链高温/高湿，后端 state.cargoRisk 下发） */
const cargoRisk = computed(() => state.value.cargoRisk || null);
const forecastStatus = computed(() => state.value.forecastStatus || null);
/** 当前降雨是否来自 GOWFS 真实预报（用于配色：真实=绿色，剧本=灰色） */
const liveRain = computed(() => (state.value.dataSource || "").includes("GOWFS"));

// ===== 模式与可调参数 =====
/** 当前模式：live=真实只读 / sim=模拟可调 */
const isSimMode = computed(() => state.value.mode === "sim");
/**
 * 「模拟」按钮是否解锁：只在本次页面真正点过「拉取 GOWFS 数据」并拿到真实值后置 true。
 * 不用后端 liveLoaded 直接判：那是单例服务的字段，上一次拉到后会跨页面刷新残留，
 * 导致一进来就能看到「模拟」。本标记随页面重载归零，必须重新点绿色按钮才解锁。
 */
const simUnlocked = ref(sandboxUnlock.sim);
/** 同时写模块作用域 + 组件响应式：重挂载后仍保留、整页刷新后归零 */
function setSimUnlocked(v) {
  sandboxUnlock.sim = v;
  simUnlocked.value = v;
}

/** 断面id → 参数前缀（ygg/mc） */
function prefixOf(sectionId) {
  return String(sectionId || "").toUpperCase().startsWith("YGG") ? "ygg" : "mc";
}
/** 要素键 → 后端扁平键后缀（与 setSimParams / flatSimParams 一一对应） */
const SIM_SUFFIX = {
  rainMm: "RainMm",
  pressureHpa: "PressureHpa",
  windKph: "WindKph",
  tempC: "TempC",
  humidityPct: "HumidityPct",
  cloudPct: "CloudPct",
  visibilityM: "VisibilityM",
};
/** 各要素的"什么都不触发"安全初值（服务端未下发时兜底） */
const SIM_DEFAULT = {
  rainMm: 0,
  pressureHpa: 1013,
  windKph: 20,
  tempC: 28,
  humidityPct: 60,
  cloudPct: 30,
  visibilityM: 10000,
};

/** 取某断面的模拟参数值（任意要素），服务端未下发时用安全初值 */
function simParam(sectionId, key) {
  const p = state.value.simParams;
  const fullKey = prefixOf(sectionId) + (SIM_SUFFIX[key] || key);
  const v = p ? p[fullKey] : undefined;
  return Number.isFinite(v) ? v : SIM_DEFAULT[key] ?? 0;
}

/** 台风双条件引导：只满足一个时提示还差多少（避免"拖了没反应"） */
function typhoonHint(s) {
  const prs = simParam(s.id, "pressureHpa");
  const wind = simParam(s.id, "windKph");
  const lowPrs = prs <= 990;
  const strongWind = wind >= 60;
  if (lowPrs && strongWind) return "已达台风判据：该口岸将熔断";
  if (lowPrs && !strongWind) return `气压已达标，风速还需 ≥60km/h（当前 ${Math.round(wind)}）`;
  if (!lowPrs && strongWind) return `风速已达标，气压还需 ≤990hPa（当前 ${Math.round(prs)}）`;
  return null;
}
function typhoonArmed(s) {
  const prs = simParam(s.id, "pressureHpa");
  const wind = simParam(s.id, "windKph");
  return prs <= 990 && wind >= 60;
}

/** 气压滑杆配色：越接近 990 越红 */
function pressureStyle(s) {
  const v = simParam(s.id, "pressureHpa");
  const pct = ((v - 960) / (1020 - 960)) * 100;
  const color = v <= 990 ? "#e11d48" : v <= 1000 ? "#d97706" : "#22a35a";
  return { background: `linear-gradient(to right, ${color} 0%, ${color} ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)` };
}
/** 风速滑杆配色：越接近 60 越红 */
function windStyle(s) {
  const v = simParam(s.id, "windKph");
  const pct = (v / 120) * 100;
  const color = v >= 60 ? "#e11d48" : v >= 40 ? "#d97706" : "#22a35a";
  return { background: `linear-gradient(to right, ${color} 0%, ${color} ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)` };
}
/** 水运参数配色：lowerIsWorse 表示值越小越危险（如能见度） */
function waterSliderStyle(v, min, max, redline, lowerIsWorse) {
  const pct = ((v - min) / (max - min)) * 100;
  const breach = lowerIsWorse ? v < redline : v > redline;
  const near = lowerIsWorse ? v < redline * 1.2 : v > redline * 0.8;
  const color = breach ? "#e11d48" : near ? "#d97706" : "#22a35a";
  return { background: `linear-gradient(to right, ${color} 0%, ${color} ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)` };
}

/** 通用渐变条：按百分比 + 颜色渲染滑杆已填充部分 */
function gradFill(pct, color) {
  const p = Math.max(0, Math.min(100, pct));
  return { background: `linear-gradient(to right, ${color} 0%, ${color} ${p}%, rgba(30,50,90,0.14) ${p}%, rgba(30,50,90,0.14) 100%)` };
}
/** 温度配色：≤5℃ 结冰蓝 / ≥38℃ 高温红 / ≥30℃ 橙 / 否则绿 */
function tempStyle(s) {
  const v = simParam(s.id, "tempC");
  const color = v <= 5 ? "#2563eb" : v >= 38 ? "#e11d48" : v >= 30 ? "#d97706" : "#22a35a";
  return gradFill(((v - (-5)) / (45 - (-5))) * 100, color);
}
/** 湿度配色：≥85% 霉变橙红 / 否则绿 */
function humidStyle(s) {
  const v = simParam(s.id, "humidityPct");
  const color = v >= 95 ? "#e11d48" : v >= 85 ? "#d97706" : "#22a35a";
  return gradFill(v, color);
}
/** 云量配色：≥70% 转阴倾向橙 / 否则绿 */
function cloudStyle(s) {
  const v = simParam(s.id, "cloudPct");
  const color = v >= 90 ? "#d97706" : v >= 70 ? "#65a30d" : "#22a35a";
  return gradFill(v, color);
}
/** 能见度配色：越低越危险（<500 红 / <1000 橙 / <2000 黄 / 否则绿） */
function visStyle(s) {
  const v = simParam(s.id, "visibilityM");
  const color = v < 500 ? "#e11d48" : v < 1000 ? "#d97706" : v < 2000 ? "#ca8a04" : "#22a35a";
  return gradFill((v / 10000) * 100, color);
}
/** 路面综合评分配色：按 colorKey 取色 */
const RISK_COLOR = { green: "#16a34a", yellow: "#ca8a04", orange: "#ea580c", red: "#e11d48" };
function riskColor(colorKey) {
  return RISK_COLOR[colorKey] || "#16a34a";
}
function riskBadgeStyle(colorKey) {
  const c = riskColor(colorKey);
  return { color: c, borderColor: c, background: c + "1f" };
}

/** 官方要素中文名 + 单位（接口变量说明见开发指南 4.1） */
const ELEMENT_META = {
  PRE: { label: "降水量", unit: "mm" },
  TMP: { label: "温度", unit: "℃" },
  TMAX: { label: "最高温", unit: "℃" },
  TMIN: { label: "最低温", unit: "℃" },
  RH: { label: "相对湿度", unit: "%" },
  PRS: { label: "气压", unit: "hPa" },
  VIS: { label: "能见度", unit: "km" },
  TCC: { label: "总云量", unit: "%" },
  LCC: { label: "低云量", unit: "%" },
  windKph: { label: "风速", unit: "km/h" },
  visibilityM: { label: "能见度", unit: "m" },
};
/** 展示顺序：先天气核心量，风速/能见度修正值在后 */
const ELEMENT_ORDER = ["TMP", "TMAX", "TMIN", "PRE", "RH", "PRS", "TCC", "LCC", "windKph", "visibilityM", "VIS"];

/** 把后端下发的要素对象转成有序列表，供模板渲染；风速项会并上风向中文方位 */
function elementList(elements, windText) {
  if (!elements) return [];
  const keys = ELEMENT_ORDER.filter((k) => elements[k] !== undefined && elements[k] !== null);
  return keys.map((k) => {
    const meta = ELEMENT_META[k] || { label: k, unit: "" };
    let value = formatElement(elements[k], meta.unit);
    // 风向与风速合成一项显示："3.0km/h 东北"，比单独占一格更紧凑
    if (k === "windKph" && windText) {
      value = value + " " + windText;
    }
    return { key: k, label: meta.label, value };
  });
}
function formatElement(v, unit) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "—";
  // 能见度按米显示取整，其余保留一位小数更贴合气象读数习惯
  const text = unit === "m" ? String(Math.round(n)) : n.toFixed(1);
  return text + (unit ? unit : "");
}

// 双向切换方向（3.4）：公路⇄水运互为备选
const directionText = computed(() => {
  const map = {
    road_to_water: "双向切换：公路已熔断 → 系统已分析水运方案（切水运/绕行芒街/等待）",
    water_to_road: "双向切换：水运已禁航 → 系统已分析公路方案（切公路/锚泊等待/绕行其他口岸）",
    dual_risk: "双线风险：公路与水运同时受阻 → 建议原地等待/延迟发车",
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

/** 进入模拟模式：气象参数转为可调 */
async function enterSim() {
  simBusy.value = true;
  try {
    const { data } = await axios.post("/api/sandbox/sim/enter");
    applyState(data);
  } catch (e) {
    console.error("sandbox sim enter failed", e);
  } finally {
    simBusy.value = false;
  }
}

/** 退出模拟模式：回到 GOWFS 真实数据（只读） */
async function exitSim() {
  simBusy.value = true;
  try {
    const { data } = await axios.post("/api/sandbox/sim/exit");
    applyState(data);
    // 退出模拟会回到真实数据（后端重拉 GOWFS），拉到则继续保持「模拟」可用
    if (data?.liveLoaded === true) setSimUnlocked(true);
  } catch (e) {
    console.error("sandbox sim exit failed", e);
  } finally {
    simBusy.value = false;
  }
}

/** 拖动参数滑杆时本地暂存，松手后提交（与降雨滑杆同样的防抖策略） */
const pendingParams = ref({});
function onParamSlide(sectionId, key, ev) {
  pendingParams.value[prefixOf(sectionId) + (SIM_SUFFIX[key] || key)] = Number(ev.target.value);
  // 立即回显：不回显的话拖动时数字不动，看起来像卡住
  if (!state.value.simParams) state.value.simParams = {};
  state.value.simParams = { ...state.value.simParams, ...pendingParams.value };
}
async function commitParams() {
  const body = { ...pendingParams.value };
  pendingParams.value = {};
  if (!Object.keys(body).length) return;
  try {
    const { data } = await axios.post("/api/sandbox/sim/params", body);
    applyState(data);
  } catch (e) {
    console.error("sandbox sim params failed", e);
  }
}

/** 拖动水运参数滑杆 */
const pendingWater = ref({});
function onWaterSlide(key, ev) {
  pendingWater.value[key] = Number(ev.target.value);
}
async function commitWater() {
  const body = { ...pendingWater.value };
  pendingWater.value = {};
  if (!Object.keys(body).length) return;
  try {
    const { data } = await axios.post("/api/sandbox/water", body);
    applyState(data);
  } catch (e) {
    console.error("sandbox water failed", e);
  }
}

/** 拉取 GOWFS 真实数据作为沙盘降雨输入；失败时保留原值并提示原因 */
async function pushLive() {
  liveBusy.value = true;
  forecastMessage.value = "";
  try {
    const { data } = await axios.post("/api/sandbox/push-live");
    applyState(data);
    // 只有真拉到真实预报（后端 liveLoaded=true）才解锁「模拟」入口
    if (data?.liveLoaded === true) setSimUnlocked(true);
    forecastMessage.value = data?.forecastMessage || "";
  } catch (e) {
    forecastMessage.value = "接口调用失败：" + (e?.message || "未知错误");
    console.error("sandbox push-live failed", e);
  } finally {
    liveBusy.value = false;
  }
}

async function reset() {
  try {
    const { data } = await axios.post("/api/sandbox/reset");
    applyState(data);
    // 重置后沙盘回到“未拉取”空态：重新锁上「模拟」，必须再点一次绿色按钮才解锁
    setSimUnlocked(false);
    forecastMessage.value = "";
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

// ===== 快速模拟灾害注入（把 App 原独立面板内聚进沙盘，点卡片直接注入/撤销）=====
const scenarios = ref([]);
const activeScenarios = ref([]);
const quickOpen = ref(false);
const quickBusy = ref(false);

/** 拉取预置灾害场景；后端不可用时退回到与 WeatherSimulator 同构的本地清单 */
async function loadScenarios() {
  try {
    const { data } = await axios.get("/api/weather/scenarios");
    scenarios.value = data || [];
  } catch (e) {
    scenarios.value = [
      { id: "RAIN_YGG", name: "暴雨·友谊关", description: "12h 降雨量 ≥80mm 即熔断（本次注入 85mm），等效道路中断" },
      { id: "FOG_MC", name: "大雾·芒街", description: "能见度 <500m 判定浓雾（本次注入 400m），芒街口岸通关受阻" },
      { id: "LANDSLIDE", name: "泥石流·越北山区", description: "谅山—北江段泥石流风险>0.7，直接切断（地质指标，不由滑杆驱动）" },
      { id: "HEAT", name: "高温·冷链", description: "北江—河内段 ≥38°C 记高温（冷链货损自 ≥30°C 起累积）" },
      { id: "TYPHOON", name: "台风·广宁沿海", description: "台风熔断：气压≤990hPa 且 风速≥60km/h（本次注入 985hPa/65km/h）" },
    ];
  }
}

/** 切换某灾害场景：未注入则 POST 注入、已注入则 DELETE 撤销，随后刷新沙盘并通知父组件重算 */
async function toggleScenario(s) {
  if (quickBusy.value) return;
  quickBusy.value = true;
  const wasActive = activeScenarios.value.includes(s.id);
  try {
    if (wasActive) {
      await axios.delete(`/api/weather/scenario/${s.id}`);
      activeScenarios.value = activeScenarios.value.filter((id) => id !== s.id);
    } else {
      await axios.post(`/api/weather/scenario/${s.id}`);
      activeScenarios.value = [...activeScenarios.value, s.id];
    }
    await refresh();
    emit("quick-inject", { id: s.id, name: s.name, active: !wasActive });
  } catch (e) {
    console.error("quick inject failed", e);
    alert("场景操作失败");
  } finally {
    quickBusy.value = false;
  }
}

/** 清除全部已注入风险 */
async function clearAllScenarios() {
  if (quickBusy.value) return;
  quickBusy.value = true;
  try {
    await axios.delete("/api/weather/risk");
    activeScenarios.value = [];
    await refresh();
    emit("quick-inject", { clearAll: true });
  } catch (e) {
    console.error("clear inject failed", e);
  } finally {
    quickBusy.value = false;
  }
}

onMounted(() => {
  refresh();
  loadScenarios();
});

// 供父组件调用：外部（配置器改阈值）触发后刷新沙盘
defineExpose({ refresh });
</script>

<style scoped>
.sandbox-card {
  border: 1px solid rgba(22, 163, 74, 0.35);
}
/* 快速模拟灾害注入：沙盘内的可折叠快捷入口 */
.quick-inject {
  margin: 8px 0 10px;
}
.quick-toggle {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-weight: 600;
  background: linear-gradient(135deg, rgba(225, 29, 72, 0.08), rgba(244, 63, 94, 0.06));
  border: 1px solid rgba(225, 29, 72, 0.35);
  color: #be123c;
}
.quick-toggle.open {
  border-color: rgba(225, 29, 72, 0.55);
}
.quick-count {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 10px;
  background: rgba(225, 29, 72, 0.14);
  color: #be123c;
}
/* 展开三角钉在按钮最右边，文案居中不受它影响 */
.quick-caret {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 11px;
  color: #be123c;
}
.quick-body {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.5);
}
.quick-tip {
  font-size: 11px;
  line-height: 1.5;
  margin-bottom: 8px;
}
.scenario-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.btn.scenario-btn {
  display: block;
  width: 100%;
  text-align: left;
  background: #fff;
  border: 1px solid rgba(217, 119, 6, 0.35);
  color: #7c4a03;
  border-radius: 9px;
  padding: 8px 10px;
  line-height: 1.4;
}
.btn.scenario-btn:hover {
  border-color: rgba(217, 119, 6, 0.6);
}
.btn.scenario-btn.active {
  background: linear-gradient(135deg, #e11d48, #f43f5e);
  color: #fff;
  border-color: #e11d48;
  box-shadow: 0 4px 18px rgba(225, 29, 72, 0.35);
}
.scenario-name {
  font-weight: 600;
}
.scenario-desc {
  display: block;
  font-size: 11px;
  color: #8b99b5;
  margin-top: 2px;
}
.btn.scenario-btn.active .scenario-desc {
  color: rgba(255, 255, 255, 0.85);
}
.data-source {
  font-size: 11px;
  margin-bottom: 8px;
}
/* 降雨来源标注：真实预报（绿）与剧本值（灰）一眼可分 */
.rain-source {
  font-size: 11px;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 8px;
  border: 1px solid transparent;
}
.rain-source.live {
  background: rgba(22, 163, 74, 0.12);
  border-color: rgba(22, 163, 74, 0.4);
  color: #15803d;
}
.rain-source.script {
  background: rgba(120, 120, 120, 0.1);
  border-color: rgba(120, 120, 120, 0.3);
  color: #6b7280;
}
.src-detail {
  opacity: 0.75;
}
.forecast-warn {
  font-size: 11px;
  padding: 5px 8px;
  margin-bottom: 8px;
  border-radius: 4px;
  background: rgba(234, 179, 8, 0.12);
  border: 1px solid rgba(234, 179, 8, 0.35);
  color: #a16207;
}
.btn.live {
  background: #16a34a;
  color: #fff;
}
.btn.live:disabled {
  opacity: 0.6;
}
/* 模式说明条：真实(绿) / 模拟(紫)，一眼分辨当前能不能调参 */
.mode-bar {
  font-size: 11px;
  padding: 5px 8px;
  border-radius: 4px;
  margin-bottom: 8px;
  border: 1px solid transparent;
}
.mode-bar.live {
  background: rgba(22, 163, 74, 0.1);
  border-color: rgba(22, 163, 74, 0.35);
  color: #15803d;
}
.mode-bar.sim {
  background: rgba(124, 58, 237, 0.1);
  border-color: rgba(124, 58, 237, 0.35);
  color: #6d28d9;
}
.slider-label {
  min-width: 42px;
  font-size: 11px;
  color: #6b7280;
}
.typhoon-hint {
  font-size: 10px;
  margin-bottom: 10px;
}
.typhoon-tag {
  margin-top: 3px;
  padding: 3px 6px;
  border-radius: 3px;
  background: rgba(124, 58, 237, 0.12);
  color: #6d28d9;
}
.typhoon-tag.blocking {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}
/* 官方气象要素网格：两列紧凑排布，避免挤占断面卡片高度 */
.element-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 10px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed rgba(120, 120, 120, 0.25);
  font-size: 11px;
}
.element-item {
  display: flex;
  justify-content: space-between;
  gap: 6px;
}
.el-label {
  color: #6b7280;
}
.el-value {
  color: #111827;
  font-weight: 600;
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
/* 头部多徽章排布 */
.head-badges {
  display: flex;
  align-items: center;
  gap: 6px;
}
.risk-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid transparent;
  white-space: nowrap;
}
/* 天气趋势预警条（事前预防） */
.trend-banner {
  margin-top: 6px;
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(59, 130, 246, 0.12);
  border: 1px solid rgba(59, 130, 246, 0.4);
  color: #1d4ed8;
}
/* 路面综合风险明细 */
.risk-detail {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed rgba(120, 120, 120, 0.25);
}
.risk-advice {
  font-size: 11px;
  font-weight: 700;
}
.risk-factors {
  margin: 3px 0 0;
  padding-left: 16px;
  font-size: 11px;
  color: #5b6b85;
  line-height: 1.6;
}
/* 货物安全指数卡 */
.cargo-card {
  margin-top: 10px;
  border: 1px solid rgba(30, 50, 90, 0.14);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.6);
}
.cargo-card.cargo-red {
  border-color: rgba(225, 29, 72, 0.5);
  background: rgba(225, 29, 72, 0.06);
}
.cargo-card.cargo-orange {
  border-color: rgba(234, 88, 12, 0.5);
  background: rgba(234, 88, 12, 0.06);
}
.cargo-advice {
  font-size: 12px;
  font-weight: 700;
  margin-top: 4px;
}
.cargo-loss {
  margin-top: 4px;
  font-size: 12px;
  color: #b91c1c;
}
.cargo-loss b {
  font-family: var(--mono, monospace);
}
</style>
