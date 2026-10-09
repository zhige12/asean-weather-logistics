<template>
  <section class="card agent-card">
    <h3>
      AI 决策分析 · 多智能体协同
      <span v-if="triggerTag" class="route-state trigger-tag" :class="trigger === 'hazard' ? 'warn' : ''">
        {{ triggerTag }}
      </span>
      <span v-if="result" class="route-state ai-chip" :class="aiChip.cls" :title="aiChip.title">
        {{ aiChip.text }}
      </span>
    </h3>
    <div class="muted agent-hint">
      三个智能体协同工作：风险研判 → 方案生成 → 触达，前一个的输出作为后一个的输入
    </div>

    <div class="row">
      <button class="btn primary" :disabled="running" @click="runAnalysis(false)">
        {{ running ? "智能体推理中…" : "AI 决策分析" }}
      </button>
      <button class="btn outline" :disabled="running" @click="runAnalysis(true)" title="读取预生成缓存，演示 2 秒出结果">
        缓存模式
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
            <span v-else-if="a.status === 'DONE'">{{ a.elapsedMs ? (a.elapsedMs / 1000).toFixed(1) + "s" : "完成" }}</span>
            <span v-else>待命</span>
            <!-- 逐智能体标注真实生成通道：三段文案可能来自不同通道，汇总标签只取最远程的一级，
                 这里必须能拆开自证（否则「两个本地 + 一个在线」会被汇总成一个笼统标签） -->
            <span v-if="a.aiChannel" class="agent-chan" :class="channelClass(a.aiChannel)"
                  :title="'本段输出来源：' + (a.aiChannelLabel || a.aiChannel)">
              {{ channelShort(a.aiChannel) }}
            </span>
          </div>
          <div v-if="a.output" class="agent-output">{{ a.output }}</div>
        </div>
        <div v-if="i < agentFlow.length - 1" class="agent-arrow" :class="{ lit: agentFlow[i].status === 'DONE' }">→</div>
      </template>
    </div>

    <template v-if="result">
      <!-- 风险研判输出 -->
      <div class="block">
        <div class="block-title">风险研判 · 决策解释</div>
        <div class="explain-box">
          <!-- 熔断即标红：台风致熔断时 fused 同样为 true，不能被漏掉 -->
          <div class="explain-item" :class="{ danger: result.explanation?.ygg?.fused }">
            {{ result.explanation?.ygg?.verdict }}
          </div>
          <div class="explain-item" :class="{ danger: result.explanation?.mc?.fused }">
            {{ result.explanation?.mc?.verdict }}
          </div>
          <div class="explain-boundary">{{ result.explanation?.boundary }}</div>
          <div v-if="result.explanation?.llm" class="llm-chip" :title="'本段研判输出来源：' + (result.agents?.[0]?.aiChannelLabel || result.aiChannelLabel)">
            模型研判：{{ result.explanation.llm.riskLevel }}风险 · 决策边界 {{ result.explanation.llm.decisionBoundary }}
          </div>
        </div>
      </div>

      <!-- 风险已变化：旧结果立即失效提示（两种模板都需要，独立于方案生成块） -->
      <div v-if="stale" class="stale-banner">
        风险状态已变化，以下旧方案已失效，正在重新分析绕行路线…
      </div>

      <!-- 三方案对比（六维分析 + 双向切换）：遇风险改路线模板专属，
           刚开始导航（常态）不展示——没有风险时不存在绕行/联运/等待的取舍 -->
      <div v-if="isReroute" class="block">
        <div class="block-title">
          方案生成 · 六维分析 + 双向切换
          <!-- 司机在途时标注行程进度：方案是按车当前位置算的，不是纸上对比 -->
          <span v-if="result.progressRatio >= 0" class="route-state progress-tag">
            行程已走 {{ Math.round(result.progressRatio * 100) }}%
          </span>
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
            :class="{ recommended: p.id === recommendedId, selected: p.id === selectedPlan, na: p.notApplicable }"
            @click="selectPlan(p.id)"
          >
            <div class="plan-head">
              <span class="plan-badge">{{ p.id }}</span>
              <span class="plan-name">{{ p.name }}</span>
              <span v-if="p.id === recommendedId" class="rec-tag">推荐</span>
              <span v-if="p.notApplicable" class="na-tag">对本行程不适用</span>
            </div>
            <div class="plan-metrics">
              <span :class="deltaClass(p.extraHours)">{{ fmtDelta(p.extraHours, "h") }}</span>
              <span :class="costClass(p.costDeltaYuan)">{{ fmtDelta(p.costDeltaYuan, "元") }}</span>
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
                多式联运“一口价”
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
        <div class="recommend-box">{{ result.recommendation }}</div>
      </div>

      <!-- 开始导航模板（常态）：AI 决策分析下的多条路线，选一条派单给司机 -->
      <div v-if="!isReroute && (dispatchCandidates.length || dispatchCanal)" class="block">
        <div class="block-title">开始导航派单 · 从以下路线选一条派给司机</div>
        <!-- 常态 AI 结论（方案生成智能体照常计算一次，只不展示风险对比表） -->
        <div class="recommend-box">{{ result.recommendation }}</div>
        <div class="dcand-list">
          <label
            v-for="c in dispatchCandidates"
            :key="c.key"
            class="dcand-row"
            :class="{ sel: c.key === dispatchSelected }"
          >
            <input type="radio" name="agent-dispatch-route" :value="c.key" v-model="dispatchSelected" />
            <span class="dcand-radio" :class="{ on: c.key === dispatchSelected }"></span>
            <span class="dcand-body">
              <span class="dcand-title">
                {{ c.label }}
                <span v-if="c.key === 'recommended'" class="rec-tag">推荐</span>
              </span>
              <span class="dcand-via">{{ viaText(c) }}</span>
              <span class="dcand-meta">
                {{ c.hours }}h · {{ c.distanceKm }}km ·
                {{ c.riskCount > 0 ? c.riskCount + " 风险" : "✓ 无风险" }}
              </span>
            </span>
            <span
              class="risk-chip"
              :class="c.hazardProbability >= 0 ? 'risk-' + probKey(c.hazardProbability) : ''"
              :title="c.hazardProbability >= 0 && c.hazardProbability < 15 && !c.hazardOccurred ? '灾害概率 ' + c.hazardProbability + '%（低于阈值视为通畅）' : ''"
            >
              {{ probText(c) }}
            </span>
          </label>
          <!-- 陆水联运（坐船出境）：目的地在越南侧时后端并带回 canalOption；
               禁航时整行标红、不可选，并说明情况——不能一边禁航一边推荐坐船派单 -->
          <label
            v-if="dispatchCanal"
            class="dcand-row canal-row"
            :class="{ sel: dispatchSelected === 'canal', blocked: dispatchCanal.canalBlocked }"
          >
            <input type="radio" name="agent-dispatch-route" value="canal" v-model="dispatchSelected"
                   :disabled="dispatchCanal.canalBlocked" />
            <span class="dcand-radio" :class="{ on: dispatchSelected === 'canal' }"></span>
            <span class="dcand-body">
              <span class="dcand-title">
                {{ dispatchCanal.label }}
                <span v-if="!dispatchCanal.canalBlocked" class="rec-tag">可坐船</span>
                <span v-else class="rec-tag blocked">已禁航</span>
              </span>
              <span v-if="dispatchCanal.canalBlocked" class="canal-blocked-note">
                {{ dispatchCanal.canalBlockedReason || '平陆运河触发通航安全红线' }}，水运方案暂不可派单，请选公路候选
              </span>
              <span class="dcand-via">{{ dispatchCanal.via }}</span>
              <span class="dcand-meta">
                {{ dispatchCanal.hours }}h · {{ dispatchCanal.distanceKm }}km · ¥{{ dispatchCanal.costYuan }}
              </span>
            </span>
            <span v-if="!dispatchCanal.canalBlocked" class="risk-chip risk-low">水运</span>
            <span v-else class="risk-chip risk-high">禁航</span>
          </label>
        </div>
        <div class="row dispatch-row">
          <button class="btn confirm" :disabled="dispatching || !dispatchSelected" @click="dispatchToDriver">
            {{ dispatching ? "派单中…" : "确认选择此路线并派单给司机" }}
          </button>
          <!-- 派单结果就地反馈：成功时时间线/派单卡片在页面其他区域，没点过的人注意不到 -->
          <span v-if="dispatchFeedback" class="dispatch-ok">{{ dispatchFeedback }}</span>
        </div>
        <div class="muted dispatch-hint">
          派单后司机端自动切物流任务模式：预览订单（货物/重量/起终点/路线/AI 分析）→ 确认接单按选定路线自动导航，或拒单退回普通导航。
        </div>
      </div>

      <!-- 触达预览：任务变更角色指令，改路线模板专属；常态下触达状态只看状态条 -->
      <div v-if="isReroute" class="block">
        <div class="block-title">触达智能体 · 角色专属指令（{{ result.outreachPreview?.count || 0 }} 个角色）</div>
        <div class="touch-list">
          <div v-for="t in result.outreachPreview?.targets || []" :key="t.role" class="touch-item">
            <span class="touch-role">{{ t.name }}</span>
            <span class="touch-instruction">{{ t.instruction }}</span>
          </div>
        </div>
      </div>

      <!-- 调度决策（改路线模板）：确认方案 → 下发任务变更 -->
      <div v-if="isReroute" class="row dispatch-row">
        <button class="btn confirm" :disabled="dispatching || !selectedPlan || stale" @click="dispatch">
          {{ dispatching ? "下发中…" : stale ? "等待新分析结果…" : "确认切换方案" + (selectedPlan || "") + " · 下发任务变更指令" }}
        </button>
      </div>
      <div v-if="isReroute" class="muted dispatch-hint">
        司机收到的是执行指令和权益保障包，不是"你想走公路还是水运"——切换运输方式是调度端的决策权限。
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref } from "vue";
import axios from "axios";

const emit = defineEmits(["dispatched", "dispatch-task"]);

const props = defineProps({
  originId: { type: String, default: "NN" },
  destinationId: { type: String, default: "HN" },
});

const running = ref(false);
const dispatching = ref(false);
const result = ref(null);
const selectedPlan = ref("");
// 本次分析的触发来源：normal=派单前常态 / hazard=灾害触发重算 / manual=手动
const trigger = ref("manual");
const triggerTag = computed(() => {
  if (trigger.value === "normal") return "派单前 · 常态分析";
  if (trigger.value === "hazard") return "灾害触发 · 熔断重算";
  return "";
});

// ---------- AI 溯源标签 ----------
// 旧写法是 `aiPowered ? "本地大模型" : "规则模板"`，而 aiPowered 的真实含义只是
// “模型返回了非空文本”——本地 Ollama 一抖（超时 / 显存被占 / 连续 2 次失败进入 2 分钟
// 冷却）就会静默降级到在线 DeepSeek，屏幕上的「本地大模型」当场变成假话，
// 而 README 里写的“数据不出境”是最容易被评委追问的一句。现在只认后端回报的通道。
const CHANNEL_KIND = { local: "local", online: "online", unrecorded: "unrecorded", template: "template" };
function channelKind(ch) {
  const s = String(ch || "");
  return CHANNEL_KIND[s.split(":")[0]] || "template";
}
function channelClass(ch) {
  return "chip-" + { local: "local", online: "remote", unrecorded: "unknown", template: "template" }[channelKind(ch)];
}
function channelShort(ch) {
  return { local: "本地", online: "在线", unrecorded: "未记录", template: "模板" }[channelKind(ch)];
}
const aiChip = computed(() => {
  const r = result.value;
  if (!r) return { text: "", cls: "", title: "" };
  const ch = String(r.aiChannel || "");
  const label = r.aiChannelLabel || "";
  const kind = channelKind(ch);
  // 可见标签只留两字（本地 / 在线 / 缓存 / 模板），完整溯源说明放 hover 提示，
  // 既满足“简洁”又不丢可核验信息（评委悬停仍能看到真实通道与降级链）
  if (r.cacheHit) {
    if (kind === "unrecorded" || kind === "template") {
      return {
        text: "缓存",
        cls: "chip-unknown",
        title: "这条结果来自赛前写进 decision-cache 的静态文案，生成时未记录真实通道，不能证明是本地模型产出。\n点「AI 决策分析」可现场重跑一次真实推理。",
      };
    }
    return {
      text: "缓存",
      cls: channelClass(ch),
      title: "命中 decision-cache 预生成结果，缓存内记录了生成时的通道：" + label,
    };
  }
  if (kind === "local") {
    return { text: "本地", cls: "chip-local", title: "本次推理在本机 Ollama 完成，数据不出境：" + label };
  }
  if (kind === "online") {
    return {
      text: "在线",
      cls: "chip-remote",
      title: "本机 Ollama 不可用，已静默降级到公网大模型。\n提示词里的路线、货值、口岸状态已离开本机，不属于本地推理：" + label,
    };
  }
  return {
    text: "模板",
    cls: "chip-template",
    title: "本次没有模型参与：方案卡里的数字由确定性决策引擎实时重算（真路网算路 + 熔断/禁航阈值 + 成本模型），\n推荐语那段文字是固定模板。",
  };
});
// 风险变化后旧结果立即标记过期（不再展示旧推荐），并自动重算新方案
const stale = ref(false);
const rerunPending = ref(false);
// 开始导航模板：供调度员比选并派单的多路线候选（含逐条灾害概率）
const dispatchCandidates = ref([]);
const dispatchSelected = ref("");
// 公路候选之外的「可坐船出境」（平陆运河陆水联运）推荐位：目的地在越南侧时后端会并带回
const dispatchCanal = ref(null);
// 派单就地反馈文案（成功显示几秒后自清）；与 dispatching 一起构成按钮全链路状态
const dispatchFeedback = ref("");
let dispatchFbTimer = null;

// 三智能体实时状态（SSE agent-status 事件驱动 + 结果回填）
const agentFlow = ref([
  { id: "risk-assessment", name: "风险研判智能体", icon: "🔵", status: "IDLE", elapsedMs: 0, output: "" },
  { id: "plan-generation", name: "方案生成智能体", icon: "🟢", status: "IDLE", elapsedMs: 0, output: "" },
  { id: "outreach", name: "触达智能体", icon: "🟡", status: "IDLE", elapsedMs: 0, output: "" },
]);

const recommendedId = computed(() => {
  if (!result.value?.plans) return "";
  const rec = result.value.agents?.find((a) => a.id === "plan-generation");
  // 兜底也要跳过被进度闸门禁用的方案（后端正常都会给出 recommended，这里防旧缓存）
  return rec?.detail?.recommended
    || (result.value.plans.find((p) => p.id === "B" && !p.notApplicable) ? "B" : "A");
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
  // 不写死口岸名：熔断口岸随场景变化（暴雨断友谊关、台风断芒街），
  // 写死会让"台风断芒街"时横幅仍提示"绕行芒街"（绕向已断的口岸）
  const map = {
    road_to_water: {
      title: "双向切换：公路熔断 → AI 已分析水运方案",
      sub: "公路口岸熔断，可选：切水运（陆水联运）/ 公路绕行另一口岸 / 原地等待",
    },
    water_to_road: {
      title: "双向切换：水运禁航 → AI 已分析公路方案",
      sub: `平陆运河${reason}，可选：切公路（绕行可用口岸）/ 锚泊等待`,
    },
    dual_risk: {
      title: "双线风险：公路与水运同时受阻",
      sub: "两条线各自风险均不可控，建议原地等待 / 延迟发车，等待期间货损风险已标注",
    },
  };
  return map[d] || null;
});

function selectPlan(id) {
  // 被进度闸门禁用的方案（如车已过港口时的陆水联运）不可点选下发
  const p = (result.value?.plans || []).find((x) => x.id === id);
  if (!p || p.notApplicable) return;
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

async function runAnalysis(useCache, triggerSource) {
  running.value = true;
  trigger.value = triggerSource || "manual";
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
        // 逐智能体通道回填：汇总标签可能把三个不同通道“就重不就轻”地合并成一个，
        // 但评委要查的是“这一段具体是谁写的”，所以每个节点各自存一份
        node.aiChannel = a.aiChannel || "";
        node.aiChannelLabel = a.aiChannelLabel || "";
      }
    });
    (data.agentStatus || []).forEach((row, i) => {
      if (agentFlow.value[i]) agentFlow.value[i].output = row.output;
    });
    // 默认选中推荐方案
    selectedPlan.value = recommendedId.value;
    // 常态（开始导航）模板：同步拉多路线候选供比选派单；改路线模板不需要
    if (data.analysisMode === "reroute") {
      dispatchCandidates.value = [];
    } else {
      loadDispatchCandidates();
    }
  } catch (e) {
    console.error("agent analysis failed", e);
    agentFlow.value.forEach((n) => {
      if (n.status === "RUNNING") n.status = "IDLE";
    });
  } finally {
    running.value = false;
    // 推理期间又有新的风险注入 → 结果一出就立即再算一轮
    if (rerunPending.value) {
      const t = rerunTrigger.value;
      rerunPending.value = false;
      rerunTrigger.value = "manual";
      runAnalysis(false, t);
    }
  }
}

function resetAgentFlow() {
  agentFlow.value.forEach((n) => {
    n.status = "IDLE";
    n.elapsedMs = 0;
    n.output = "";
    n.aiChannel = "";
    n.aiChannelLabel = "";
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

/** 开始导航模板：拉多路线候选（逐条结合实时气象的灾害概率），供调度员比选后派单 */
async function loadDispatchCandidates() {
  dispatchCandidates.value = [];
  dispatchSelected.value = "";
  dispatchCanal.value = null;
  try {
    const { data } = await axios.get("/api/route/candidates", {
      params: { originId: props.originId, destinationId: props.destinationId, cargoType: "cold" },
      timeout: 30000,
    });
    const list = (data && data.candidates) || [];
    dispatchCandidates.value = list;
    // 与公路并列的「坐船出境」推荐位（后端 serves 命中时才有）
    dispatchCanal.value = (data && data.canalOption) || null;
    // 默认选中推荐路线（无则选灾害概率最低的一条）
    const withProb = list.filter((c) => c.hazardProbability >= 0);
    const def = list.find((c) => c.key === "recommended")
      || (withProb.length ? withProb.reduce((a, b) => (a.hazardProbability <= b.hazardProbability ? a : b)) : list[0]);
    dispatchSelected.value = def ? def.key : "";
  } catch (e) {
    console.error("dispatch candidates failed", e);
  }
}

function viaText(c) {
  return Array.isArray(c.via) ? c.via.join(" → ") : (c.via || "");
}
function probKey(p) {
  if (p >= 60) return "high";
  if (p >= 30) return "mid";
  return "low";
}
/** 低阈值不显示百分比：平静天气下各候选都堆在同一个地板值（如 9%），
 * 直接标「通畅」更诚实；已发生灾害或概率达阈值才显示具体数字 */
function probText(c) {
  const p = c.hazardProbability;
  if (p < 0) return "暂无";
  if (!c.hazardOccurred && p < 15) return "通畅";
  return p + "%";
}

/** 开始导航派单：把选定路线快照 + 本次 AI 分析递上去，由父组件合并订单信息后调 /api/task/dispatch */
function dispatchToDriver() {
  // 请求在飞时禁止重复点击（否则会向同一司机叠发多条派单指令）
  if (dispatching.value) return;
  dispatchFeedback.value = "";
  // 调度员选定「坐船出境」：派 routeChoice='canal'，司机端接单后走陆水联运导航
  if (dispatchSelected.value === "canal" && dispatchCanal.value) {
    // 防御：选上之后运河才禁航（SSE 翻转）时不允许把注定走不了的方案派下去
    if (dispatchCanal.value.canalBlocked) {
      window.alert(`平陆运河禁航：${dispatchCanal.value.canalBlockedReason || '触发通航安全红线'}，请改选公路路线`);
      return;
    }
    const cn = dispatchCanal.value;
    const aiText = (result.value?.recommendation || "") + " 本线为陆水联运（平陆运河坐船出境）。";
    dispatching.value = true;
    emit("dispatch-task", {
      route: {
        routeChoice: "canal",
        routeLabel: cn.label,
        routeSummary: `${cn.via} · ${cn.hours}h · ${cn.distanceKm}km`,
        hazardProbability: -1,
        aiAnalysis: aiText,
      },
      done: finishDispatch,
    });
    return;
  }
  const c = dispatchCandidates.value.find((x) => x.key === dispatchSelected.value);
  if (!c) {
    // 旧版这里静默 return：点了按钮像没反应一样，补上提示
    window.alert("未选中任何路线，请先在上方候选里点选一条");
    return;
  }
  const aiText = (result.value?.recommendation || "")
    + (c.hazardReason ? ` 本线风险：${c.hazardReason}` : "");
  dispatching.value = true;
  emit("dispatch-task", {
    route: {
      routeChoice: c.key,
      routeLabel: c.label,
      routeSummary: `${viaText(c)} · ${c.hours}h · ${c.distanceKm}km`,
      hazardProbability: c.hazardProbability >= 0 ? c.hazardProbability : -1,
      aiAnalysis: aiText,
    },
    done: finishDispatch,
  });
}

/** 父组件派单完成后回调：ok=true 就地亮「已派单」，失败只恢复按钮（父组件已 alert） */
function finishDispatch(ok) {
  dispatching.value = false;
  if (!ok) return;
  dispatchFeedback.value = "✓ 已派单，等待司机接单";
  if (dispatchFbTimer) clearTimeout(dispatchFbTimer);
  dispatchFbTimer = setTimeout(() => { dispatchFeedback.value = ""; }, 4000);
}

/**
 * 风险状态变化（场景注入/清除、沙盘雨量调整、水运禁航、灾害推送等）时由父组件调用：
 * 已有结果立即标记过期（界面上不再信任旧推荐），并马上重新计算新方案（防抖 0.3s）。
 * 即使还没分析过也会触发（灾害一来就出熔断重算版），正在推理则只排队，本轮结束后自动再算一轮。
 */
let staleTimer = null;
const rerunTrigger = ref("manual");
function notifyRiskChanged() {
  stale.value = true;
  if (staleTimer) clearTimeout(staleTimer);
  staleTimer = setTimeout(() => {
    if (running.value) {
      rerunPending.value = true;
      rerunTrigger.value = "hazard";
      return;
    }
    runAnalysis(false, "hazard");
  }, 300);
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
.trigger-tag {
  margin-right: 4px;
}
.trigger-tag.warn {
  background: rgba(217, 119, 6, 0.16);
  color: #d97706;
}
.dispatch-ok {
  font-size: 12px;
  font-weight: 700;
  color: #16a34a;
  white-space: nowrap;
  animation: dispatch-fade-in 0.2s ease;
}
@keyframes dispatch-fade-in {
  from { opacity: 0; transform: translateY(-3px); }
  to { opacity: 1; transform: translateY(0); }
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
/* 每个智能体自己的生成通道（红=出境、绿=本机）， hover 看完整说明 */
.agent-chan {
  display: inline-block;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 7px;
  font-size: 9px;
  cursor: help;
}
.agent-chan.chip-local { background: rgba(22, 163, 74, 0.16); color: #16a34a; }
.agent-chan.chip-remote { background: rgba(225, 29, 72, 0.16); color: #e11d48; }
.agent-chan.chip-template { background: rgba(139, 148, 158, 0.2); color: #64748f; }
.agent-chan.chip-unknown { background: rgba(139, 148, 158, 0.2); color: #64748f; }
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
/* AI 溯源 chip：颜色按“数据离本机多远”递进，绿不出门 / 红出公网 */
.ai-chip { cursor: help; }
.ai-chip.chip-local { background: rgba(22, 163, 74, 0.14); color: #16a34a; }
.ai-chip.chip-remote { background: rgba(225, 29, 72, 0.14); color: #e11d48; }
.ai-chip.chip-template { background: rgba(139, 148, 158, 0.18); color: #64748f; }
.ai-chip.chip-unknown { background: rgba(139, 148, 158, 0.18); color: #64748f; }
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
/* 进度闸门禁用的方案（如车已过港口时的陆水联运）：置灰、不可点选下发 */
.plan-item.na {
  opacity: 0.55;
  cursor: not-allowed;
  filter: grayscale(0.5);
}
.plan-item.na:hover {
  border-color: rgba(30, 50, 90, 0.12);
}
.na-tag {
  font-size: 10px;
  background: rgba(139, 148, 158, 0.22);
  color: #64748f;
  padding: 1px 6px;
  border-radius: 8px;
}
.progress-tag {
  margin-left: 8px;
  font-size: 11px;
  font-weight: 400;
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
.dcand-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dcand-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid rgba(30, 50, 90, 0.16);
  border-radius: 9px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.6);
  transition: border-color 0.15s, background 0.15s;
}
.dcand-row.sel {
  border-color: rgba(79, 109, 245, 0.6);
  background: rgba(79, 109, 245, 0.08);
}
.dcand-row input {
  display: none;
}
.dcand-radio {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid rgba(30, 50, 90, 0.3);
  flex: 0 0 auto;
}
.dcand-radio.on {
  border-color: #4f6df5;
  background: radial-gradient(circle, #4f6df5 40%, transparent 45%);
}
.dcand-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
  min-width: 0;
}
.dcand-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #1c2a44;
}
.dcand-via {
  font-size: 10px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dcand-meta {
  font-size: 10px;
  color: #5b6b85;
}
/* 坐船（陆水联运）行：青蓝底色与公路候选区分；途经较长允许换行 */
.dcand-row.canal-row {
  border-color: rgba(14, 165, 233, 0.4);
  background: rgba(14, 165, 233, 0.06);
}
.dcand-row.canal-row.sel {
  border-color: rgba(14, 165, 233, 0.8);
  background: rgba(14, 165, 233, 0.12);
}
.canal-row .dcand-via {
  white-space: normal;
  line-height: 1.4;
}
/* 禁航态：整行标红不可选，不能一边禁航一边推荐坐船 */
.dcand-row.canal-row.blocked,
.dcand-row.canal-row.blocked.sel {
  border-color: rgba(225, 29, 72, 0.55);
  background: rgba(225, 29, 72, 0.07);
}
.rec-tag.blocked {
  background: #e11d48;
  color: #fff;
}
.canal-blocked-note {
  color: #e11d48;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.5;
  white-space: normal;
}
</style>
