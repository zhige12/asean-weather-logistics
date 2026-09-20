<template>
  <div class="app-root" :class="{ 'panel-dragging': panelDragging }">
    <header class="app-header">
      <h1>东盟跨境物流气象导航平台 · 调度大屏</h1>
      <div class="header-right">
        <button class="btn small outline-light" @click="openDriver">司机端 ↗</button>
        <button class="btn small" @click="getStatus">OSM 状态</button>
        <span class="status-badge" :class="{ready: status && status.loaded}">{{ status && status.loaded ? 'OSM 已加载' : 'OSM 未加载' }}</span>
      </div>
    </header>

    <div v-if="agentNotice" class="agent-banner" :class="agentNotice.level || 'info'">
      <span class="agent-banner-title">🤖 Agent 实时守护</span>
      <span class="agent-banner-text">{{ agentNotice.reason }} {{ agentNotice.advice }}</span>
      <span class="agent-banner-time">{{ agentNotice.time }}</span>
    </div>

    <div class="main">
      <aside class="panel" :style="panelStyle">
        <section class="card">
          <h3>跨境路径规划</h3>
          <div class="coords">
            <label>起点
              <select v-model="draftOriginId">
                <option v-for="n in nodeOptions" :key="n.id" :value="n.id">{{ n.name }}</option>
              </select>
            </label>
            <label>终点
              <select v-model="draftDestinationId">
                <option v-for="n in nodeOptions" :key="n.id" :value="n.id">{{ n.name }}</option>
              </select>
            </label>
            <div class="row">
              <button class="btn primary" @click="confirmSelection">确认选择并规划</button>
              <button class="btn outline" @click="clearRoute">清空</button>
            </div>
            <div class="row">
              <button class="btn" :class="pickMode ? 'pick-on' : 'outline'" @click="togglePickMode">
                {{ pickMode ? '取消地图选点' : '地图选点 ⚡' }}
              </button>
              <span class="muted" style="align-self:center">{{ pickMode ? (pickOriginNode ? '已选起点，点地图选终点' : '点击地图任意位置选起点') : '选择起终点后点击「确认选择并规划」' }}</span>
            </div>
          </div>
        </section>

        <!-- 第四幕：公司派单（司机端收到后自动切物流任务模式） -->
        <TaskDispatchPanel ref="taskPanelRef" :task-status="taskStatus" @refresh="refreshTask" />

        <!-- 第一/二幕：决策沙盘（官方推送熔断 + 滑块观察决策边界） -->
        <DecisionSandbox ref="sandboxRef" @sandbox-change="onSandboxChange" />

        <!-- 第三幕：多智能体协同决策分析（含第四幕确认下发入口） -->
        <AgentPanel
          ref="agentPanelRef"
          :originId="originId"
          :destinationId="destinationId"
          @dispatched="onDispatched"
        />

        <!-- 第五/六幕：多角色触达 + 分级叫应追踪 -->
        <OutreachPanel :status="outreachStatus" @refresh="refreshOutreach" />

        <section class="card">
          <h3>模拟灾害注入（熔断演示）</h3>
          <div v-if="scenarios.length" class="scenario-list">
            <div v-for="s in scenarios" :key="s.id" class="scenario-item">
              <button class="btn scenario-btn" :class="{active: activeScenarios.includes(s.id)}"
                      @click="toggleScenario(s.id)">
                <span class="scenario-name">{{ s.name }}</span>
                <span class="scenario-desc">{{ s.description }}</span>
              </button>
            </div>
          </div>
          <div v-else class="muted">加载中…</div>
          <div class="row" style="margin-top:8px">
            <button class="btn muted" @click="clearAllScenarios">清除全部风险</button>
          </div>
        </section>

        <section class="card">
          <h3>灾害时间线（实时）</h3>
          <div v-if="hazardTimeline.length" class="timeline-list">
            <div v-for="(item, i) in hazardTimeline" :key="i" class="timeline-item">
              <span class="timeline-time">{{ item.time }}</span>
              <span class="timeline-dot" :class="item.level"></span>
              <div class="timeline-body">
                <div class="timeline-title">{{ item.title }}</div>
                <div class="timeline-desc">{{ item.desc }}</div>
              </div>
            </div>
          </div>
          <div v-else class="muted">暂无灾害事件</div>
        </section>

        <section class="card">
          <h3>实时气象（{{ weatherSourceLabel }}）
            <span class="route-state" :class="weatherSourceClass">{{ weatherSourceText }}</span>
          </h3>
          <div class="row source-switch-row">
            <select
              class="source-select"
              :value="weatherSourceId"
              :disabled="sourceSwitching"
              @change="switchWeatherSource($event.target.value)"
            >
              <option
                v-for="opt in weatherSourceOptions"
                :key="opt.id"
                :value="opt.id"
                :disabled="!opt.configured"
              >
                {{ opt.label }}{{ opt.configured ? '' : '（未配置）' }}
              </option>
            </select>
          </div>
          <div class="row">
            <button class="btn" :disabled="weatherLoading" @click="refreshRealWeather">
              {{ weatherLoading ? '拉取中…' : '拉取实时气象' }}
            </button>
            <span class="muted" style="align-self:center">网络失败自动降级模拟</span>
          </div>
          <div v-if="visibleWeather.length" class="weather-grid">
            <div v-for="w in visibleWeather" :key="w.nodeId" class="weather-cell">
              <div class="w-name">{{ w.nodeName }} <span class="w-id">{{ w.nodeId }}</span></div>
              <div class="w-temp">{{ Math.round(w.temperatureC) }}°C</div>
              <div class="w-sub">
                <span v-if="w.precipitationMm > 0">降水 {{ w.precipitationMm }}mm</span>
                <span v-if="w.windKph > 5">风 {{ Math.round(w.windKph) }}km/h</span>
                <span v-if="w.visibilityM < 5000">能见度 {{ w.visibilityM }}m</span>
              </div>
            </div>
          </div>
          <div v-else class="muted">暂无实时数据，点击拉取</div>
        </section>

        <section class="card">
          <h3>通关时效（customs_efficiency）</h3>
          <div class="row">
            <label class="inline-label">时段
              <select v-model="period" @change="replanIfNeeded">
                <option value="normal">普通</option>
                <option value="peak">高峰 ×1.3~1.4</option>
                <option value="holiday">节假日 ×1.7~1.8</option>
              </select>
            </label>
            <label class="inline-label">货类
              <select v-model="cargoType" @change="replanIfNeeded">
                <option value="general">普货</option>
                <option value="cold">冷链</option>
                <option value="dangerous">危化</option>
                <option value="oversized">大件</option>
              </select>
            </label>
          </div>
          <div v-for="p in customsList" :key="p.portId" class="customs-item" :class="{adjusted: p.adjusted}">
            <div class="customs-head">
              <span class="customs-name">{{ p.portName }}</span>
              <span class="customs-hours">{{ p.currentHours }}h</span>
              <span v-if="p.adjusted" class="customs-note" :title="p.note">(调整)</span>
            </div>
            <div class="row">
              <button class="btn tiny" @click="adjustCustoms(p.portId, -0.5)">-0.5h</button>
              <button class="btn tiny" @click="adjustCustoms(p.portId, 0.5)">+0.5h</button>
              <button class="btn tiny outline" @click="resetCustoms(p.portId)">重置</button>
            </div>
          </div>
          <div class="muted" style="margin-top:6px">基准：友谊关 {{ baseHours('YGG') }}h / 芒街 {{ baseHours('MC') }}h</div>
        </section>

        <section v-if="routeResult && routeResult.route" class="card">
          <h3>路线状态
            <span class="route-state" :class="routeStateClass">{{ routeStateText }}</span>
          </h3>
          <ETAIndicator :routeData="routeResult.route" />
          <div class="compare-row">
            <span class="compare-label">基准路线</span>
            <span>{{ routeResult.route.baselineHours }}h / {{ fmtKm(routeResult.route.baselinePathNodeIds) }}节点</span>
          </div>
          <div class="compare-row">
            <span class="compare-label">当前路线</span>
            <span>{{ routeResult.route.currentHours }}h</span>
          </div>
          <div class="compare-row" :class="{warn: routeResult.route.extraHours > 0}">
            <span class="compare-label">额外延误</span>
            <span>{{ routeResult.route.extraHours }} 小时</span>
          </div>
          <div class="compare-row" v-if="routeResult.route.cargoLossYuan > 0" :class="{warn: true}">
            <span class="compare-label">冷链货损估算</span>
            <span>约 ¥{{ fmtYuan(routeResult.route.cargoLossYuan) }}
              <span class="muted" style="font-size:11px">(2% 基础 + 温湿度加成 × 延误/24)</span>
            </span>
          </div>
          <div class="compare-row">
            <span class="compare-label">是否绕行</span>
            <span>{{ routeResult.route.rerouted ? '是（自动切换口岸）' : '否' }}</span>
          </div>
        </section>

        <section v-if="candidates.length" class="card">
          <h3>候选路线
            <span class="route-state">{{ candidates.length }} 条可选</span>
          </h3>
          <div class="candidate-list">
            <div v-for="c in candidates" :key="c.key"
                 class="candidate-item"
                 :class="{active: c.key === selectedCandidateKey, recommended: c.key === 'recommended', softened: c.softened}"
                 @click="selectCandidate(c.key)">
              <div class="candidate-head">
                <span class="candidate-label">{{ c.label || c.key }}</span>
                <span v-if="c.key === 'recommended'" class="candidate-badge rec">推荐</span>
                <span v-if="c.softened" class="candidate-badge soften" title="硬熔断降级为软惩罚，兜底路径">降级</span>
              </div>
              <div class="candidate-meta">
                <span>⏱ {{ c.hours != null ? c.hours + 'h' : '-' }}</span>
                <span>📏 {{ c.distanceKm != null ? c.distanceKm + 'km' : '-' }}</span>
                <span :class="{warn: c.riskCount > 0}">⚠ {{ c.riskCount || 0 }} 风险</span>
              </div>
              <div v-if="c.note" class="candidate-note">{{ c.note }}</div>
              <div v-if="c.via && c.via.length" class="candidate-via">途经：{{ Array.isArray(c.via) ? c.via.join(' → ') : c.via }}</div>
            </div>
          </div>
          <div class="muted" style="margin-top:6px;font-size:11px">点击切换主线，备选线在地图上以浅蓝虚线展示。</div>
        </section>

        <section class="card">
          <h3>任务简报</h3>
          <BriefingPanel
            :originId="originId"
            :destinationId="destinationId"
            :period="period"
            :cargoType="cargoType"
          />
        </section>

        <section class="card">
          <h3>碳排放计算</h3>
          <CarbonCalc
            :routeResult="routeResult"
            :customsList="customsList"
            :cargoType="cargoType"
          />
        </section>

        <section class="card">
          <h3>AI 预警（DeepSeek 双语 + RAG）</h3>
          <div class="row">
            <button class="btn" @click="buildBilingual" :disabled="bilingualLoading">{{ bilingualLoading ? 'AI 生成中，约 10 秒…' : '生成中越双语预警' }}</button>
          </div>
          <div v-if="bilingualWarning" class="warning-box">{{ bilingualWarning }}</div>
        </section>

        <section class="card">
          <h3>AI 灾害预测（实时气象 + DeepSeek）</h3>
          <div class="row">
            <button class="btn" :disabled="hazardLoading" @click="predictHazards">
              {{ hazardLoading ? 'AI 预测中，约 10 秒…' : 'AI 灾害预测并注入风险' }}
            </button>
            <button class="btn muted" :disabled="!hazardInjected" @click="clearPredictHazards">清除预测风险</button>
          </div>
          <div v-if="hazardSummary" class="warning-box">{{ hazardSummary }}</div>
          <div v-if="hazardItems.length" class="hazard-list">
            <div v-for="(h, i) in hazardItems" :key="i"
                 class="hazard-item" :class="'sev-' + (h.severity || 'MEDIUM').toLowerCase()">
              <span class="hazard-type">{{ h.hazardType }}</span>
              <span class="hazard-city">{{ h.nodeName }} <small>{{ h.nodeId }}</small></span>
              <span class="hazard-sev">{{ h.severity }} · {{ Math.round((h.probability || 0) * 100) }}%</span>
              <div class="hazard-reason">{{ h.reason }}</div>
              <div class="hazard-advice">建议：{{ h.advice }}</div>
            </div>
          </div>
        </section>

        <section class="card">
          <h3>行业知识库（RAG 检索）</h3>
          <div class="row">
            <input class="kb-input" v-model="knowledgeQuery" placeholder="如：泥石流 / 冷链 / 台风…"
                   @keyup.enter="searchKnowledge" />
            <button class="btn" @click="searchKnowledge">检索</button>
          </div>
          <div v-if="knowledgeResults.length" class="kb-list">
            <div v-for="k in knowledgeResults" :key="k.id" class="kb-item">
              <div class="kb-title">{{ k.id }} {{ k.title }}
                <span class="kb-cat">{{ k.category }}</span>
              </div>
              <div class="kb-content">{{ k.content }}</div>
            </div>
          </div>
          <div v-else-if="knowledgeLoaded" class="muted">输入关键词检索行业知识，或查看全部条目</div>
        </section>

        <!-- 第九幕：平陆运河支流风险差异化预测 -->
        <TributaryPanel />

        <!-- 第十幕：开放平台智能体配置器 -->
        <PlatformConfigurator @config-changed="onConfigChanged" />

        <!-- 第十一幕：成长路径三步走 -->
        <GrowthPath />

        <!-- 全程：决策日志留痕 -->
        <DecisionLogTimeline ref="logRef" />

        <section class="card">
          <h3>当前风险</h3>
          <RiskStrip :riskLevel="overallRiskLevel" />
          <div v-if="risks.length">
            <div v-for="r in risks" :key="r.edgeId" class="risk-item">
              <span class="risk-edge">{{ r.edgeId }}</span>
              <span class="risk-sev" :class="sevClass(r.severity)">{{ r.severity }}</span>
              <span class="risk-reason">{{ r.reason }}</span>
            </div>
          </div>
          <div v-else class="muted">无风险注入</div>
        </section>

        <section class="card small">
          <h3 class="raw-header" @click="showRawResult = !showRawResult" title="点击展开/收起">
            原始返回
            <span class="raw-toggle">{{ showRawResult ? '收起 ▲' : '展开 ▼' }}</span>
          </h3>
          <div v-if="showRawResult" class="raw-meta">
            {{ routeResult && routeResult.route ? routeResult.route.pathNodeIds.length + ' 节点 · ' + routeResult.route.totalDistanceKm + 'km · ' + (routeResult.route.rerouted ? '已绕行' : '未绕行') : '暂无数据' }}
          </div>
          <pre v-if="showRawResult" class="status-pre">{{ routeResult }}</pre>
        </section>
      </aside>

      <!-- 面板宽度拖拽手柄：整体等比缩放（宽度与字号同步变大），双击复位 -->
      <div class="panel-resizer" :class="{ dragging: panelDragging }"
           title="拖动调整面板宽度（字号随之放大）· 双击复位"
           @pointerdown="startPanelDrag"
           @dblclick="resetPanelZoom">
        <span class="resizer-grip"></span>
      </div>

      <main class="map-area">
        <MapView
          :network="network"
          :risks="risks"
          :route="routeCoords"
          :route-edge-ids="routeEdgeIds"
          :route-edge-spans="routeEdgeSpans"
          :baseline="baselineCoords"
          :baseline-edge-ids="baselineEdgeIds"
          :baseline-edge-spans="baselineEdgeSpans"
          :hazard-points="hazardPoints"
          :rerouted="routeResult && routeResult.route ? routeResult.route.rerouted : false"
          :selected-edge="selectedEdgeId"
          :candidates="candidates"
          :selected-candidate-key="selectedCandidateKey"
          @candidate-pick="selectCandidate"
          @edge-click="onEdgeClick"
          @map-click="onMapClick"
        />
      </main>
    </div>
  </div>
</template>

<script>
import axios from 'axios';
import MapView from './components/MapView.vue';
import RiskStrip from './components/RiskStrip.vue';
import ETAIndicator from './components/ETAIndicator.vue';
import BriefingPanel from './components/BriefingPanel.vue';
import CarbonCalc from './components/CarbonCalc.vue';
import DecisionSandbox from './components/DecisionSandbox.vue';
import TaskDispatchPanel from './components/TaskDispatchPanel.vue';
import AgentPanel from './components/AgentPanel.vue';
import OutreachPanel from './components/OutreachPanel.vue';
import TributaryPanel from './components/TributaryPanel.vue';
import PlatformConfigurator from './components/PlatformConfigurator.vue';
import GrowthPath from './components/GrowthPath.vue';
import DecisionLogTimeline from './components/DecisionLogTimeline.vue';

// 左侧面板拖拽缩放边界：1=默认 360px；上限约 2.2 倍（≈790px，投影仪/后排也能看清）
const PANEL_ZOOM_MIN = 0.85;
const PANEL_ZOOM_MAX = 2.2;
/** 面板基准宽度（CSS 中的 .panel width），拖拽换算用 */
const PANEL_BASE_WIDTH = 360;
/**
 * 浏览器是否支持 zoom。Chromium 与 Firefox 126+ 均支持，且 zoom 参与布局尺寸计算，
 * 因此面板宽度与内部字号能一起等比放大（子组件字号是写死的 px，靠父级 zoom 才会放大）。
 * 不支持时退化为"只加宽"——字不会变大，但拖动始终有可见反馈。
 */
const ZOOM_SUPPORTED = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
  && CSS.supports('zoom', '1.5');

export default {
  components: {
    MapView, RiskStrip, ETAIndicator, BriefingPanel, CarbonCalc,
    DecisionSandbox, TaskDispatchPanel, AgentPanel, OutreachPanel, TributaryPanel,
    PlatformConfigurator, GrowthPath, DecisionLogTimeline
  },
  data() {
    return {
      status: null,
      routeResult: null,
      showRawResult: false,
      routeCoords: [],
      routeEdgeIds: [],
      routeEdgeSpans: [],
      baselineCoords: [],
      baselineEdgeIds: [],
      baselineEdgeSpans: [],
      network: null,
      risks: [],
      scenarios: [],
      activeScenarios: [],
      originId: 'NN',
      destinationId: 'HN',
      draftOriginId: 'NN',
      draftDestinationId: 'HN',
      selectedEdgeId: null,
      bilingualWarning: '',
      bilingualLoading: false,
      hazardLoading: false,
      hazardSummary: '',
      hazardItems: [],
      hazardInjected: false,
      customsList: [],
      period: 'normal',
      cargoType: 'general',
      knowledgeQuery: '',
      knowledgeResults: [],
      knowledgeLoaded: false,
      weatherPoints: [],
      weatherSource: 'unknown',
      weatherSourceId: 'contest-observation',
      weatherSourceOptions: [],
      sourceSwitching: false,
      weatherLoading: false,
      pickMode: false,
      pickOriginNode: null,
      pickDestinationNode: null,
      agentNotice: null,
      hazardTimeline: [],
      // 候选路线：每次规划后由 /api/routes/candidates 拉回，最多 10 条
      candidates: [],
      // 当前主线对应的候选 key（默认 = 后端返回的 "recommended"，选中后变为对应 key）
      // 预先写 'recommended' 是为了让候选到达后 drawAlternates 能直接跳过它，避免与主线红绿分层重叠
      selectedCandidateKey: 'recommended',
      // === 演示场景（11幕）===
      outreachStatus: { targets: [], total: 0, confirmed: 0, pending: 0 },
      // 运输任务（公司派单）：司机端据此切物流任务模式，大屏派单面板显示接单状态
      taskStatus: { task: null, status: 'NONE' },
      // 左侧面板缩放系数（1=默认360px）：拖动右边缘手柄整体等比放大，字号随之变大
      panelZoom: 1,
      panelDragging: false
    };
  },
  computed: {
    // 面板用 zoom 整体等比放大：宽度与字号一同变化。
    // 只改 width 的话卡片内字号仍写死 px，会变成"更宽但字没大"的空旷版式
    panelStyle() {
      if (ZOOM_SUPPORTED) {
        return { zoom: this.panelZoom };
      }
      return { width: Math.round(PANEL_BASE_WIDTH * this.panelZoom) + 'px', flex: '0 0 auto' };
    },
    nodeOptions() {
      if (!this.network || !this.network.nodes) return [];
      // 真实路网含大量无名的 OSM 中间节点，下拉只展示有名字的城市/口岸节点
      const named = this.network.nodes.filter(n => n.name && n.name.trim());
      return named.length ? named : this.network.nodes;
    },
    // 天气面板只展示有名字的节点，避免几千个格子卡死
    visibleWeather() {
      const pts = this.weatherPoints || [];
      const named = pts.filter(w => w.nodeName && w.nodeName.trim());
      return (named.length ? named : pts).slice(0, 60);
    },
    routeStateText() {
      const r = this.routeResult && this.routeResult.route;
      if (!r) return '未规划';
      if (r.rerouted) return '已熔断·绕行';
      if (r.riskSegments && r.riskSegments.length) return '风险预警';
      return '通畅';
    },
    routeStateClass() {
      const r = this.routeResult && this.routeResult.route;
      if (!r) return '';
      if (r.rerouted) return 'blocked';
      if (r.riskSegments && r.riskSegments.length) return 'warning';
      return 'clear';
    },
    weatherSourceLabel() {
      const m = {
        'contest-observation': '比赛接口 CRA40',
        'open-meteo': '真实气象 Open-Meteo',
        simulated: '离线模拟',
        none: '待拉取'
      };
      return m[this.weatherSourceId] || '多数据源';
    },
    weatherSourceText() {
      const m = {
        live: '实时', 'live-alt': '实时(备用源)',
        fallback: '模拟降级', offline: '离线', unknown: '未拉取'
      };
      return m[this.weatherSource] || '未拉取';
    },
    weatherSourceClass() {
      const m = {
        live: 'ok', 'live-alt': 'ok',
        fallback: 'warn', offline: 'off', unknown: 'pending'
      };
      return m[this.weatherSource] || 'pending';
    },
    hazardPoints() {
      const route = this.routeResult && this.routeResult.route;
      if (!route || !route.pathCoords || !route.pathCoords.length) return [];
      const edgeIds = route.pathEdgeIds || [];
      const points = [];
      (route.riskSegments || []).forEach((seg) => {
        const i = edgeIds.indexOf(seg.edgeId);
        if (i >= 0 && route.pathCoords[i]) {
          points.push({
            lat: route.pathCoords[i][0],
            lon: route.pathCoords[i][1],
            reason: seg.reason || ''
          });
        }
      });
      return points;
    },
    overallRiskLevel() {
      if (!this.risks || this.risks.length === 0) return 0;
      const severityMap = {
        CRITICAL: 100,
        HIGH: 66,
        MEDIUM: 33
      };
      return this.risks.reduce((max, risk) => {
        return Math.max(max, severityMap[risk.severity] || 0);
      }, 0);
    }
  },
  methods: {
    fmtKm(nodeIds) {
      return (nodeIds || []).length;
    },
    fmtYuan(v) {
      return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 });
    },
    sevClass(sev) {
      return { CRITICAL: 'sev-critical', HIGH: 'sev-high', MEDIUM: 'sev-medium' }[sev] || '';
    },
    inferPortFromNodeIds(nodeIds) {
      const ids = nodeIds || [];
      if (ids.includes('MC') || ids.includes('E9')) return '芒街口岸';
      if (ids.includes('YGG') || ids.includes('E4')) return '友谊关口岸';
      if (ids.includes('HK') || ids.includes('E36')) return '河口口岸';
      return '当前口岸';
    },
    pushTimeline(title, desc, level = 'info') {
      this.hazardTimeline.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        title,
        desc,
        level
      });
      if (this.hazardTimeline.length > 30) this.hazardTimeline.pop();
    },
    async getStatus() {
      try {
        const r = await axios.get('/api/route/status');
        this.status = r.data;
      } catch (e) {
        this.status = String(e);
      }
    },
    async loadNetwork() {
      try {
        const r = await axios.get('/api/route/network');
        this.network = r.data;
      } catch (e) {
        // 离线兜底：从 APK 内置精简路网加载（仅用于可视化，不做算路）
        try {
          const r = await axios.get('/data/network-lite.json');
          const raw = r.data;
          // 精简格式字段名映射回完整格式
          this.network = {
            nodes: (raw.nodes || []).map(n => ({
              id: n.id, name: n.n || n.name || '', latitude: n.lat || n.latitude, longitude: n.lon || n.longitude
            })),
            edges: (raw.edges || []).map(e => ({
              id: e.id, fromNodeId: e.f || e.from, toNodeId: e.t || e.to,
              c: e.c || null, customs: e.cs || e.customs || false, name: e.n || e.name || ''
            }))
          };
        } catch (_) {
          console.error('离线路网加载失败');
        }
      }
    },
    async refreshRisks() {
      try {
        const r = await axios.get('/api/weather/risk');
        this.risks = r.data || [];
      } catch (e) {
        console.error(e);
      }
    },
    async loadScenarios() {
      try {
        const r = await axios.get('/api/weather/scenarios');
        this.scenarios = r.data || [];
      } catch (e) {
        // 离线模拟场景
        this.scenarios = [
          { id: 's1', name: '暴雨·友谊关', description: '北部山区暴雨，友谊关口岸通行缓慢' },
          { id: 's2', name: '大雾·芒街', description: '沿海大雾，芒街口岸通行受阻' },
          { id: 's3', name: '泥石流·越北', description: '越北山区泥石流，部分路段封闭' },
          { id: 's4', name: '高温·冷链', description: '高温预警，冷链货车需加速通关' },
          { id: 's5', name: '台风·广宁', description: '台风逼近广宁省，注意路段安全' }
        ];
      }
    },
    async loadCustoms() {
      try {
        const r = await axios.get('/api/customs/efficiency');
        this.customsList = r.data || [];
      } catch (e) {
        // 离线模拟通关数据
        this.customsList = [
          { portId: 'YGG', portName: '友谊关口岸', baseHours: 3.5, currentHours: 4.0, adjusted: true, note: '高峰延时' },
          { portId: 'MC', portName: '芒街口岸', baseHours: 2.0, currentHours: 2.0, adjusted: false },
          { portId: 'HK', portName: '河口口岸', baseHours: 2.5, currentHours: 2.5, adjusted: false }
        ];
      }
    },
    baseHours(portId) {
      const p = this.customsList.find(x => x.portId === portId);
      return p ? p.baseHours : '-';
    },
    async adjustCustoms(portId, delta) {
      try {
        const p = this.customsList.find(x => x.portId === portId);
        if (!p) return;
        const next = Math.max(1, Math.round((p.currentHours + delta) * 10) / 10);
        const r = await axios.post(`/api/customs/efficiency/${portId}`,
          { hours: next, note: delta > 0 ? '口岸拥堵·人工调整' : '口岸疏解·人工调整' });
        this.customsList = r.data || [];
        await this.replanIfNeeded();
      } catch (e) {
        console.error(e);
        alert('通关时效调整失败');
      }
    },
    async resetCustoms(portId) {
      try {
        const r = await axios.delete(`/api/customs/efficiency/${portId}`);
        this.customsList = r.data || [];
        await this.replanIfNeeded();
      } catch (e) {
        console.error(e);
      }
    },
    async searchKnowledge() {
      try {
        const r = await axios.get('/api/knowledge/search', {
          params: { q: this.knowledgeQuery || '', limit: 5 }
        });
        this.knowledgeResults = r.data || [];
        this.knowledgeLoaded = true;
      } catch (e) {
        console.error(e);
        alert('知识库检索失败');
      }
    },
    async replanIfNeeded() {
      if (this.routeResult && this.routeResult.route) {
        await this.planCrossBorder();
      }
    },
    openDriver() {
      window.open('/driver.html', '_blank');
    },
    async refreshRealWeather() {
      this.weatherLoading = true;
      this.weatherSource = 'unknown';
      try {
        const r = await axios.post('/api/weather/real/refresh', null, {
          params: { originId: this.originId, destinationId: this.destinationId },
          timeout: 45000
        });
        const pts = r.data.points || [];
        this.weatherPoints = pts;
        // 后端返回实际生效数据源：contest-observation / open-meteo / simulated / none
        const src = r.data.source;
        this.weatherSourceId = src || 'contest-observation';
        this.weatherSource = isLiveSource(src) ? 'live'
          : pts.length ? 'fallback' : 'offline';
      } catch (e) {
        console.error(e);
        this.weatherSource = 'offline';
      } finally {
        this.weatherLoading = false;
      }
    },
    /** 加载可选气象数据源清单（比赛官方 / 真实气象 / 离线模拟） */
    async loadWeatherSource() {
      try {
        const r = await axios.get('/api/weather/source', { timeout: 8000 });
        this.weatherSourceOptions = r.data.options || [];
        if (r.data.current) this.weatherSourceId = r.data.current;
      } catch (e) {
        console.error('load weather source failed', e);
      }
    },
    /** 切换气象数据源：切完立即按新源重新拉取 */
    async switchWeatherSource(sourceId) {
      if (!sourceId || this.sourceSwitching) return;
      this.sourceSwitching = true;
      try {
        const r = await axios.post(`/api/weather/source/${encodeURIComponent(sourceId)}`, null, { timeout: 8000 });
        if (!r.data.ok) {
          alert('切换失败：' + (r.data.message || '未知数据源'));
          return;
        }
        this.weatherSourceId = r.data.source;
        this.weatherSourceOptions = (this.weatherSourceOptions || []).map(o => ({ ...o, active: o.id === r.data.source }));
        this.pushTimeline('气象数据源切换', `已切换为「${r.data.sourceLabel}」，正在按新数据源重新拉取`, 'info');
        await this.refreshRealWeather();
      } catch (e) {
        console.error('switch weather source failed', e);
        alert('切换气象数据源失败');
      } finally {
        this.sourceSwitching = false;
      }
    },
    async loadRealWeather() {
      try {
        const r = await axios.get('/api/weather/real', {
          params: { originId: this.originId, destinationId: this.destinationId }
        });
        this.weatherPoints = r.data.points || [];
        const src = r.data.source;
        this.weatherSourceId = src || 'contest-observation';
        this.weatherSource = isLiveSource(src) ? 'live'
          : (r.data.points && r.data.points.length) ? 'fallback' : 'offline';
      } catch (e) {
        // 离线模拟天气数据
        this.weatherSource = 'fallback';
        this.weatherPoints = [
          { nodeId: 'NN', nodeName: '南宁', temperatureC: 31, precipitationMm: 2.1, windKph: 12, visibilityM: 8000 },
          { nodeId: 'YGG', nodeName: '友谊关口岸', temperatureC: 28, precipitationMm: 8.5, windKph: 25, visibilityM: 3000 },
          { nodeId: 'LS', nodeName: '谅山', temperatureC: 27, precipitationMm: 6.2, windKph: 18, visibilityM: 4500 },
          { nodeId: 'BG', nodeName: '北江', temperatureC: 30, precipitationMm: 1.0, windKph: 10, visibilityM: 9000 },
          { nodeId: 'HN', nodeName: '河内', temperatureC: 33, precipitationMm: 0.5, windKph: 8, visibilityM: 10000 }
        ];
      }
    },
    async planCrossBorder() {
      try {
        this.watchRoute(this.originId, this.destinationId);
        const r = await axios.get('/api/route/plan-with-weather', {
          params: {
            originId: this.originId,
            destinationId: this.destinationId,
            period: this.period,
            cargoType: this.cargoType,
            // 大屏不消费 aiWarning（双语预警由「生成中越双语预警」按钮单独触发），
            // 这里跳过 AI 生成，否则每 15 秒轮询都要等一次秒级 AI 调用
            withAi: false
          }
        });
        this.applyRouteResult(r.data);
        // 同步拉候选列表，让大屏把多条备选路线都展示出来
        await this.loadCandidates();
        await this.loadRealWeather();
      } catch (e) {
        console.error(e);
        // 离线兜底：加载预置演示路线
        try {
          const r = await axios.get('/data/demo-route.json');
          this.applyRouteResult(r.data);
        } catch (_) {
          alert('路线规划需要后端服务，当前为离线模式');
        }
      }
    },
    // 拉取当前起终点的全部候选路线（最多 10 条），并把"推荐"作为默认主线
    async loadCandidates() {
      if (!this.originId || !this.destinationId) return;
      try {
        const r = await axios.get('/api/routes/candidates', {
          params: {
            originId: this.originId,
            destinationId: this.destinationId,
            period: this.period,
            cargoType: this.cargoType
          }
        });
        const list = (r.data && r.data.candidates) || [];
        this.candidates = list;
        // 默认主线 = 后端标记的 recommended；找不到就第一条
        const rec = list.find(c => c.key === 'recommended');
        const nextKey = (rec || list[0] || {}).key || '';
        if (nextKey) this.selectedCandidateKey = nextKey;
        // 离线兜底场景（demo-route.json）时 candidates 为空——保留 applyRouteResult 已经画好的主线
      } catch (e) {
        console.warn('loadCandidates failed', e);
        this.candidates = [];
      }
    },
    // 切换主线：直接把候选的几何替换为主线数据，无需再次请求后端（候选已含 coords/edgeIds/edgeSpans）
    selectCandidate(key) {
      const c = (this.candidates || []).find(x => x.key === key);
      if (!c) return;
      this.selectedCandidateKey = key;
      // 1. 主线几何 = 候选的几何（coords 是 [[lat,lon],...]）
      this.routeCoords = (c.coords || []).map(p => ({ lat: p[0], lon: p[1] }));
      this.routeEdgeIds = c.edgeIds || [];
      this.routeEdgeSpans = c.edgeSpans || [];
      // 2. 同步 routeResult.route（其他面板：ETA / 碳排放 / AI 预警 / hazardPoints 都依赖它）
      if (this.routeResult && this.routeResult.route) {
        this.routeResult = {
          ...this.routeResult,
          route: {
            ...this.routeResult.route,
            pathCoords: (c.coords || []).map(p => [p[0], p[1]]),
            pathEdgeIds: c.edgeIds || [],
            pathEdgeSpans: c.edgeSpans || [],
            totalDistanceKm: c.distanceKm != null ? c.distanceKm : this.routeResult.route.totalDistanceKm,
            estimatedHours: c.hours != null ? c.hours : this.routeResult.route.estimatedHours,
            currentHours: c.hours != null ? c.hours : this.routeResult.route.currentHours,
            via: c.via || this.routeResult.route.via,
            riskCount: c.riskCount != null ? c.riskCount : this.routeResult.route.riskCount,
            riskSegments: (c.riskEdgeIds || []).map(eid => ({ edgeId: eid })),
            rerouted: !!c.softened
          }
        };
      }
      this.bilingualWarning = '';
      this.pushTimeline('主线切换', `${c.label || c.key} · ${c.hours != null ? c.hours + 'h' : '-'} · 风险 ${c.riskCount || 0} 段`, c.softened ? 'warn' : 'info');
    },
    // 确认下拉选择的起终点：提交草稿并触发规划，同时注册关注（风险时该路线会被重算推送）
    confirmSelection() {
      this.originId = this.draftOriginId;
      this.destinationId = this.draftDestinationId;
      if (this.originId === this.destinationId) {
        alert('起终点不能是同一节点，请重新选择');
        return;
      }
      this.watchRoute(this.originId, this.destinationId);
      this.planCrossBorder();
    },
    /** 注册关注某条路线：风险注入后，该路线会随其它关注路线一起被 AI 重算并推送 */
    watchRoute(originId, destinationId) {
      if (!originId || !destinationId) return;
      axios.post('/api/agent/register', null, { params: { originId, destinationId } }).catch(() => {});
    },
    // ---- Agent 实时守护：SSE 订阅后端推送，灾害发生后立即更新路线，无需等轮询 ----
    applyRouteResult(data) {
      this.routeResult = data;
      const resp = data.route || {};
      // 用后端"沿真实道路几何"插值后的坐标画线（pathCoords 为 [[lat,lon],...]），
      // 不再由节点 ID 回查组点——否则抽稀长边会画成切弯/穿山的直线
      //
      // 注意：pathCoords 缺失时**必须保留上一次已画好的路线**。
      // applyRouteResult 会被 15 秒轮询、SSE route-update、离线兜底三处调用，
      // 任何一次不完整响应（后端重算中 / 兜底数据不全）走到这里都会把 routeCoords 清空，
      // MapView 收到空坐标即清线且不重画 —— 表现就是"地图上的线有时突然消失"。
      // 真正要清空路线的只有 clearRoute() 与选路线时的显式赋值，不走这里。
      const coords = (resp.pathCoords || []).map(p => ({ lat: p[0], lon: p[1] }));
      const hasRouteGeometry = coords.length >= 2;
      if (hasRouteGeometry) {
        this.routeCoords = coords;
        // 边 ID 序列 + 每条边在 pathCoords 中的 [startIdx,endIdx] 区间：
        // 供大屏按「风险红 / 安全绿」拆段着色，与司机端保持一致
        // （几何与区间必须同进同退，只更新其一会让拆段错位）
        this.routeEdgeIds = resp.pathEdgeIds || [];
        this.routeEdgeSpans = resp.pathEdgeSpans || [];
      }
      const baseCoords = (resp.baselinePathCoords || []).map(p => ({ lat: p[0], lon: p[1] }));
      if (baseCoords.length >= 2) {
        this.baselineCoords = baseCoords;
        // 基准（未绕行）路线的边序列：大屏可把原路线穿过风险区的段标红
        this.baselineEdgeIds = resp.baselinePathEdgeIds || [];
        this.baselineEdgeSpans = resp.baselinePathEdgeSpans || [];
      }
      this.risks = data.risks || [];
      this.bilingualWarning = '';
    },
    connectAgent() {
      if (this._agentEs) this._agentEs.close();
      let retries = 0;
      this._agentEs = new EventSource('/api/agent/events');
      this._agentEs.addEventListener('route-update', (ev) => {
        try {
          const data = JSON.parse(ev.data);
          // 风险集合先落地（即便本次没带 route）：硬熔断会走下面的 early return，
          // 若风险也一并跳过，地图上的红色风险走廊就只能等 15 秒轮询才更新。
          if (Array.isArray(data.risks)) this.risks = data.risks;
          if (!data.route) {
            // 硬熔断：无可用路径事件（含 error / advice），以红色告警提示人工介入
            this.showAgentNotice(data);
            return;
          }
          // 多条关注路线会各自广播：只应用与大屏当前显示路线一致的更新，其余忽略
          if (data.origin && data.destination
              && (data.origin !== this.originId || data.destination !== this.destinationId)) {
            return;
          }
          this.applyRouteResult({ route: data.route, risks: data.risks || [], aiWarning: '' });
          // Agent 重算后候选列表也可能变化（其他备选绕开了新风险），同步刷新
          this.loadCandidates();
          this.showAgentNotice(data);
        } catch (e) {
          console.error('agent event parse failed', e);
        }
      });
      // === 演示场景 SSE 事件 ===
      // 三智能体推理进度：驱动 AgentPanel 三个图标按真实进度依次点亮
      this._agentEs.addEventListener('agent-status', (ev) => {
        try {
          const data = JSON.parse(ev.data);
          if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.onSseStatus(data);
        } catch (e) {
          console.error('agent-status parse failed', e);
        }
      });
      // 多角色触达状态：大屏触达面板实时更新（送达/确认/升级）
      this._agentEs.addEventListener('outreach-update', (ev) => {
        try {
          this.outreachStatus = JSON.parse(ev.data);
        } catch (e) {
          console.error('outreach-update parse failed', e);
        }
      });
      // 运输任务（公司派单/司机接单/撤销）：大屏派单面板实时更新
      this._agentEs.addEventListener('task-assigned', (ev) => {
        try {
          this.taskStatus = JSON.parse(ev.data);
          if (this.taskStatus && this.taskStatus.task) {
            const t = this.taskStatus.task;
            const accepted = t.status === 'ACCEPTED';
            this.pushTimeline(accepted ? '司机接单' : '公司派单',
              `${t.plate} ${t.driverName}承运 ${t.cargoName} ${t.weightT}t，${t.originId} → ${t.destinationId}`,
              accepted ? 'info' : 'warn');
          }
          this.refreshDecisionLog();
        } catch (e) {
          console.error('task-assigned parse failed', e);
        }
      });
      this._agentEs.onerror = () => {
        retries++;
        if (retries > 3) {
          this._agentEs.close();
          this._agentEs = null;
          return;
        }
        // EventSource 会自动重连，此处仅日志
        console.warn('agent SSE disconnected, will retry');
      };
    },
    showAgentNotice(data) {
      const rerouted = data.route && data.route.rerouted;
      const d = new Date(data.ts || Date.now());
      const time = d.toLocaleTimeString('zh-CN', { hour12: false });
      const reason = data.error ? '无可用路径（已硬熔断）' : (data.reason || '风险变化');
      const advice = data.advice || data.error || '';
      this.agentNotice = {
        // data.error 存在说明后端硬熔断后无可用路径，升级为红色「请人工介入」告警
        reason,
        advice,
        level: data.error ? 'danger' : (rerouted ? 'danger' : (data.risks && data.risks.length ? 'warn' : 'ok')),
        time
      };
      if (data.route) {
        const fromPort = this.inferPortFromNodeIds(data.route.baselinePathNodeIds);
        const toPort = this.inferPortFromNodeIds(data.route.pathNodeIds);
        const extra = Number(data.route.extraHours || 0);
        const result = rerouted
          ? `已重算为 ${toPort}，较 ${fromPort} 延误 ${extra}h`
          : `已重算，继续保持 ${toPort}`;
        this.pushTimeline('SSE 风险更新', `${reason} → ${result}${advice ? `；${advice}` : ''}`,
          data.error ? 'danger' : (rerouted ? 'warn' : 'ok'));
      } else {
        this.pushTimeline('SSE 告警', `${reason}${advice ? `：${advice}` : ''}`, 'danger');
      }
      clearTimeout(this._agentNoticeTimer);
      this._agentNoticeTimer = setTimeout(() => { this.agentNotice = null; }, 8000);
    },
    // === 演示场景（11幕）：沙盘/配置器/触达 事件处理 ===
    onSandboxChange(data) {
      // 熔断/恢复已走后端 SSE 推路线；此处同步时间线与日志
      const fused = (data.sections || []).filter(s => s.fused);
      const ygg = (data.sections || []).find(s => s.id === 'YGG');
      if (ygg) {
        this.pushTimeline(
          ygg.fused ? '路段熔断' : '路段恢复',
          ygg.fused
            ? `友谊关未来6h累计降雨 ${Math.round(ygg.forecastMm)}mm > ${ygg.thresholdMm}mm 阈值，penalty=100`
            : `友谊关当前预测 ${Math.round(ygg.forecastMm)}mm，低于恢复线，恢复通行`,
          ygg.fused ? 'danger' : 'ok'
        );
      }
      // 沙盘状态变化（雨量调整/熔断/水运禁航）→ AI 决策面板旧方案立即失效并重算
      if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
      // 水运断面：禁航/恢复通航时间线（双向切换场景B，仅在状态翻转时推送）
      if (data.water) {
        if (this._lastWaterBlocked !== undefined && data.water.blocked !== this._lastWaterBlocked) {
          this.pushTimeline(
            data.water.blocked ? '水运禁航' : '恢复通航',
            data.water.blocked
              ? `${data.water.blockedReason || '通航条件超限'}，平陆运河禁航，系统已自动分析公路方案`
              : '平陆运河通航条件恢复红线之上，公水联运方案恢复可用',
            data.water.blocked ? 'danger' : 'ok'
          );
        }
        this._lastWaterBlocked = data.water.blocked;
      }
      this.refreshDecisionLog();
    },
    onConfigChanged() {
      // 配置器改动（阈值/货物/触达对象）：沙盘按新阈值重估过，刷新沙盘显示
      if (this.$refs.sandboxRef) this.$refs.sandboxRef.refresh();
      this.refreshDecisionLog();
    },
    onDispatched(status) {
      this.outreachStatus = status;
      this.pushTimeline('任务变更下发', '调度员确认方案，任务变更指令已推送至司机/船东/沿岸百姓', 'warn');
      this.refreshDecisionLog();
    },
    refreshOutreach() {
      axios.get('/api/outreach/status').then(({ data }) => { this.outreachStatus = data; }).catch(() => {});
      this.refreshDecisionLog();
    },
    // 任务变更（派单/接单/撤销）：面板本地请求 + SSE 双通道，取先到者
    refreshTask(data) {
      if (data && (data.task !== undefined || data.status !== undefined)) {
        this.taskStatus = data;
      } else {
        axios.get('/api/task/current').then(({ data: d }) => { this.taskStatus = d; }).catch(() => {});
      }
      this.refreshDecisionLog();
    },
    // ---- 左侧面板拖拽缩放：拖动右边缘手柄，宽度与字号等比放大 ----
    startPanelDrag(e) {
      if (e.button !== undefined && e.button !== 0) return;
      e.preventDefault();
      this.panelDragging = true;
      const startX = e.clientX;
      const startZoom = this.panelZoom;
      const onMove = (ev) => {
        // 屏幕位移 ÷ 基准宽度 = 缩放增量（面板可视宽度 = 基准宽度 × zoom）
        const dz = (ev.clientX - startX) / PANEL_BASE_WIDTH;
        const raw = startZoom + dz;
        this.panelZoom = Math.round(
          Math.min(PANEL_ZOOM_MAX, Math.max(PANEL_ZOOM_MIN, raw)) * 100
        ) / 100;
      };
      const onUp = () => {
        this.panelDragging = false;
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
        window.removeEventListener('pointercancel', onUp);
        try { localStorage.setItem('panelZoom', String(this.panelZoom)); } catch (err) { /* 忽略 */ }
      };
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onUp);
    },
    /** 双击手柄复位到默认宽度 */
    resetPanelZoom() {
      this.panelZoom = 1;
      try { localStorage.setItem('panelZoom', '1'); } catch (err) { /* 忽略 */ }
    },
    refreshDecisionLog() {
      if (this.$refs.logRef) this.$refs.logRef.load();
    },
    // ---- 地图选点：点击任意两点 → 吸附最近节点 → 自动规划 + 天气预测 ----
    togglePickMode() {
      this.pickMode = !this.pickMode;
      if (!this.pickMode) {
        this.pickOriginNode = null;
        this.pickDestinationNode = null;
      }
    },
    haversineKm(a, b) {
      const R = 6371;
      const dLat = (b.latitude - a.latitude) * Math.PI / 180;
      const dLon = (b.longitude - a.longitude) * Math.PI / 180;
      const s = Math.sin(dLat / 2) ** 2 +
        Math.cos(a.latitude * Math.PI / 180) * Math.cos(b.latitude * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
      return 2 * R * Math.asin(Math.sqrt(s));
    },
    nearestNode(lat, lon) {
      if (!this.network || !this.network.nodes || !this.network.nodes.length) return null;
      let best = null;
      let bestDist = Infinity;
      this.network.nodes.forEach(n => {
        const d = this.haversineKm({ latitude: lat, longitude: lon }, n);
        if (d < bestDist) { bestDist = d; best = n; }
      });
      return { node: best, distKm: bestDist };
    },
    async onMapClick({ lat, lon }) {
      if (!this.pickMode) return;
      const hit = this.nearestNode(lat, lon);
      if (!hit || !hit.node) { alert('路网数据未加载'); return; }
      const n = hit.node;
      if (!this.pickOriginNode) {
        this.pickOriginNode = n;
        this.originId = n.id;
        this.draftOriginId = n.id;
        return;
      }
      this.pickDestinationNode = n;
      this.destinationId = n.id;
      this.draftDestinationId = n.id;
      if (this.pickOriginNode.id === n.id) {
        alert('起终点不能是同一节点，请重新点击终点');
        this.pickDestinationNode = null;
        return;
      }
      await this.planCrossBorder();
    },
    clearRoute() {
      this.routeResult = null;
      this.routeCoords = [];
      this.routeEdgeIds = [];
      this.routeEdgeSpans = [];
      this.baselineCoords = [];
      this.baselineEdgeIds = [];
      this.baselineEdgeSpans = [];
      this.bilingualWarning = '';
      this.pickOriginNode = null;
      this.pickDestinationNode = null;
    },
    async toggleScenario(scenarioId) {
      try {
        if (this.activeScenarios.includes(scenarioId)) {
          await axios.delete(`/api/weather/scenario/${scenarioId}`);
          this.activeScenarios = this.activeScenarios.filter(id => id !== scenarioId);
          this.pushTimeline('清除灾害场景', `场景 ${scenarioId} 已清除，等待路线重算`, 'ok');
        } else {
          await axios.post(`/api/weather/scenario/${scenarioId}`);
          this.activeScenarios.push(scenarioId);
          this.pushTimeline('注入灾害场景', `场景 ${scenarioId} 已注入，正在触发路线重算`, 'warn');
        }
        // 风险已变化：通知 AI 决策面板，旧推荐立即失效并自动重算新方案（水路/公路）
        if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
        await this.refreshRisks();
        if (this.routeResult && this.routeResult.route) {
          await this.planCrossBorder();
        }
      } catch (e) {
        console.error(e);
        alert('场景操作失败');
      }
    },
    async clearAllScenarios() {
      try {
        await axios.delete('/api/weather/risk');
        this.activeScenarios = [];
        // 风险已清除：通知 AI 决策面板重算
        if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
        await this.refreshRisks();
        this.pushTimeline('清除全部风险', '已清空所有灾害注入，路线恢复常态评估', 'ok');
        if (this.routeResult && this.routeResult.route) {
          await this.planCrossBorder();
        }
      } catch (e) {
        console.error(e);
      }
    },
    async buildBilingual() {
      if (!this.routeResult || !this.routeResult.route) {
        alert('请先规划跨境路线');
        return;
      }
      this.bilingualLoading = true;
      try {
        const r = await axios.get('/api/ai/warning-bilingual', {
          params: { originId: this.originId, destinationId: this.destinationId },
          timeout: 60000
        });
        this.bilingualWarning = r.data.message || r.data.warning || '';
      } catch (e) {
        console.error(e);
        alert('AI 预警生成失败，请稍后重试');
      } finally {
        this.bilingualLoading = false;
      }
    },
    // ---- AI + 实时气象灾害预测：DeepSeek 预测 → 注入风险 → agent 实时重算 → SSE 推新路线 ----
    async predictHazards() {
      if (!this.routeResult || !this.routeResult.route) {
        alert('请先规划跨境路线');
        return;
      }
      this.hazardLoading = true;
      try {
        const r = await axios.post('/api/ai/predict-hazards', null, {
          params: { originId: this.originId, destinationId: this.destinationId },
          timeout: 60000
        });
        this.hazardItems = r.data.hazards || [];
        this.hazardSummary = r.data.warning || '';
        this.hazardInjected = (r.data.injected || 0) > 0;
        if (this.hazardInjected) {
          this.pushTimeline('AI 灾害注入',
            `已注入 ${r.data.injected || 0} 条风险路段，正在触发实时重算`,
            'warn');
          // 风险已变化：AI 决策面板旧方案立即失效并重算（水路/公路）
          if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
        }
        // 注入已触发后端 agent 重算，SSE 会自动推新路线；这里主动刷新一次风险列表兜底
        await this.refreshRisks();
        if (this.hazardInjected && this.routeResult && this.routeResult.route) {
          this.showAgentNotice({
            type: 'warn',
            text: 'AI 预测到灾害风险，已注入 ' + r.data.injected + ' 条路段，正在实时重算路线…',
            ts: Date.now()
          });
        }
      } catch (e) {
        console.error(e);
        alert('AI 灾害预测失败，请稍后重试');
      } finally {
        this.hazardLoading = false;
      }
    },
    async clearPredictHazards() {
      try {
        const r = await axios.delete('/api/ai/predict-hazards');
        this.hazardItems = [];
        this.hazardSummary = '已清除 ' + (r.data.cleared || 0) + ' 条 AI 预测风险。';
        this.hazardInjected = false;
        if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
        await this.refreshRisks();
      } catch (e) {
        console.error(e);
        alert('清除失败');
      }
    },
    onEdgeClick(edgeId) {
      this.selectedEdgeId = edgeId;
    },
    /** 判断数据源是否为真实联网源（比赛官方 / Open-Meteo） */
    isLiveSource(src) {
      return src === 'contest-observation' || src === 'open-meteo';
    },
    /** 轮询司机端行程启动信号：检测到新的"开始导航"即让 AI 决策面板自动分析一次 */
    async pollTripSignal() {
      try {
        const { data } = await axios.get('/api/trip/signal', { timeout: 5000 });
        if (!data || !data.started || !data.startedAt) return;
        if (data.startedAt === this._lastTripStartedAt) return;
        this._lastTripStartedAt = data.startedAt;
        // 同步起终点，保证分析与司机实际行程一致
        if (data.originId) this.originId = data.originId;
        if (data.destinationId) this.destinationId = data.destinationId;
        this.pushTimeline('行程启动', '司机已开始导航，AI 自动生成常态方案对比，等待调度员人工确认', 'info');
        // 等起终点 props 同步到面板后再触发分析
        this.$nextTick(() => {
          if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.runAnalysis(false);
        });
      } catch (e) {
        // 轮询失败静默，下一轮再试
      }
    }
  },
  mounted() {
    // 恢复上次拖拽的面板缩放（演示前调好，刷新/换页不丢）
    try {
      const saved = parseFloat(localStorage.getItem('panelZoom'));
      if (!Number.isNaN(saved)) this.panelZoom = Math.min(PANEL_ZOOM_MAX, Math.max(PANEL_ZOOM_MIN, saved));
    } catch (e) { /* 无 localStorage：用默认值 */ }
    this.getStatus();
    this.loadNetwork();
    this.refreshRisks();
    this.loadScenarios();
    this.loadCustoms();
    this.loadRealWeather();
    // Agent 实时守护：订阅 SSE，灾害发生后立即收到新路线（毫秒级），轮询仅作兜底
    this.connectAgent();
    this._riskTimer = setInterval(() => {
      this.refreshRisks();
      if (this.routeResult && this.routeResult.route) {
        this.planCrossBorder();
      }
    }, 15000);
    // 司机端"开始导航"信号：车一动就自动触发一次 AI 六维分析（常态方案对比），
    // 由调度员人工确认路线后再下发，分析不再只在熔断后才做
    this._tripTimer = setInterval(() => { this.pollTripSignal(); }, 2500);
    this.loadWeatherSource();
  },
  beforeUnmount() {
    if (this._riskTimer) clearInterval(this._riskTimer);
    if (this._tripTimer) clearInterval(this._tripTimer);
    if (this._agentEs) this._agentEs.close();
    if (this._agentNoticeTimer) clearTimeout(this._agentNoticeTimer);
  }
};
</script>

<style scoped>
.app-root {
  font-family: 'Segoe UI', Inter, system-ui, sans-serif;
  color: #33415c;
  background: #edf1f9;
  min-height: 100vh;
  position: relative;
  overflow-x: hidden;
}
/* 极光渐变光斑背景：缓慢漂移的动态特效 */
.app-root::before {
  content: '';
  position: fixed;
  inset: -20%;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse 42% 34% at 18% 12%, rgba(99, 102, 241, 0.16), transparent 65%),
    radial-gradient(ellipse 38% 30% at 82% 8%, rgba(34, 211, 238, 0.14), transparent 65%),
    radial-gradient(ellipse 46% 38% at 88% 78%, rgba(168, 85, 247, 0.12), transparent 65%),
    radial-gradient(ellipse 40% 32% at 8% 86%, rgba(56, 189, 248, 0.12), transparent 65%);
  animation: aurora-drift 26s ease-in-out infinite alternate;
}
@keyframes aurora-drift {
  0%   { transform: translate3d(0, 0, 0) scale(1); }
  50%  { transform: translate3d(-3%, 2%, 0) scale(1.06); }
  100% { transform: translate3d(3%, -2%, 0) scale(1.02); }
}
.app-root > * { position: relative; z-index: 1; }
.app-header {
  display:flex; justify-content:space-between; align-items:center;
  padding:14px 24px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  color:#1c2a44;
  border-bottom: 1px solid rgba(120, 140, 190, 0.18);
  box-shadow: 0 4px 28px rgba(60, 80, 140, 0.10);
  position: relative;
  z-index: 10;
}
.app-header::after {
  content: ''; position: absolute; bottom: 0; left: 0; right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, #4f6df5, #22b8cf, #7c5cff, transparent);
  background-size: 200% 100%;
  opacity: 0.75;
  animation: header-line 6s linear infinite;
}
@keyframes header-line {
  from { background-position: 0% 0; }
  to { background-position: 200% 0; }
}
.app-header h1 { margin:0; font-size:18px; font-weight:700; letter-spacing: .5px;
  background: linear-gradient(120deg, #4f6df5 10%, #22b8cf 50%, #7c5cff 90%);
  -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.header-right { display:flex; align-items:center; gap:12px; }
.status-badge { padding:6px 14px; border-radius:20px; background:rgba(255,255,255,0.8); color:#7c8ca6; font-size:12px; border:1px solid rgba(120,140,190,0.22); transition:all .3s; }
.status-badge.ready { background:rgba(22,163,74,0.10); color:#16a34a; border-color:rgba(22,163,74,0.3); box-shadow:0 0 16px rgba(22,163,74,0.15); }

/* Agent banner */
.agent-banner { display:flex; align-items:center; gap:12px; padding:12px 24px; font-size:13px; animation:slideDown .4s cubic-bezier(.16,1,.3,1); }
.agent-banner.danger { background:rgba(225,29,72,0.92); color:#fff; border-bottom:2px solid #e11d48; }
.agent-banner.warn { background:rgba(255,251,235,0.92); color:#b45309; border-bottom:2px solid rgba(217,119,6,0.4); }
.agent-banner.ok { background:rgba(240,253,244,0.92); color:#15803d; border-bottom:2px solid rgba(22,163,74,0.3); }
.agent-banner.info { background:rgba(236,254,255,0.92); color:#0e7490; border-bottom:2px solid rgba(14,155,176,0.3); }
.agent-banner-title { font-weight:700; white-space:nowrap; }
.agent-banner-text { flex:1; }
.agent-banner-time { opacity:.7; font-size:11px; white-space:nowrap; font-variant-numeric:tabular-nums; }
@keyframes slideDown { from { transform:translateY(-100%); opacity:0; } to { transform:translateY(0); opacity:1; } }

/* Main layout */
.main { display:flex; height:calc(100vh - 65px); }
.map-area { flex:1; position:relative; background:#dfe7f5; }

/* Side panel —— 棱彩发光面板
   底色不再是一片纯白：接上与 .app-root::before 极光同源的色相（靛蓝/天蓝/青/紫/品红），
   多层 background 做棱彩流动 + 右缘多色发光边；卡片仍是近不透明白，数据可读性不受影响。
   注意：.panel 自身是滚动容器，背景默认固定在元素边框盒上、不随内容滚动 —— 正好当"背光"用。 */
.panel {
  position: relative;
  width:360px; padding:14px;
  background-color: rgba(255, 255, 255, 0.55);
  background-image:
    /* 右缘棱彩细线：竖向多色，像棱镜分光 */
    linear-gradient(180deg,
      rgba(99,102,241,0.90) 0%, rgba(56,189,248,0.90) 28%,
      rgba(34,211,238,0.90) 52%, rgba(168,85,247,0.90) 76%,
      rgba(236,72,153,0.85) 100%),
    /* 右缘柔光晕 */
    linear-gradient(to left,
      rgba(129,140,248,0.40) 0%, rgba(56,189,248,0.18) 40%, transparent 100%),
    /* 大面积极光底层：四色斜向渐变（首尾同色，漂移无接缝），缓慢往复形成棱彩流动。
       浓度即"菜单底色有多彩"：想更艳就调大这几处 alpha（0.28/0.22/0.20/0.26/0.20/0.28）。 */
    linear-gradient(150deg,
      rgba(99,102,241,0.28) 0%, rgba(56,189,248,0.22) 22%,
      rgba(34,211,238,0.20) 45%, rgba(168,85,247,0.26) 70%,
      rgba(236,72,153,0.20) 88%, rgba(99,102,241,0.28) 100%);
  background-size: 3px 100%, 40px 100%, 240% 240%;
  background-position: right top, right top, 0% 50%;
  background-repeat: no-repeat, no-repeat, no-repeat;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  overflow:auto;
  border-right: 1px solid rgba(255, 255, 255, 0.50);
  box-shadow:
    4px 0 28px rgba(60, 80, 140, 0.10),
    inset 0 0 70px rgba(255, 255, 255, 0.30);
  animation:
    prism-drift 24s ease-in-out infinite alternate,
    prism-glow 16s ease-in-out infinite;
}
/* 棱彩流动：极光底层横向缓慢往复 */
@keyframes prism-drift {
  0%   { background-position: right top, right top, 0% 50%; }
  100% { background-position: right top, right top, 100% 50%; }
}
/* 发光呼吸：外发光在靛蓝 → 天蓝 → 紫之间循环 */
@keyframes prism-glow {
  0%, 100% { box-shadow: 4px 0 30px rgba(99,102,241,0.26), inset 0 0 70px rgba(255,255,255,0.30); }
  33%      { box-shadow: 4px 0 30px rgba(56,189,248,0.26), inset 0 0 70px rgba(255,255,255,0.38); }
  66%      { box-shadow: 4px 0 30px rgba(168,85,247,0.26), inset 0 0 70px rgba(255,255,255,0.34); }
}
/* 尊重系统「减少动态效果」设置 */
@media (prefers-reduced-motion: reduce) {
  .panel { animation: none; }
}
.panel::-webkit-scrollbar { width:6px; }
.panel::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, rgba(99,102,241,0.45), rgba(34,211,238,0.45), rgba(168,85,247,0.45));
  border-radius:3px;
}

/* 面板宽度拖拽手柄：宽度与字号等比放大，双击复位 */
.panel-resizer {
  flex: 0 0 7px;
  cursor: col-resize;
  background: linear-gradient(180deg, rgba(99,102,241,0.18), rgba(34,211,238,0.18), rgba(168,85,247,0.18));
  display: flex; align-items: center; justify-content: center;
  transition: background .18s ease;
  z-index: 5;
}
.panel-resizer:hover, .panel-resizer.dragging { background: rgba(79, 109, 245, 0.30); }
.resizer-grip {
  width: 3px; height: 34px; border-radius: 2px;
  background: linear-gradient(180deg, #6366f1, #22d3ee, #a855f7);
  pointer-events: none;
}
.panel-resizer:hover .resizer-grip, .panel-resizer.dragging .resizer-grip { background:#fff; }

/* Cards */
.card {
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(105, 125, 175, 0.16);
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 12px;
  transition: all .3s ease;
  position: relative;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(60, 80, 140, 0.07);
}
.card::before {
  content: '';
  position: absolute; top:0; left:0; right:0;
  height: 2px;
  background: linear-gradient(90deg, transparent, #4f6df5, #22b8cf, transparent);
  opacity: 0;
  transition: opacity .3s;
}
.card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 32px rgba(60, 80, 140, 0.13);
}
.card:hover::before { opacity: 1; }
.card h3 {
  margin: 0 0 10px 0; font-size:13px; font-weight:700;
  color:#5b6b85; text-transform:uppercase; letter-spacing:.8px;
}
.card.small { padding:10px; font-size:12px; }

.row { display:flex; gap:8px; margin-top:8px; flex-wrap:wrap; }
.coords label { display:block; margin-top:8px; color:#8b99b5; font-size:11px; text-transform:uppercase; letter-spacing:.5px; }
.coords select {
  width:100%; padding:8px 10px; margin-top:4px; box-sizing:border-box;
  background:#f4f6fc; border:1px solid #dbe3f2;
  border-radius:8px; color:#33415c; font-size:13px; outline:none;
  transition:border-color .2s, box-shadow .2s;
}
.coords select:focus { border-color:#4f6df5; box-shadow:0 0 0 3px rgba(79,109,245,0.12); }

/* Buttons */
.btn {
  padding:8px 16px; border-radius:8px; border:none;
  color:#fff; cursor:pointer; font-size:12px; font-weight:600;
  transition:all .2s ease; position:relative; overflow:hidden;
}
.btn::after {
  content:''; position:absolute; top:0; left:-80%;
  width:60%; height:100%;
  background:linear-gradient(100deg, transparent, rgba(255,255,255,0.45), transparent);
  transform:skewX(-20deg);
  transition:left .45s ease;
}
.btn:hover::after { left:120%; }
.btn:active { transform:scale(.97); }
.btn.primary { background:linear-gradient(135deg, #4f6df5, #22b8cf); box-shadow:0 4px 18px rgba(79,109,245,0.35); }
.btn.primary:hover { box-shadow:0 6px 24px rgba(79,109,245,0.45); }
.btn:not(.primary):not(.outline):not(.muted):not(.tiny):not(.scenario-btn):not(.pick-on) { background:#eef1f8; border:1px solid #dbe3f2; color:#33415c; }
.btn:hover:not(.primary):not(.outline):not(.muted):not(.tiny):not(.scenario-btn):not(.pick-on) { background:#e4eaf6; }
.btn.small { padding:6px 10px; font-size:11px; }
.btn.muted { background:#eef1f8; color:#8b99b5; }
.btn.outline { background:transparent; color:#0e9bb0; border:1px solid rgba(14,155,176,0.4); }
.btn.outline:hover { border-color:#0e9bb0; background:rgba(14,155,176,0.08); }
.btn.outline-light { background:rgba(255,255,255,0.25); color:#fff; border:1px solid rgba(255,255,255,.5); }
.btn.pick-on { background:linear-gradient(135deg, #16a34a, #34d399); box-shadow:0 0 18px rgba(22,163,74,0.35); animation:pulse-green 2s infinite; }
@keyframes pulse-green { 0%,100%{box-shadow:0 0 8px rgba(22,163,74,0.18)} 50%{box-shadow:0 0 22px rgba(22,163,74,0.45)} }

/* Scenario buttons */
.btn.scenario-btn {
  width:100%; text-align:left;
  background:#f6f8fd; color:#5b6b85;
  border:1px solid #e2e8f5; display:block;
}
.btn.scenario-btn:hover { border-color:rgba(217,119,6,0.45); }
.btn.scenario-btn.active { background:linear-gradient(135deg, #e11d48, #f43f5e); color:#fff; border-color:#e11d48; box-shadow:0 4px 18px rgba(225,29,72,0.35); }
.scenario-item { margin-top:6px; }
.scenario-name { font-weight:600; }
.scenario-desc { display:block; font-size:11px; color:#8b99b5; margin-top:2px; }
.btn.scenario-btn.active .scenario-desc { color:rgba(255,255,255,0.85); }

.muted { color:#8b99b5; font-size:12px; }

/* Route state badge */
.route-state { float:right; font-size:11px; padding:3px 10px; border-radius:12px; font-weight:600; }
.route-state.clear { background:rgba(22,163,74,0.12); color:#16a34a; }
.route-state.warning { background:rgba(217,119,6,0.12); color:#d97706; }
.route-state.blocked { background:rgba(225,29,72,0.12); color:#e11d48; }

.compare-row { display:flex; justify-content:space-between; margin-top:6px; font-size:12px; color:#64748f; }
.compare-row.warn { color:#e11d48; font-weight:600; }
.compare-label { color:#93a2ba; }

.warning-box {
  background:rgba(217,119,6,0.07); padding:12px; border-radius:8px;
  margin-top:8px; white-space:pre-wrap; font-size:12px; line-height:1.6;
  max-height:320px; overflow:auto; border:1px solid rgba(217,119,6,0.2);
  color:#b45309;
}

.hazard-list { margin-top:8px; display:flex; flex-direction:column; gap:6px; }
.hazard-item { border-left:3px solid #f59e0b; background:#f8fafd; padding:8px 10px; border-radius:0 6px 6px 0; font-size:12px; line-height:1.5; }
.hazard-item.sev-critical { border-left-color:#e11d48; background:rgba(225,29,72,0.06); }
.hazard-item.sev-high { border-left-color:#ea7a2e; background:rgba(234,122,46,0.06); }
.hazard-item.sev-medium { border-left-color:#f59e0b; }
.hazard-type { font-weight:600; margin-right:8px; color:#33415c; }
.hazard-city { color:#64748f; }
.hazard-city small { color:#93a2ba; }
.hazard-sev { float:right; color:#8b99b5; }
.hazard-reason { color:#5b6b85; margin-top:3px; }
.hazard-advice { color:#16a34a; margin-top:2px; }

/* 候选路线列表 */ollama listollama listollama list
.candidate-list {
  display:flex; flex-direction:column; gap:8px; margin-top:6px;
}
.candidate-item {
  border:1px solid #e2e8f5;
  border-radius:8px; padding:8px 10px;
  background:#f8fafd;
  cursor:pointer; transition:all .15s ease;
  border-left:3px solid #22b8cf;
}  .candidate-item:hover { background:#eef4fb; border-color:rgba(34,184,207,0.5); }
.candidate-item.recommended { border-left-color:#16a34a; }
.candidate-item.softened { border-left-color:#ea7a2e; }
.candidate-item.active {
  background:rgba(34,184,207,0.10);
  border-color:#22b8cf;
  box-shadow:0 0 0 1px rgba(34,184,207,0.35);
}
.candidate-head { display:flex; align-items:center; gap:6px; font-weight:600; color:#33415c; font-size:13px; }
.candidate-label { flex:1; }
.candidate-badge {
  font-size:10px; padding:1px 6px; border-radius:8px; font-weight:600;
}
.candidate-badge.rec { background:#16a34a; color:#fff; }
.candidate-badge.soften { background:#ea7a2e; color:#fff; }
.candidate-meta {
  display:flex; gap:10px; margin-top:4px;
  font-size:11px; color:#64748f; flex-wrap:wrap;
}
.candidate-meta .warn { color:#ea7a2e; font-weight:600; }
.candidate-note {
  font-size:11px; color:#64748f; margin-top:4px; font-style:italic;
  background:rgba(50,70,120,0.06); padding:3px 6px; border-radius:4px;
}
.candidate-via {
  font-size:11px; color:#8b99b5; margin-top:4px;
  word-break:break-all; line-height:1.4;
}

.risk-item { display:flex; gap:8px; align-items:center; margin-top:4px; font-size:12px; }
.risk-edge { font-weight:600; background:#eef1f8; padding:2px 8px; border-radius:4px; color:#0e9bb0; }
.risk-sev { padding:2px 8px; border-radius:4px; font-size:11px; font-weight:700; }
.sev-critical { background:#e11d48; color:#fff; }
.sev-high { background:#ea7a2e; color:#fff; }
.sev-medium { background:#f59e0b; color:#fff; }
.risk-reason { color:#64748f; }

.status-pre {
  background:rgba(50,70,120,0.06); padding:8px; border-radius:6px;
  max-height:120px; overflow:auto; font-size:11px; color:#7c8ca6;
  font-family:'JetBrains Mono','Fira Code',monospace;
}
.raw-header { cursor:pointer; user-select:none; display:flex; justify-content:space-between; align-items:center; }
.raw-toggle { font-size:11px; color:#0e9bb0; font-weight:normal; }

.inline-label { font-size:11px; color:#8b99b5; }
.inline-label select {
  margin-left:4px; padding:4px 8px;
  background:#f4f6fc; border:1px solid #dbe3f2;
  border-radius:6px; color:#33415c; font-size:12px;
}
.btn.tiny { padding:3px 10px; font-size:11px; border-radius:6px; background:#eef1f8; color:#5b6b85; border:1px solid #dbe3f2; }
.btn.tiny:hover { background:#e4eaf6; color:#1c2a44; border-color:#c4d0e8; }
.btn.tiny.outline { background:transparent; color:#0e9bb0; border:1px solid rgba(14,155,176,0.4); }
.btn.tiny.outline:hover { background:rgba(14,155,176,0.08); }

.customs-item { border:1px solid #e2e8f5; border-radius:10px; padding:10px 12px; margin-top:6px; background:#f8fafd; transition:all .3s; }
.customs-item.adjusted { border-color:#ea7a2e; background:#fdf4ea; }
.customs-head { display:flex; align-items:center; gap:6px; font-size:13px; }
.customs-name { font-weight:700; color:#1c2a44; font-size:13px; }
.customs-hours { background:linear-gradient(135deg, #4f6df5, #22b8cf); color:#fff; padding:3px 10px; border-radius:10px; font-weight:700; font-size:11px; }
.customs-note { color:#ea7a2e; font-size:11px; font-weight:600; }

.kb-input { flex:1; padding:8px 10px; background:#f4f6fc; border:1px solid #dbe3f2; border-radius:8px; color:#33415c; font-size:13px; outline:none; }
.kb-input:focus { border-color:#4f6df5; box-shadow:0 0 0 3px rgba(79,109,245,0.12); }
.kb-list { margin-top:8px; }
.kb-item { border-bottom:1px solid #eef1f8; padding:6px 0; }
.kb-title { font-size:12px; font-weight:600; color:#0e9bb0; }
.kb-cat { font-size:10px; font-weight:400; background:#eef1f8; color:#8b99b5; padding:1px 8px; border-radius:8px; margin-left:4px; }
.kb-content { font-size:11px; color:#64748f; line-height:1.5; margin-top:3px; }

.weather-grid { display:grid; grid-template-columns:1fr 1fr; gap:6px; margin-top:8px; }
.source-switch-row {
  margin-bottom: 8px;
}
.source-select {
  flex: 1;
  padding: 7px 10px;
  background: #f4f6fc;
  border: 1px solid #dbe3f2;
  border-radius: 8px;
  color: #33415c;
  font-size: 12px;
  outline: none;
  cursor: pointer;
  transition: border-color .2s, box-shadow .2s;
}
.source-select:focus {
  border-color: #4f6df5;
  box-shadow: 0 0 0 3px rgba(79, 109, 245, 0.12);
}
.source-select:disabled {
  opacity: .6;
  cursor: not-allowed;
}
.weather-cell {
  border:1px solid #e2e8f5; border-radius:10px;
  padding:8px 10px; background:#f8fafd;
  transition:all .3s; position:relative; overflow:hidden;
}
.weather-cell::before {
  content:''; position:absolute; top:0; left:0; right:0; height:2px;
  background:linear-gradient(90deg, #4f6df5, #22b8cf); opacity:0; transition:opacity .3s;
}
.weather-cell:hover { border-color:rgba(79,109,245,0.3); transform:translateY(-1px); box-shadow:0 6px 18px rgba(60,80,140,0.10); }
.weather-cell:hover::before { opacity:.7; }
.w-name { font-size:11px; font-weight:600; color:#0e9bb0; display:flex; align-items:center; gap:4px; }
.w-id { font-size:9px; color:#93a2ba; font-weight:400; }
.w-temp { font-size:18px; font-weight:700; margin-top:2px; color:#1c2a44; }
.w-sub { font-size:10px; color:#8b99b5; margin-top:2px; display:flex; gap:6px; flex-wrap:wrap; }

.timeline-list { margin-top:8px; display:flex; flex-direction:column; gap:10px; }
.timeline-item { display:flex; align-items:flex-start; gap:10px; position:relative; }
.timeline-item::before { content:''; position:absolute; left:29px; top:16px; bottom:-10px; width:1px; background:#dbe3f2; }
.timeline-item:last-child::before { display:none; }
.timeline-time { font-size:10px; color:#93a2ba; width:58px; flex-shrink:0; margin-top:2px; font-variant-numeric:tabular-nums; }
.timeline-dot { width:10px; height:10px; border-radius:50%; margin-top:4px; background:#93a2ba; flex-shrink:0; z-index:1; }
.timeline-dot.ok { background:#16a34a; box-shadow:0 0 8px rgba(22,163,74,0.4); }
.timeline-dot.warn { background:#ea7a2e; box-shadow:0 0 8px rgba(234,122,46,0.4); }
.timeline-dot.danger { background:#e11d48; box-shadow:0 0 8px rgba(225,29,72,0.4); }
.timeline-body { flex:1; min-width:0; }
.timeline-title { font-size:12px; font-weight:700; color:#33415c; }
.timeline-desc { font-size:11px; color:#64748f; line-height:1.45; margin-top:2px; word-break:break-word; }
</style>

<style>
/* 全局：页面底色与滚动区与浅色主题一致（非 scoped） */
html, body { background: #edf1f9; }
body { margin: 0; }
/* 拖拽面板宽度时：禁止选中文本（否则横向拖出整片蓝色选区）+ 全屏保持 col-resize */
.panel-dragging, .panel-dragging * { user-select: none !important; cursor: col-resize !important; }
</style>