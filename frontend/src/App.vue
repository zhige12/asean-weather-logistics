<template>
  <div class="app-root" :class="{ 'panel-dragging': panelDragging }">
    <header class="app-header">
      <h1>面向东盟跨境物流的突发气象预警与多路线协同调度平台 · 调度大屏</h1>
      <div class="header-right">
        <button class="btn small" :class="perfLite ? 'primary' : 'outline'"
                :title="perfLite ? (perfAuto ? '本机帧率自检未达阈值，已自动关掉毛玻璃与逐帧重绘的装饰特效（功能、数据、红绿分段均不变）；点一下可强制恢复满特效' : '轻界面模式已开启：关掉毛玻璃与流光等逐帧重绘的装饰特效（功能、数据、红绿分段均不变），再点一次恢复满特效') : '当前为满特效。若这台机器不只拖图卡、点按钮也发滞，点这里关掉装饰特效'"
                @click="togglePerfLite">{{ perfLite ? (perfAuto ? '轻界面（自动）' : '轻界面已开') : '轻界面' }}</button>
        <!-- 司机端入口：原先用 .outline-light（白字 + 半透明白底）压在浅色毛玻璃头部上几乎看不见，
             换成品牌橙红渐变胶囊 + 货车 SVG 图标（不用 emoji，与司机端图标规范一致） -->
        <button class="btn driver-entry" title="在新窗口打开司机端（与手机同款界面），演示时可并排看调度下发 → 司机接单"
                @click="openDriver">
          <svg class="de-ico" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M2 6h11v9.5H2z"/>
            <path d="M13 9h4l3 3.4V15.5h-7z"/>
            <circle cx="6.5" cy="17.8" r="1.8"/><circle cx="16.8" cy="17.8" r="1.8"/>
          </svg>
          <span>司机端</span>
          <span class="de-arrow">↗</span>
        </button>
        <button class="btn small" @click="getStatus">OSM 状态</button>
        <span class="status-badge" :class="{ready: status && status.loaded}">{{ status && status.loaded ? 'OSM 已加载' : 'OSM 未加载' }}</span>
      </div>
    </header>

    <div v-if="agentNotice" class="agent-banner" :class="agentNotice.level || 'info'">
      <span class="agent-banner-title">Agent 实时守护</span>
      <span class="agent-banner-text">{{ agentNotice.reason }} {{ agentNotice.advice }}</span>
      <span class="agent-banner-time">{{ agentNotice.time }}</span>
    </div>

    <div class="main">
      <aside class="panel" :style="panelStyle">
        <section class="card">
          <h3>跨境路径规划</h3>
          <div class="coords">
            <!-- 自由输入 + 关键字联想（本地地名库→天地图 POI），空输入点开即常用地点下拉，
                 选中即吸附路网节点；与地图选点共用同一套草稿起终点 -->
            <PlaceInput
              label="起点"
              placeholder="输入或点选起点，如 南宁 / 青秀"
              v-model="draftOriginId"
              v-model:name="originName"
              ref="originInput"
            />
            <PlaceInput
              label="终点"
              placeholder="输入或点选终点，如 河内 / 还剑湖"
              v-model="draftDestinationId"
              v-model:name="destinationName"
              ref="destInput"
            />
            <div class="row">
              <button class="btn primary" @click="confirmSelection">确认选择并规划</button>
              <button class="btn outline" @click="clearRoute">清空</button>
            </div>
            <div class="row">
              <button class="btn" :class="pickMode ? 'pick-on' : 'outline'" @click="togglePickMode">
                {{ pickMode ? '取消地图选点' : '地图选点' }}
              </button>
              <span class="muted" style="align-self:center">{{ pickHintText }}</span>
            </div>
          </div>
        </section>

        <!-- 自然语言调度舱：一句话驱动查风险/规划/决策分析与沙盘注入（写操作先预览后确认） -->
        <CopilotPanel
          :origin-id="originId"
          :destination-id="destinationId"
          @executed="onCopilotExecuted"
        />

        <!-- 第四幕：公司派单（司机端收到后自动切物流任务模式） -->
        <TaskDispatchPanel ref="taskPanelRef" :task-status="taskStatus" @refresh="refreshTask" @analyze="onPreDispatchAnalysis" />

        <!-- 全域受阻：区域性灾害（台风 / 大范围暴雨）同时盖住多条出境走廊，平陆运河也禁航。
             此时算路引擎仍会给出一条「向前」的路线——那是把已封闭路段降级放行换来的，
             继续派单等于从一个风险区换进另一个风险区，所以把返回/等待的结论顶到面板上方 -->
        <section v-if="corridorView" class="card corridor-advisory">
          <h3>
            {{ corridorView.title }}
            <span class="route-state blocked">{{ corridorShort }}</span>
          </h3>
          <div class="corridor-advice">{{ corridorView.text }}</div>
          <div class="muted corridor-basis" :title="corridorAdvisory.waitBasis">{{ corridorAdvisory.waitBasis }}</div>
          <div class="row" style="margin-top:6px">
            <button class="btn outline tiny" @click="dismissCorridorAdvisory">知道了</button>
            <span class="muted" style="align-self:center">走廊或成因变化会重新提醒</span>
          </div>
        </section>

        <!-- 平陆运河禁航：点名影响在途水运对象。
             物流司机换线必须由大屏下发任务变更（司机端只等确认）；
             普通用户在司机端自助弹窗换线，这里只做知会 -->
        <section v-if="canalAlert" class="card">
          <h3>平陆运河禁航 · 影响在途对象</h3>
          <div class="muted">{{ canalAlert.reason }}
            <span v-if="canalAlert.trigger" class="canal-trigger">· 来源：{{ canalAlert.trigger }}</span>
          </div>
          <div v-if="canalAlert.driverOnCanal" class="row" style="margin-top:6px">
            <span style="align-self:center">司机 {{ canalAlert.driverName }} 正在以陆水联运导航去平陆运河，应为其改换公路方案：</span>
            <button v-if="!canalAlert.dispatched" class="btn primary" :disabled="canalAlert.dispatching"
                    @click="switchDriverToRoad">{{ canalAlert.dispatching ? '下发中…' : '为司机换公路方案' }}</button>
            <span v-else class="muted">已下发：司机端将预览新公路路线并确认接收</span>
          </div>
          <div v-if="canalAlert.watcherCount" class="muted" style="margin-top:4px;font-size:12px">
            {{ canalAlert.watcherCount }} 条在途路线处于坐船方案，普通用户司机端已自动弹出公路替代路线供自助切换
          </div>
          <div class="row" style="margin-top:6px">
            <button class="btn outline tiny" @click="canalAlert = null">知道了</button>
          </div>
        </section>

        <!-- 第一/二幕：决策沙盘（官方推送熔断 + 滑块观察决策边界） -->
        <DecisionSandbox ref="sandboxRef" @sandbox-change="onSandboxChange" @quick-inject="onQuickInject">
          <!-- 通关时效并入决策沙盘：simMode/pushed 由沙盘经作用域插槽下发。
               未点「模拟」只读展示真实时效，点「模拟」后才可手动调节各口岸 -->
          <template #extra="{ simMode, pushed }">
            <div class="sandbox-sub">
              <h3>
                通关时效（customs_efficiency）
                <span v-if="pushed && !simMode" class="route-state">真实 · 只读</span>
                <span v-else-if="simMode" class="route-state ok">模拟 · 可调</span>
              </h3>
              <div v-if="!pushed" class="muted" style="line-height:1.6">
                未拉取真实数据：请先在上方「决策沙盘」点击「拉取 GOWFS 数据」查看真实通关时效；再点「模拟」方可手动调节各口岸时效。
              </div>
              <template v-else>
                <div class="row">
                  <label class="inline-label">时段
                    <select v-model="period" :disabled="!simMode" @change="replanIfNeeded">
                      <option value="normal">普通</option>
                      <option value="peak">高峰 ×1.3~1.4</option>
                      <option value="holiday">节假日 ×1.7~1.8</option>
                    </select>
                  </label>
                  <label class="inline-label">货类
                    <select v-model="cargoType" :disabled="!simMode" @change="replanIfNeeded">
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
                    <button class="btn tiny" :disabled="!simMode" @click="adjustCustoms(p.portId, -0.5)">-0.5h</button>
                    <button class="btn tiny" :disabled="!simMode" @click="adjustCustoms(p.portId, 0.5)">+0.5h</button>
                    <button class="btn tiny outline" :disabled="!simMode" @click="resetCustoms(p.portId)">重置</button>
                  </div>
                </div>
                <div class="muted" style="margin-top:6px">基准：友谊关 {{ baseHours('YGG') }}h / 芒街 {{ baseHours('MC') }}h</div>
              </template>
            </div>
          </template>
        </DecisionSandbox>

        <!-- 第三幕：多智能体协同决策分析（含第四幕确认下发入口） -->
        <AgentPanel
          ref="agentPanelRef"
          :originId="originId"
          :destinationId="destinationId"
          :canalLiveBlock="canalBlockLive ? canalBlockLive.blocked : undefined"
          :canalLiveReason="canalBlockLive && canalBlockLive.blocked ? canalBlockLive.reason : ''"
          :taskAccepted="!!(taskStatus && taskStatus.task && taskStatus.task.status === 'ACCEPTED')"
          @dispatched="onDispatched"
          @dispatch-task="onDispatchTask"
          @preview-candidate="onPreviewCandidate"
        />

        <!-- 第五/六幕：多角色触达 + 分级叫应追踪 -->
        <OutreachPanel :status="outreachStatus" @refresh="refreshOutreach" />

        <section class="card">
          <h3>
            灾害时间线（实时）
            <span class="route-state">{{ filteredTimeline.length }} / {{ hazardTimeline.length }} 条</span>
          </h3>
          <!-- 历史事件只留最近 30 条、会越堆越长：给它一个限高滚动窗口 + 关键字搜索，
               输入「台风 / 熔断 / 绕行 / 河内」等即可从历史里筛出相关事件 -->
          <input
            v-model="timelineQuery"
            class="timeline-search"
            type="text"
            placeholder="搜索历史记录，如 台风 / 熔断 / 绕行 / 河内…"
          />
          <div v-if="filteredTimeline.length" class="timeline-list">
            <div v-for="(item, i) in filteredTimeline" :key="i" class="timeline-item">
              <span class="timeline-time">{{ item.time }}</span>
              <span class="timeline-dot" :class="item.level"></span>
              <div class="timeline-body">
                <div class="timeline-title">{{ item.title }}</div>
                <div class="timeline-desc">{{ item.desc }}</div>
              </div>
            </div>
          </div>
          <div v-else class="muted">{{ timelineQuery ? '没有匹配的记录' : '暂无灾害事件' }}</div>
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
                v-for="opt in sourceSelectOptions"
                :key="opt.id"
                :value="opt.id"
                :disabled="!opt.configured"
              >
                {{ opt.label }}{{ opt.configured ? '' : '（未配置）' }}
              </option>
            </select>
          </div>
          <!-- 选了哪个源与数字实际来自哪个源是两回事，这一行把后者说清楚 -->
          <div class="muted source-note" :class="{ warn: weatherSourceMismatch }" :title="weatherSourceNote">
            {{ weatherSourceNote }}
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
          <h3>AI 预警（AI 双语 + RAG）</h3>
          <div class="row">
            <button class="btn" @click="buildBilingual" :disabled="bilingualLoading">{{ bilingualLoading ? 'AI 生成中，约 10 秒…' : '生成中越双语预警' }}</button>
          </div>
          <div v-if="bilingualWarning" class="warning-box">{{ bilingualWarning }}</div>
        </section>

        <section class="card">
          <h3>AI 灾害预测（实时气象 + AI）</h3>
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

        <!-- 第十幕：开放平台智能体配置器（成长路径三步走已并入该卡尾段，
             让「众创」从第 1 步就看得见，而不是单独摆在第三步） -->
        <PlatformConfigurator @config-changed="onConfigChanged" />

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
          :corridor="showCorridor ? corridorData : null"
          :pick-mode="pickMode"
          :pick-origin="pickOriginNode"
          :pick-destination="pickDestinationNode"
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
import CopilotPanel from './components/CopilotPanel.vue';
import OutreachPanel from './components/OutreachPanel.vue';
import PlatformConfigurator from './components/PlatformConfigurator.vue';
import DecisionLogTimeline from './components/DecisionLogTimeline.vue';
import PlaceInput from './components/PlaceInput.vue';
import { advisoryView, ACTION_SHORT } from './utils/corridorAdvisory';
import { fetchPlaceList } from './utils/geocoder.js';
import { isLite, isAutoLite, onLiteChange, toggleLite } from './utils/perfMode.js';

// 左侧面板拖拽缩放边界：1=默认 360px；上限约 2.2 倍（≈790px，投影仪/后排也能看清）
const PANEL_ZOOM_MIN = 0.85;
const PANEL_ZOOM_MAX = 2.2;
/** 面板基准宽度（CSS 中的 .panel width），拖拽换算用 */
const PANEL_BASE_WIDTH = 360;
/**
 * 地图选点吸附护栏（km）：点击点到此距离内才认最近路网节点。
 * 路网节点只沿中越走廊密铺（走廊内中位吸附距离 ~6km），走廊外点到哪都
 * 会吸到同几个远处节点，不拦就会出现“点了一下、起点跑到几百公里外”。
 */
const PICK_SNAP_MAX_KM = 60;
/**
 * 浏览器是否支持 zoom。Chromium 与 Firefox 126+ 均支持，且 zoom 参与布局尺寸计算，
 * 因此面板宽度与内部字号能一起等比放大（子组件字号是写死的 px，靠父级 zoom 才会放大）。
 * 不支持时退化为"只加宽"——字不会变大，但拖动始终有可见反馈。
 */
const ZOOM_SUPPORTED = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
  && CSS.supports('zoom', '1.5');

// 气象数据源简称（标题与来源说明共用）：id 与后端 WeatherProvider.id() 一一对应
const WEATHER_SOURCE_SHORT = {
  'contest-observation': 'CRA40',
  'open-meteo': 'Open-Meteo',
  simulated: '离线模拟',
  none: '待拉取'
};

export default {
  components: {
    MapView, RiskStrip, ETAIndicator, BriefingPanel, CarbonCalc,
    DecisionSandbox, TaskDispatchPanel, AgentPanel, OutreachPanel,
    PlatformConfigurator, DecisionLogTimeline, PlaceInput, CopilotPanel
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
      originId: 'NN',
      destinationId: 'HN',
      draftOriginId: 'NN',
      draftDestinationId: 'HN',
      // 输入框显示名（PlaceInput v-model:name 双绑）：与草稿节点 id 同步更新
      originName: '南宁',
      destinationName: '河内',
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
      // 下拉框的选择：只跟“后端当前选中的数据源”走，不被实际取数结果反向覆盖
      weatherSourceId: 'contest-observation',
      // 屏幕上这批数字实际来自哪个源（可能是上一个源留下的缓存）
      weatherServed: 'none',
      // 这批数据是不是本次真实回源拿到的（false = 节流窗口内的缓存）
      weatherFresh: false,
      weatherSourceOptions: [],
      sourceSwitching: false,
      weatherLoading: false,
      pickMode: false,
      pickOriginNode: null,
      pickDestinationNode: null,
      agentNotice: null,
      hazardTimeline: [],
      // 灾害时间线搜索关键字：命中 标题/描述/时间 任一即保留，空则展示全部
      timelineQuery: '',
      // 候选路线：每次规划后由 /api/routes/candidates 拉回，最多 10 条
      candidates: [],
      // 平陆运河「可坐船出境」推荐位：后端 candidates 接口在公路候选外并列返回的 canalOption
      // （起点中国侧、终点越南侧时非空）。大屏此前只读 candidates、把它丢了。
      canalOption: null,
      // 运河禁航实时状态（canal-block/canal-recover SSE 维护）：null=未收到事件，看 canalOption 快照
      canalBlockLive: null,
      // 坐船走廊几何（/api/canal/corridor 懒拉缓存）与地图高亮开关
      corridorData: null,
      showCorridor: false,
      // 当前主线对应的候选 key（默认 = 后端返回的 "recommended"，选中后变为对应 key）
      // 预先写 'recommended' 是为了让候选到达后 drawAlternates 能直接跳过它，避免与主线红绿分层重叠
      selectedCandidateKey: 'recommended',
      // === 演示场景（11幕）===
      outreachStatus: { targets: [], total: 0, confirmed: 0, pending: 0 },
      // 运输任务（公司派单）：司机端据此切物流任务模式，大屏派单面板显示接单状态
      // 运输任务服务端态：初值必须是 null（= 还没拿到服务端态），不能给个
      // { task: null, status: 'NONE' } 占位——那样子组件的「服务端态优先」判据会立刻生效，
      // 把面板自己 mount 时拉到的兜底快照顶掉，首屏反而短暂显示成「未派单」
      taskStatus: null,
      // 公司派单面板递来的订单信息（车牌/司机/货物/重量/起终点）：
      // 「AI 决策分析」面板的开始导航模板派单时与选定路线合并后下发
      dispatchForm: null,
      // 平陆运河禁航告警卡（canal-block SSE 事件驱动）：点名受影响在途对象并支持一键换线
      canalAlert: null,
      // 全域受阻建议（后端 CorridorAdvisoryService 下发）：公路走廊全部封闭且运河禁航时给返回/等待建议
      corridorAdvisory: null,
      // 左侧面板缩放系数（1=默认360px）：拖动右边缘手柄整体等比放大，字号随之变大
      panelZoom: 1,
      panelDragging: false,
      // 轻界面开关当前状态（入口已按机器自动判定，这里只是给按钮做回显）
      perfLite: isLite(),
      // 是否由帧率自检自动开的（区别于手点）：文案上让评委知道这不是他误触
      perfAuto: isAutoLite()
    };
  },
  computed: {
    /** 选点面板提示：把「现在该点哪里 / 已经选到哪一步」说清楚 */
    pickHintText() {
      if (!this.pickMode) return '选择起终点后点击「确认选择并规划」';
      if (!this.pickOriginNode) return '点击地图任意位置选起点';
      if (!this.pickDestinationNode) return '已选起点「' + this.originName + '」，再点一处选终点';
      return '已选「' + this.originName + ' → ' + this.destinationName + '」，继续点击可换终点并重规';
    },
    /** 大屏不在途（规划态），动作固定为「暂缓发车」；进度归司机端算 */
    corridorView() {
      return advisoryView(this.corridorAdvisory, -1);
    },
    corridorShort() {
      return (this.corridorView && ACTION_SHORT[this.corridorView.action]) || '';
    },
    /** 运河当前是否禁航：SSE 实时状态优先，其次用后端随 canalOption 下发的静态快照 */
    canalBlockedNow() {
      if (this.canalBlockLive) return this.canalBlockLive.blocked;
      return !!(this.canalOption && this.canalOption.canalBlocked);
    },
    canalBlockReasonNow() {
      if (this.canalBlockLive && this.canalBlockLive.blocked && this.canalBlockLive.reason) return this.canalBlockLive.reason;
      return (this.canalOption && this.canalOption.canalBlockedReason) || '平陆运河触发通航安全红线';
    },
    // 面板用 zoom 整体等比放大：宽度与字号一同变化。
    // 只改 width 的话卡片内字号仍写死 px，会变成"更宽但字没大"的空旷版式
    panelStyle() {
      if (ZOOM_SUPPORTED) {
        return { zoom: this.panelZoom };
      }
      return { width: Math.round(PANEL_BASE_WIDTH * this.panelZoom) + 'px', flex: '0 0 auto' };
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
      return WEATHER_SOURCE_SHORT[this.weatherSourceId] || '多数据源';
    },
    /**
     * 下拉框实际渲染的清单。
     * 清单原本只在 mounted 里拉一次（/api/weather/source），后端比页面起得晚、
     * 或那一发超时/断网，weatherSourceOptions 就永远是空数组 —— <select> 没有任何
     * <option> 可显示，整个框变成空白（看着像“数据源没了”），而其它卡片都有 15 秒轮询自愈。
     * 这里在清单为空时兜一个“当前源”单选项，至少把正在用的源显示出来，
     * 配合下方轮询里的自动补拉恢复成完整清单。
     */
    sourceSelectOptions() {
      if (this.weatherSourceOptions && this.weatherSourceOptions.length) return this.weatherSourceOptions;
      const id = this.weatherSourceId || 'contest-observation';
      return [{ id, label: this.sourceShort(id) + '（清单加载中…）', configured: true, active: true }];
    },
    /** 展示中数据的真实来源（与下拉框选了谁是两件事） */
    weatherServedLabel() {
      return WEATHER_SOURCE_SHORT[this.weatherServed] || this.weatherServed;
    },
    /** 选中的源与实际取数的源不一致（典型场景：刚切完源、还在节流窗口里） */
    weatherSourceMismatch() {
      return this.weatherServed !== 'none' && this.weatherServed !== this.weatherSourceId;
    },
    /** 一行说清“这些数字到底从哪来”，不拿上一个源的数据冒充新选的源 */
    weatherSourceNote() {
      const selected = WEATHER_SOURCE_SHORT[this.weatherSourceId] || this.weatherSourceId;
      if (!this.weatherServed || this.weatherServed === 'none') {
        return '尚未取到数据，点下方按钮拉取';
      }
      const served = this.weatherServedLabel;
      if (this.weatherSourceMismatch) {
        return `已选「${selected}」，当前显示的是「${served}」的缓存，下一轮回源改用「${selected}」`;
      }
      return this.weatherFresh
        ? `数据来自「${served}」本次回源`
        : `数据来自「${served}」缓存（60 秒节流窗口内不重复请求上游）`;
    },
    weatherSourceText() {
      const m = {
        live: '实时', cached: '缓存', stale: '旧源缓存',
        fallback: '模拟降级', offline: '离线', unknown: '未拉取'
      };
      return m[this.weatherSource] || '未拉取';
    },
    weatherSourceClass() {
      const m = {
        live: 'ok', cached: 'ok', stale: 'warn',
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
    },
    // 按关键字过滤灾害时间线（大小写不敏感）；空查询返回全量，
    // 列表本身限高滚动，不再把面板越堆越长
    filteredTimeline() {
      const q = (this.timelineQuery || '').trim().toLowerCase();
      const list = this.hazardTimeline || [];
      if (!q) return list;
      return list.filter(it =>
        (it.title && it.title.toLowerCase().includes(q)) ||
        (it.desc && it.desc.toLowerCase().includes(q)) ||
        (it.time && String(it.time).toLowerCase().includes(q))
      );
    }
  },
  methods: {
    fmtKm(nodeIds) {
      return (nodeIds || []).length;
    },
    /**
     * 一键切轻界面：去掉 backdrop-filter 与逐帧重绘的装饰动画。
     * 开关变化由 perfMode 广播，MapView 监听后重建路线特效层，这里只负责按钮回显。
     */
    togglePerfLite() {
      this.perfLite = toggleLite();
      this.perfAuto = false;
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
        this.applyWeatherSourceState(r.data, pts.length);
      } catch (e) {
        console.error(e);
        this.weatherSource = 'offline';
      } finally {
        this.weatherLoading = false;
      }
    },
    /**
     * 把一份气象响应翻译成界面状态。后端在这里有两个不同的概念，必须分开回显：
     * selectedSource = 当前选中的数据源（下拉框的唯一真相）；
     * servedSource   = 屏幕上这批数字实际来自哪个源（可能是上一个源留下的缓存）。
     * 早先直接用后者写下拉框，于是“切到 CRA40”会被上一轮缓存里的 Open-Meteo 顶回去，
     * 看起来像“拉完官方数据马上又自动切回实时气象”，现在只按 selectedSource 回显。
     */
    applyWeatherSourceState(payload, pointCount) {
      const selected = (payload && (payload.selectedSource || payload.current)) || '';
      if (selected) this.weatherSourceId = selected;
      const served = (payload && payload.servedSource) || 'none';
      this.weatherServed = served;
      this.weatherFresh = !!(payload && payload.servedFresh);
      const liveName = this.isLiveSource(served);
      if (served === 'simulated') this.weatherSource = 'fallback';
      else if (liveName && this.weatherFresh) this.weatherSource = 'live';
      else if (liveName && served === this.weatherSourceId) this.weatherSource = 'cached';
      else if (liveName) this.weatherSource = 'stale';
      else this.weatherSource = pointCount > 0 ? 'fallback' : 'offline';
    },
    /** 数据源 id → 界面简称（说明文案与日志共用） */
    sourceShort(id) {
      return WEATHER_SOURCE_SHORT[id] || id || '未定';
    },
    /** 加载可选气象数据源清单（CRA40 / Open-Meteo / 离线模拟） */
    async loadWeatherSource(retryLeft = 3) {
      try {
        const r = await axios.get('/api/weather/source', { timeout: 8000 });
        this.weatherSourceOptions = r.data.options || [];
        // 下拉框以“后端选中的源”为准（另一个页面/评委切了也能跟上）
        if (r.data.current) this.weatherSourceId = r.data.current;
      } catch (e) {
        console.error('load weather source failed', e);
      }
      // 大屏往往在后端启动完成之前就打开了，mounted 那一发注定空手。
      // 只靠 15 秒心跳补拉的话，评委对着空下拉框能等小半分钟，这里先按 3 秒节奏快补三次。
      if (!this.weatherSourceOptions.length && retryLeft > 0) {
        if (this._srcRetryTimer) clearTimeout(this._srcRetryTimer);
        this._srcRetryTimer = setTimeout(() => {
          this._srcRetryTimer = null;
          this.loadWeatherSource(retryLeft - 1);
        }, 3000);
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
        // 节流窗口挡住的场景要当场说清楚，不能让调度员自己猜“是不是没切过来”
        if (this.weatherSourceMismatch) {
          this.pushTimeline('气象数据源待回源',
            `当前数字仍来自「${this.sourceShort(this.weatherServed)}」缓存，下一轮回源改用「${r.data.sourceLabel}」`, 'warn');
          this.scheduleSourceSettleRetry();
        }
      } catch (e) {
        console.error('switch weather source failed', e);
        alert('切换气象数据源失败');
      } finally {
        this.sourceSwitching = false;
      }
    },
    /**
     * 切源后屏幕上还是上一个源的数据（被按源 60 秒节流或跨源最小间隔挡住）时补拉。
     * 等待时长不写死：直接问本机后端「这个源距下次允许回源还要等多久」，多留 2 秒再去，
     * 这样冷启动刚回过源的场景也能在窗口一开就落到新源，不用调度员反复点。最多补两次，不循环骚扰上游。
     */
    async scheduleSourceSettleRetry() {
      if (this._sourceSettleTimer) { clearTimeout(this._sourceSettleTimer); this._sourceSettleTimer = null; }
      this._sourceSettleLeft = 2;
      const step = async () => {
        this._sourceSettleTimer = null;
        if (!this.weatherSourceMismatch) return;
        await this.refreshRealWeather();
        if (!this.weatherSourceMismatch) {
          this.pushTimeline('气象数据源已生效', `已按「${this.sourceShort(this.weatherSourceId)}」取到实况`, 'ok');
          return;
        }
        this._sourceSettleLeft -= 1;
        if (this._sourceSettleLeft <= 0) return;
        this._sourceSettleTimer = setTimeout(step, await this.sourceSettleWaitMs());
      };
      this._sourceSettleTimer = setTimeout(step, await this.sourceSettleWaitMs());
    },
    /** 本源还要等多久才允许回源（只查本机后端统计，不碰上游）；拿不到就按 18 秒兜底，封顶 75 秒 */
    async sourceSettleWaitMs() {
      let ms = 18000;
      try {
        const r = await axios.get('/api/weather/rate-stats', { timeout: 5000 });
        const d = r.data || {};
        const per = (d.sources || {})[this.weatherSourceId] || {};
        const minInterval = d.minIntervalSeconds || 60;
        const remain = per.secondsSinceLastUpstream == null ? 0 : minInterval - per.secondsSinceLastUpstream;
        ms = (remain > 0 ? remain + 2 : 6) * 1000;
      } catch (e) {
        console.warn('rate-stats 不可用，按默认等待时间补拉', e);
      }
      return Math.min(75000, Math.max(6000, ms));
    },
    async loadRealWeather() {
      try {
        const r = await axios.get('/api/weather/real', {
          params: { originId: this.originId, destinationId: this.destinationId }
        });
        const pts = r.data.points || [];
        this.weatherPoints = pts;
        this.applyWeatherSourceState(r.data, pts.length);
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
        this.setCorridorAdvisory(r.data && r.data.advisory);
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
        // 候选接口里附带的全部受阻建议（与 /candidates 同一份判据）
        this.setCorridorAdvisory(r.data && r.data.advisory);
        // 公路候选之外的「可坐船出境」推荐位：一并接住，供坐船卡片与地图走廊高亮使用
        this.canalOption = (r.data && r.data.canalOption) || null;
        if (!this.canalOption) this.showCorridor = false;
        // 默认主线 = 后端标记的 recommended；找不到就第一条
        const rec = list.find(c => c.key === 'recommended');
        const nextKey = (rec || list[0] || {}).key || '';
        if (nextKey) this.selectedCandidateKey = nextKey;
        // 离线兜底场景（demo-route.json）时 candidates 为空——保留 applyRouteResult 已经画好的主线
      } catch (e) {
        console.warn('loadCandidates failed', e);
        this.candidates = [];
        this.canalOption = null;
        this.showCorridor = false;
      }
    },
    /**
     * 全域受阻建议的统一入口（15 秒轮询 / SSE / 候选拉取三处都会带）。
     * 同一局势只弹一次：调度员确认过就不再因轮询反复抢回面板，
     * 但走廊清单或成因变了（签名变化）会重新提醒——灾害范围扩大时必须重新告知。
     */
    setCorridorAdvisory(a) {
      const sig = a && a.code ? a.action + '|' + (a.detail || '') : '';
      this._corridorSig = sig;
      if (sig && sig === this._corridorAck) {
        this.corridorAdvisory = null;
        return;
      }
      const first = !!a && !this.corridorAdvisory;
      this.corridorAdvisory = a || null;
      if (a && first) {
        const v = advisoryView(a, -1);
        this.pushTimeline('全域受阻', (v && v.title) || '全线受阻', 'danger');
      }
    },
    /** 调度员确认「知道了」：记住这轮局势签名，走廊与成因都没变就不再弹 */
    dismissCorridorAdvisory() {
      this._corridorAck = this._corridorSig || '';
      this.corridorAdvisory = null;
    },
    // 切换主线：直接把候选的几何替换为主线数据，无需再次请求后端（候选已含 coords/edgeIds/edgeSpans）
    selectCandidate(key, candidateOverride) {
      const c = candidateOverride || (this.candidates || []).find(x => x.key === key);
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
    /**
     * AgentPanel「开始导航派单」候选点选 → 大屏地图预览。
     * 直接用传上来的候选几何（与派单完全一致），不依赖大屏自身候选列表（两者 cargoType 参数可能不同）。
     * 选到「坐船出境」则展开平陆运河陆水联运走廊。
     */
    onPreviewCandidate(payload) {
      if (!payload) return;
      if (payload.key === 'canal') {
        if (payload.canal && !this.canalOption) this.canalOption = payload.canal;
        if (this.canalOption && !this.showCorridor) this.toggleCanalCorridor();
        return;
      }
      this.selectCandidate(payload.key, payload.candidate);
    },
    // 坐船卡片：点击在地图上高亮/收起「平陆运河陆水联运走廊」。几何懒拉一次后缓存复用。
    toggleCanalCorridor() {
      if (!this.canalOption) return;
      this.showCorridor = !this.showCorridor;
      if (this.showCorridor && !this.corridorData) this.ensureCorridor();
      this.pushTimeline('出行方式', this.showCorridor ? '查看陆水联运（坐船）走廊' : '收起坐船走廊', 'info');
    },
    async ensureCorridor() {
      try {
        const r = await axios.get('/api/canal/corridor', { timeout: 15000 });
        this.corridorData = r.data;
      } catch (e) {
        console.warn('陆水联运走廊拉取失败', e);
      }
    },
    // 确认输入/选点得到的起终点：用户只打字没选候选时兜底在线解析+吸附，
    // 然后提交草稿并触发规划，同时注册关注（风险时该路线会被重算推送）
    async confirmSelection() {
      const o = await this.$refs.originInput.ensure();
      if (!o.ok) { alert(o.msg); return; }
      const d = await this.$refs.destInput.ensure();
      if (!d.ok) { alert(d.msg); return; }
      this.originName = o.name;
      this.destinationName = d.name;
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
          // 全域受阻建议随推送一并下发（RouteAgentService 重算后附上）：先落建议再看路线
          this.setCorridorAdvisory(data.advisory);
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
          const prevTask = (this.taskStatus && this.taskStatus.task) || null;
          this.taskStatus = JSON.parse(ev.data);
          const t = this.taskStatus && this.taskStatus.task;
          if (t) {
            const accepted = t.status === 'ACCEPTED';
            this.pushTimeline(accepted ? '司机接单' : '公司派单',
              `${t.plate} ${t.driverName}承运 ${t.cargoName} ${t.weightT}t，${t.originId} → ${t.destinationId}`,
              accepted ? 'info' : 'warn');
          } else if (prevTask) {
            // 司机退出接单 / 调度撤销：派单面板立刻回到「未派单」，时间线也要留痕，
            // 否则演示时看不出这一刀是即时生效的
            this.pushTimeline('任务撤销', `${prevTask.plate} 已退出接单，司机端退回普通导航模式`, 'info');
          }
          this.refreshDecisionLog();
        } catch (e) {
          console.error('task-assigned parse failed', e);
        }
      });
      this._agentEs.onerror = () => {
        retries++;
        if (retries > 3) {
          // 旧实现在这里 close 后置空就再也不管了：SSE 一断，司机端接单/退单
          // 只能等刷新页面才看得到，正是「两端响应慢」的另一半原因。
          // 改成退避后重建连接，重连成功再补拉一次任务态对齐断流期间漏掉的事件。
          this._agentEs.close();
          this._agentEs = null;
          clearTimeout(this._agentReconnectTimer);
          this._agentReconnectTimer = setTimeout(() => {
            this._agentReconnectTimer = null;
            if (this._unmounted) return;
            this.connectAgent();
          }, 4000);
          return;
        }
        // EventSource 会自动重连，此处仅日志
        console.warn('agent SSE disconnected, will retry');
      };
      // === 平陆运河禁航/恢复：在途水运对象点名反馈（双向切换场景 B）===
      this._agentEs.addEventListener('canal-block', (ev) => {
        try { this.onCanalBlock(JSON.parse(ev.data)); } catch (e) { console.error('canal-block parse failed', e); }
      });
      this._agentEs.addEventListener('canal-recover', (ev) => {
        try { this.onCanalRecover(JSON.parse(ev.data)); } catch (e) { console.error('canal-recover parse failed', e); }
      });
      // 订阅建好立刻对一次任务态：首次挂载靠这一发拿到初始状态，
      // 断线重连则补上断流期间漏掉的派单/接单/退单事件
      this.refreshTask();
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
      // 灾害状态翻转都要触发多智能体重算（版本二），不能只认「有灾害」这一半：
      // ① 出现风险/绕行 → 「灾害触发·熔断重算」；
      // ② 风险解除（本条线不再绕行 rerouted=false、全网无风险 risks=[]、也无 error）
      //    同样必须重算——否则 AI 面板会冻结在旧的绕行/联运推荐上，调度端看不到「已恢复常态」，
      //    这正是「风险解除后六维分析不触发」的成因。用上一次的有效灾害态做跳变判定，
      //    常态下的无风险心跳（从未有过灾害）不会误触发。
      const riskActive = !!(data.error || rerouted || (data.risks && data.risks.length));
      if (riskActive || this._lastRouteRiskActive) {
        if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
      }
      this._lastRouteRiskActive = riskActive;
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
              : '平陆运河通航条件恢复红线之上，陆水联运方案恢复可用',
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
    /**
     * 调度舱写操作落地成功：与沙盘手动注入同一联动路径——刷新风险条、沙盘显示与决策日志，
     * 并记一笔时间线（指令来源是对话舱，日志里要看得见）。
     */
    onCopilotExecuted(payload) {
      const tool = (payload && payload.tool) || '';
      this.refreshRisks();
      if (this.$refs.sandboxRef) this.$refs.sandboxRef.refresh();
      this.refreshDecisionLog();
      this.pushTimeline('调度舱指令执行', `自然语言调度舱落地：${tool}`, 'info');
    },
    onDispatched(status) {
      this.outreachStatus = status;
      this.pushTimeline('任务变更下发', '调度员确认方案，任务变更指令已推送至司机/船东/沿岸百姓', 'warn');
      this.refreshDecisionLog();
    },
    /**
     * 平陆运河禁航（canal-block SSE）：判定是否有在途对象受影响。
     * 已接单且调度选的是联运（routeChoice=canal / 路线名含「联运」）的司机 →
     * 展示一键换线卡片（下发后司机端走既有任务变更编排）；
     * canalWatchers 是后端登记的运河模式在途路线（含普通用户）。
     * 时间线与 onSandboxChange 共用 _lastWaterBlocked 去重，跨窗口触发也不遗漏。
     */
    onCanalBlock(data) {
      // 先记实时禁航态：候选路线里的水运卡据此标红（与是否有在途受影响对象无关）
      this.canalBlockLive = { blocked: true, reason: (data && data.reason) || '' };
      const watchers = (data && data.canalWatchers) || [];
      const t = (this.taskStatus && this.taskStatus.task) || null;
      const driverOnCanal = !!(t && t.status === 'ACCEPTED'
        && (t.routeChoice === 'canal' || (t.routeLabel || '').includes('联运')));
      if (this._lastWaterBlocked !== true) {
        this._lastWaterBlocked = true;
        this.pushTimeline('水运禁航', `${(data && data.reason) || '平陆运河禁航'}，运河模式在途路线 ${watchers.length} 条`
          + (driverOnCanal ? `，司机 ${t.driverName} 正前往平陆运河，请点名换线` : ''), 'danger');
      }
      if (!driverOnCanal && !watchers.length) return;
      this.canalAlert = {
        reason: (data && data.reason) || '平陆运河触发通航安全红线',
        // 事件来源（支流风险联动/调度员调整运河通航条件等）——支流卡联动时大屏能看出是谁推的
        trigger: (data && data.trigger) || '',
        driverOnCanal,
        driverName: t ? `${t.driverName}（${t.plate}）` : '',
        watcherCount: watchers.length,
        dispatched: false,
        dispatching: false
      };
      // 禁航翻转也让 AI 面板旧方案立即失效并重算（与 onSandboxChange 沙盘路径同构）：
      // 支流联动/后端自动触发的 canal-block 不经沙盘，不补这一刀的话面板会一直停在
      // 禁航前的旧推荐，常态模板还会拿过期候选比选派单
      if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
    },
    /** 运河恢复通航：收起换线卡片，时间线去重同上 */
    onCanalRecover(data) {
      // 清除实时禁航态：水运卡回到正常「可坐船」展示
      this.canalBlockLive = { blocked: false, reason: '' };
      if (this._lastWaterBlocked !== false) {
        this._lastWaterBlocked = false;
        this.pushTimeline('恢复通航', (data && data.reason) || '平陆运河通航条件恢复红线之上，陆水联运方案恢复可用', 'ok');
      }
      this.canalAlert = null;
      // 恢复通航同样重算：水运方案重新可用，旧一轮「公路绕行」推荐不该接着展示
      if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
    },
    /**
     * 一键为在途联运司机换线：等价于调度员在 AI 面板确认「公路绕行」并下发任务变更
     * （POST /api/outreach/dispatch，planId=A）；司机端收到 outreach-update 后
     * 自行完成「预览新路线 → 确认接收 → 切公路导航」编排。
     */
    async switchDriverToRoad() {
      if (!this.canalAlert || this.canalAlert.dispatching) return;
      this.canalAlert.dispatching = true;
      try {
        const { data } = await axios.post('/api/outreach/dispatch', {
          planId: 'A',
          planName: '公路绕行（平陆运河禁航换线）'
        });
        this.outreachStatus = data;
        this.canalAlert.dispatched = true;
        this.pushTimeline('调度换线', '平陆运河禁航：已向联运在途司机下发公路方案，待司机确认后生效', 'warn');
        this.refreshDecisionLog();
      } catch (e) {
        console.error('canal reroute dispatch failed', e);
        alert('换线下发失败，请重试');
      } finally {
        if (this.canalAlert) this.canalAlert.dispatching = false;
      }
    },
    /** 派单前出车分析完成：记住订单信息、同步起终点并触发多智能体「派单前·常态」决策分析（版本一） */
    onPreDispatchAnalysis(payload) {
      this.dispatchForm = payload || null;
      if (payload && payload.originId) this.originId = payload.originId;
      if (payload && payload.destinationId) this.destinationId = payload.destinationId;
      this.pushTimeline('派单前 AI 决策分析', '调度员发起出车前分析，三智能体生成常态方案对比与多路线候选，选定一条后派单给司机', 'info');
      this.$nextTick(() => {
        if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.runAnalysis(false, 'normal');
      });
    },
    /** 开始导航模板派单：订单信息（公司派单面板）+ 选定路线快照（AI 决策分析面板）合并下发司机端 */
    async onDispatchTask(payload) {
      const route = (payload && payload.route) || null;
      // done：AI 面板按钮的完成回调（恢复可点/亮「已派单」），无论成败必须调，否则按钮卡在「派单中…」
      const done = payload && typeof payload.done === 'function' ? payload.done : () => {};
      if (!route) { done(false); return; }
      // 全域受阻时不得直接把线派下去：候选里那条「向前」路线是降级放行换来的，
      // 默认动作是暂缓发车。这里加一道人工确认，防的是“灾害一来、调度员顺手一点就派单”。
      if (this.corridorAdvisory && this._dispatchHitsBlocked(route)) {
        const v = this.corridorView;
        const ok = window.confirm(`${v.title}\n\n${v.text}\n\n`
          + `仍要按「${route.routeLabel || route.routeChoice}」派单吗？\n（确定 = 明知全线受阻仍派单，取消 = 回到候选列表）`);
        if (!ok) {
          done(false);
          this.pushTimeline('派单已中止', v.title, 'danger');
          return;
        }
        this.pushTimeline('全域受阻仍派单',
          `调度员确认按「${route.routeLabel || route.routeChoice}」派单，风险自负`, 'danger');
      }
      const body = { ...(this.dispatchForm || {}), ...route };
      // 空值剔掉，让后端剧本默认值生效
      Object.keys(body).forEach(k => {
        if (body[k] === undefined || body[k] === null || body[k] === '') delete body[k];
      });
      try {
        const { data } = await axios.post('/api/task/dispatch', body, { timeout: 30000 });
        this.refreshTask(data);
        const t = data && data.task;
        this.pushTimeline('公司派单',
          t ? `${t.plate} ${t.driverName}承运 ${t.cargoName} ${t.weightT}t，路线「${t.routeLabel || t.routeChoice}」已下发，等待司机接单`
            : '派单指令已下发',
          'warn');
        done(true);
      } catch (e) {
        console.error(e);
        done(false);
        alert('派单失败，请重试');
      }
    },
    /** 本次派单是否踩在全域受阻的口径上：选了降级放行的候选，或运河禁航下仍派坐船方案 */
    _dispatchHitsBlocked(route) {
      if (route.routeChoice === 'canal') return true;
      const c = (this.candidates || []).find(x => x.key === route.routeChoice);
      return !!(c && c.notRecommended);
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
    /**
     * 任务态对账（SSE 之外的兜底）：只在真的变了时才写入 taskStatus。
     * EventSource 有「连接看着还活着、但已经不吐事件」的静默死法（切后台、代理断流），
     * 没有这层兜底，司机端退单后大屏可能长时间停在旧状态。
     */
    syncTaskState() {
      axios.get('/api/task/current').then(({ data }) => {
        if (!data) return;
        const sig = (t) => (t ? `${t.taskId}|${t.status}` : 'none');
        const prev = (this.taskStatus && this.taskStatus.task) || null;
        if (sig(prev) !== sig(data.task || null)) this.taskStatus = data;
      }).catch(() => { /* 静默，下一轮再对 */ });
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
      // 吸附距离护栏：路网节点只沿中越走廊密集，点到走廊外（老挝/泰国/海上）
      // 会默默吸到几百 km 外的节点，起终点看起来“乱跑”且算路必然失败——当场说清楚
      if (hit.distKm > PICK_SNAP_MAX_KM) {
        alert(`该位置距最近路网节点约 ${Math.round(hit.distKm)} km，超出路网覆盖范围。\n请在广西—越北道路走廊附近点击。`);
        return;
      }
      const n = hit.node;
      const label = await this.pickLabel(n, lat, lon);
      if (!this.pickOriginNode) {
        this.pickOriginNode = n;
        this.originId = n.id;
        this.draftOriginId = n.id;
        this.originName = label;
        return;
      }
      this.pickDestinationNode = n;
      this.destinationId = n.id;
      this.draftDestinationId = n.id;
      this.destinationName = label;
      if (this.pickOriginNode.id === n.id) {
        alert('起终点不能是同一节点，请重新点击终点');
        this.pickDestinationNode = null;
        return;
      }
      await this.planCrossBorder();
    },
    /**
     * 选点显示名。8.6 万路网节点里只有 32 个带名字，直接用 n.name || n.id 会让
     * 输入框变成一串节点 ID（3266817885），调度员看不出选中了哪儿、还以为没生效。
     * 因此：节点有名用节点名 → 否则用本地地名库最近地名（“XX附近”）→ 再否则用坐标。
     */
    async pickLabel(n, lat, lon) {
      if (n.name && n.name.trim()) return n.name.trim();
      let best = null;
      let bestKm = Infinity;
      try {
        for (const p of await fetchPlaceList()) {
          if (p.lat == null || p.lng == null) continue;
          const d = this.haversineKm({ latitude: lat, longitude: lon }, { latitude: p.lat, longitude: p.lng });
          if (d < bestKm) { bestKm = d; best = p; }
        }
      } catch (e) { /* 地名库拉不到（离线）就退化成坐标显示 */ }
      if (best && bestKm <= 60) return `${best.name}附近`;
      return `${lat.toFixed(2)}, ${lon.toFixed(2)}`;
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
      this.corridorAdvisory = null;
      this._corridorSig = '';
      this._corridorAck = '';
      this.pickOriginNode = null;
      this.pickDestinationNode = null;
    },
    /**
     * 沙盘「快速模拟灾害注入」回调：注入/撤销已在子组件完成，
     * 此处只做跨面板副作用：AI 面板旧方案失效重算、刷新风险列表、重规路线、写时间线。
     */
    async onQuickInject(payload) {
      if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.notifyRiskChanged();
      await this.refreshRisks();
      if (payload && payload.clearAll) {
        this.pushTimeline('清除全部风险', '已清空所有灾害注入，路线恢复常态评估', 'ok');
      } else if (payload) {
        this.pushTimeline(
          payload.active ? '注入灾害场景' : '清除灾害场景',
          `${payload.name} ${payload.active ? '已注入，正在触发路线重算' : '已清除'}`,
          payload.active ? 'warn' : 'ok'
        );
      }
      if (this.routeResult && this.routeResult.route) {
        await this.planCrossBorder();
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
    // ---- AI + 实时气象灾害预测：大模型预测 → 注入风险 → agent 实时重算 → SSE 推新路线 ----
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
    /** 判断数据源是否为真实联网源（CRA40 / Open-Meteo） */
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
          if (this.$refs.agentPanelRef) this.$refs.agentPanelRef.runAnalysis(false, 'normal');
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
    this.loadCustoms();
    // 先拿“选了哪个源”，再拉数据：反过来会让拉数据回包临时顶掉下拉框
    this.loadWeatherSource();
    this.loadRealWeather();
    // Agent 实时守护：订阅 SSE，灾害发生后立即收到新路线（毫秒级），轮询仅作兜底
    this.connectAgent();
    this._riskTimer = setInterval(() => {
      this.refreshRisks();
      // 气象源清单只在 mounted 拉过一次，那一发失败就再没人管（下拉框永久空白）；
      // 空了就顺着这个 15 秒心跳补拉，拉到自动停（不再发请求）。
      if (!this.weatherSourceOptions.length) this.loadWeatherSource();
      if (this.routeResult && this.routeResult.route) {
        this.planCrossBorder();
      }
    }, 15000);
    // 司机端"开始导航"信号：车一动就自动触发一次 AI 六维分析（常态方案对比），
    // 由调度员人工确认路线后再下发，分析不再只在熔断后才做
    this._tripTimer = setInterval(() => { this.pollTripSignal(); }, 2500);
    // 两端状态对账：SSE 是毫秒级主通道，这发 10 秒的 REST 只防 SSE 静默断流
    this._taskSyncTimer = setInterval(() => { this.syncTaskState(); }, 10000);
    // 帧率自检是在首屏画完后异步跑的，它判定要降级时得把按钮文案带起来
    this._offLiteChange = onLiteChange(() => {
      this.perfLite = isLite();
      this.perfAuto = isAutoLite();
    });
  },
  beforeUnmount() {
    this._unmounted = true;
    if (this._agentReconnectTimer) { clearTimeout(this._agentReconnectTimer); this._agentReconnectTimer = null; }
    if (this._taskSyncTimer) clearInterval(this._taskSyncTimer);
    if (this._offLiteChange) { this._offLiteChange(); this._offLiteChange = null; }
    if (this._sourceSettleTimer) { clearTimeout(this._sourceSettleTimer); this._sourceSettleTimer = null; }
    if (this._srcRetryTimer) { clearTimeout(this._srcRetryTimer); this._srcRetryTimer = null; }
    if (this._riskTimer) clearInterval(this._riskTimer);
    if (this._tripTimer) clearInterval(this._tripTimer);
    if (this._agentEs) this._agentEs.close();
    if (this._agentNoticeTimer) clearTimeout(this._agentNoticeTimer);
  }
};
</script>

<style scoped>
/* 灾害时间线：搜索框 + 限高滚动窗口（此前 timeline-* 类没有任何样式，记录一多就把面板撑得很长）*/
.timeline-search {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 8px;
  padding: 6px 10px;
  font-size: 12px;
  border: 1px solid rgba(30, 50, 90, 0.18);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--text-h, #1c2a44);
}
.timeline-search:focus {
  outline: none;
  border-color: #4f6df5;
}
.timeline-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow-y: auto;
  padding-right: 4px;
}
.timeline-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed rgba(30, 50, 90, 0.08);
}
.timeline-item:last-child {
  border-bottom: none;
}
.timeline-time {
  font-size: 10px;
  color: #64748f;
  font-family: var(--mono, monospace);
  min-width: 52px;
  padding-top: 2px;
}
.timeline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
  background: #64748f;
}
.timeline-dot.info { background: #4f6df5; }
.timeline-dot.ok { background: #22a35a; }
.timeline-dot.warn { background: #d97706; }
.timeline-dot.danger { background: #e11d48; }
.timeline-body {
  flex: 1;
  min-width: 0;
}
.timeline-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-h, #1c2a44);
}
.timeline-desc {
  font-size: 11px;
  color: #64748f;
  line-height: 1.5;
  word-break: break-word;
}
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
/* 项目全称 24 个字，拼上「 · 调度大屏」在 1366 宽的屏上刚好一行；
   这里强制不换行，否则标题折行会把头部顶高，而 .main 的高度是 calc(100vh - 65px) 写死的 */
.app-header h1 { margin:0; font-size:17px; font-weight:700; letter-spacing: .5px; white-space:nowrap;
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
.btn:not(.primary):not(.outline):not(.muted):not(.tiny):not(.pick-on):not(.driver-entry) { background:#eef1f8; border:1px solid #dbe3f2; color:#33415c; }
.btn:hover:not(.primary):not(.outline):not(.muted):not(.tiny):not(.pick-on):not(.driver-entry) { background:#e4eaf6; }
.btn.small { padding:6px 10px; font-size:11px; }
.btn.muted { background:#eef1f8; color:#8b99b5; }
.btn.outline { background:transparent; color:#0e9bb0; border:1px solid rgba(14,155,176,0.4); }
.btn.outline:hover { border-color:#0e9bb0; background:rgba(14,155,176,0.08); }
.btn.outline-light { background:rgba(255,255,255,0.25); color:#fff; border:1px solid rgba(255,255,255,.5); }
/* 司机端入口：头部其余按钮都是冷蓝灰，这里用品牌棱镜里的暖端（橙→品红）做整屏唯一暖色块，
   再叠一呼吸发光。高度控制在 ~29px：.main 的 calc(100vh - 65px) 是写死的，
   按钮把头部顶高就会在底部多出/缺一段空隙 */
.btn.driver-entry {
  display:inline-flex; align-items:center; gap:5px;
  padding:6px 12px; font-size:12px; font-weight:700; letter-spacing:.3px;
  border-radius:999px; color:#fff;
  background:linear-gradient(135deg, #ff7a00, #ff2d55);
  border:1px solid rgba(255,255,255,.55);
  box-shadow:0 0 0 1px rgba(255,122,0,.35), 0 4px 14px rgba(255,45,85,.32);
  animation:driver-entry-pulse 2.4s ease-in-out infinite;
}
.btn.driver-entry:hover {
  transform:translateY(-1px) scale(1.03);
  box-shadow:0 0 0 1px rgba(255,122,0,.55), 0 7px 20px rgba(255,45,85,.45);
}
/* 与 .btn:hover 同特异度且写在后面，按下时的缩放手感才能生效 */
.btn.driver-entry:active { transform:scale(.97); }
.de-ico { width:15px; height:15px; fill:none; stroke:#fff; stroke-width:1.7; stroke-linecap:round; stroke-linejoin:round; }
.de-arrow { font-size:11px; opacity:.9; }
@keyframes driver-entry-pulse {
  0%, 100% { box-shadow:0 0 0 1px rgba(255,122,0,.30), 0 4px 12px rgba(255,45,85,.28); }
  50%      { box-shadow:0 0 0 4px rgba(255,122,0,.16), 0 5px 18px rgba(255,45,85,.42); }
}
.btn.pick-on { background:linear-gradient(135deg, #16a34a, #34d399); box-shadow:0 0 18px rgba(22,163,74,0.35); animation:pulse-green 2s infinite; }
@keyframes pulse-green { 0%,100%{box-shadow:0 0 8px rgba(22,163,74,0.18)} 50%{box-shadow:0 0 22px rgba(22,163,74,0.45)} }

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

/* 候选路线列表 */
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
    /* 全域受阻卡：区域性灾害同时盖住多条走廊时的返回/等待结论，比普通告警卡更重 */
.corridor-advisory { border-color:rgba(225,29,72,0.45); background:rgba(225,29,72,0.06); }
.corridor-advisory h3 { color:#be123c; }
.corridor-advice { font-size:13px; line-height:1.7; margin-top:4px; }
.corridor-basis { font-size:11px; line-height:1.6; margin-top:4px; }
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

/* 通关时效并入决策沙盘卡片内的子块：不再自带 card 外框，用顶部虚线与上方沙盘内容分隔 */
.sandbox-sub { margin-top:16px; padding-top:14px; border-top:1px dashed rgba(30,50,90,0.15); }

.kb-input { flex:1; padding:8px 10px; background:#f4f6fc; border:1px solid #dbe3f2; border-radius:8px; color:#33415c; font-size:13px; outline:none; }
.kb-input:focus { border-color:#4f6df5; box-shadow:0 0 0 3px rgba(79,109,245,0.12); }
.kb-list { margin-top:8px; }
.kb-item { border-bottom:1px solid #eef1f8; padding:6px 0; }
.kb-title { font-size:12px; font-weight:600; color:#0e9bb0; }
.kb-cat { font-size:10px; font-weight:400; background:#eef1f8; color:#8b99b5; padding:1px 8px; border-radius:8px; margin-left:4px; }
.kb-content { font-size:11px; color:#64748f; line-height:1.5; margin-top:3px; }

.weather-grid { display:grid; grid-template-columns:1fr 1fr; gap:6px; margin-top:8px; }
.source-switch-row {
  margin-bottom: 4px;
}
/* 数据来源说明：比 .muted 再小一号，不把数据格子挤下去 */
.source-note {
  font-size: 11px;
  line-height: 1.5;
  margin: 0 0 8px;
}
.source-note.warn {
  color: #b45309;
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
.canal-trigger { color:#4f6df5; font-size:11px; margin-left:4px; }
/* 禁航中的水运推荐卡：整卡标红 + 红色徽章 + 情况说明，不再呈现「可坐船」推荐态 */
.canal-item.canal-blocked { border-color: rgba(225,29,72,.55); background: linear-gradient(135deg,#fff1f2 0%,#ffe4e6 100%); opacity:.95; }
.candidate-badge.blocked { background:#e11d48; color:#fff; }
.canal-block-note { margin-top:6px; font-size:12px; line-height:1.6; color:#e11d48; font-weight:600; }
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