<template>
  <section class="card amri-panel">
    <h3>
      气象风险模型 · {{ spec.name || 'AMRI' }}
      <span class="route-state" :class="stateClass">{{ stateText }}</span>
      <span v-if="live.sandboxSim" class="sim-chip" :title="live.sandboxNote || '沙盘滑杆模拟值叠加中'">沙盘模拟叠加</span>
    </h3>
    <div class="muted amri-def">{{ spec.fullName }}</div>
    <div class="amri-formula" :title="spec.membership">{{ spec.formula }}</div>
    <div v-if="live.sandboxSim && live.sandboxNote" class="sim-note">{{ live.sandboxNote }}；拖动决策沙盘滑杆，下方工作点与曲线圆点实时跟随。</div>

    <div class="amri-tabs">
      <button :class="{ on: tab === 'curve' }" @click="tab = 'curve'">标定曲线</button>
      <button :class="{ on: tab === 'live' }" @click="tab = 'live'">实时工作点</button>
      <button :class="{ on: tab === 'nowcast' }" @click="tab = 'nowcast'">短临预报</button>
      <button class="amri-refresh" :disabled="loading" @click="loadAll">
        {{ loading ? '计算中…' : '刷新' }}
      </button>
    </div>

    <!-- ① 标定曲线：单灾种物理量 → 风险指数的 S 型响应，标出分档线与当前实况工作点 -->
    <div v-show="tab === 'curve'" class="amri-body">
      <div class="hz-tabs">
        <button
          v-for="h in hazards"
          :key="h.id"
          :class="{ on: hazard === h.id }"
          @click="hazard = h.id"
        >{{ h.label }}</button>
      </div>
      <div v-if="curve.length" class="curve-wrap">
        <svg :viewBox="`0 0 ${CW} ${CH}`" class="curve-svg" preserveAspectRatio="none">
          <!-- 分档参考线（LOW/MEDIUM/HIGH/CRITICAL 落在纵轴风险指数上） -->
          <line v-for="b in bandLines" :key="'bl' + b.v"
                :x1="PAD_L" :x2="CW - PAD_R" :y1="b.y" :y2="b.y"
                class="band-line" :stroke="b.color" />
          <text v-for="b in bandLines" :key="'bt' + b.v"
                :x="CW - PAD_R - 2" :y="b.y - 2" class="band-txt" :fill="b.color" text-anchor="end">{{ b.name }}</text>
          <!-- 曲线 -->
          <polyline :points="curvePts" class="curve-line" />
          <!-- 算路侧运营动作阈值竖线：让「分数落在哪档」与「业务动不动作」在同一把横轴上直接对读，
               否则会出现「曲线已饱和、99.8 CRITICAL，而业务侧尚未熔断」只能靠口头解释的画面 -->
          <template v-if="opMark">
            <line :x1="opMark.x" :x2="opMark.x" :y1="PAD_T" :y2="CH - PAD_B" class="op-line"
                  :stroke-dasharray="opMark.dir === 'down' ? '2 2' : '4 3'" />
            <text :x="opMark.x" :y="PAD_T + 7" class="op-txt" text-anchor="middle">{{ opMark.dir === 'down' ? '<' : '≥' }}{{ opMark.value }} 动作线</text>
          </template>
          <!-- 当前实况工作点 -->
          <template v-if="liveMark">
            <line :x1="liveMark.px" :x2="liveMark.px" :y1="PAD_T" :y2="CH - PAD_B" class="mark-line" />
            <circle :cx="liveMark.px" :cy="liveMark.py" r="4" class="mark-dot" />
          </template>
          <!-- 坐标轴 -->
          <line :x1="PAD_L" :x2="CW - PAD_R" :y1="CH - PAD_B" :y2="CH - PAD_B" class="axis" />
          <line :x1="PAD_L" :x2="PAD_L" :y1="PAD_T" :y2="CH - PAD_B" class="axis" />
          <text :x="PAD_L" :y="CH - 6" class="tick">{{ curve[0][0] }}</text>
          <text :x="CW - PAD_R" :y="CH - 6" class="tick" text-anchor="end">{{ curve[curve.length - 1][0] }}{{ activeHazard.unit }}</text>
          <text :x="6" :y="PAD_T + 8" class="tick">100</text>
        </svg>
        <div class="muted curve-cap">
          {{ activeHazard.label }}（{{ activeHazard.unit }}）→ 单项风险指数响应曲线；
          半程点 x0={{ activeHazard.x0 }}、陡峭度 k={{ activeHazard.k }}、权重 w={{ activeHazard.weight }}。
          <!-- 圆点只画位不报数时，讲解得切页签才能读出数值，故在本行直接给读数；
               “取值”用中性措词，模拟值另用括号声明，不拿滑杆值冒充实测 -->
          <template v-if="liveReading && !liveReading.missing">
            圆点 = 当前路线最不利点 <b class="cap-val">{{ live.worst.nodeName || live.worst.nodeId }}</b>：
            取值 <b class="cap-val">{{ liveReading.value }} {{ activeHazard.unit }}</b> ·
            隶属度 <b class="cap-val">{{ liveReading.membership }}</b>
            <template v-if="live.worst.sandboxSim">（沙盘模拟值，非实测）</template>。
          </template>
          <template v-else-if="liveReading && liveReading.missing">
            当前路线最不利点该灾种<b>缺测</b>，故曲线不画圆点。
          </template>
          <!-- 完整阈值口径放 title 悬停，行内只留结论，避免说明行胀长 -->
          <template v-if="opMark && opVerdict">
            <span :title="spec.opThresholdNote">动作线 {{ opMark.dir === 'down' ? '<' : '≥' }}{{ opMark.value }}{{ activeHazard.unit }}：圆点在其{{ opVerdict.side }}，<b>{{ opVerdict.triggered ? '已触发' : '未触发' }}</b>该动作。</span>
          </template>
        </div>
      </div>
      <div v-else class="muted">标定曲线尚在加载…</div>
    </div>

    <!-- ② 实时工作点：当前路线最不利点的 AMRI 指数 + 逐灾种贡献条 -->
    <div v-show="tab === 'live'" class="amri-body">
      <div v-if="!live.points || !live.points.length" class="muted">
        {{ live.message || '暂无沿线实时气象点，先在上方「实时气象」拉取后再看。' }}
      </div>
      <template v-else>
        <div class="worst-row">
          <div class="worst-idx" :style="{ color: sevColor(live.worst.severity) }">{{ live.worst.index }}</div>
          <div class="worst-meta">
            <div class="worst-name">{{ live.worst.nodeName || live.worst.nodeId }}
              <span class="sev-badge" :style="{ background: sevColor(live.worst.severity) }">{{ sevText(live.worst.severity) }}</span>
              <span v-if="live.worst.sandboxSim" class="sim-chip">模拟值</span>
            </div>
            <div class="muted">
              <span class="route-tag" :title="live.scoredRouteKey ? '打分点 = 本程选定的这条候选路线沿线气象点' : '未手动选路，按无天气基准最短路沿线点打分'">
                打分路线：{{ scoredRouteName }}
              </span>
              沿线 {{ live.count }} 个气象点中最不利 · 数据源 {{ live.servedSourceLabel || live.servedSource }}
            </div>
          </div>
        </div>
        <div class="contribs">
          <div v-for="c in live.worst.contributions" :key="c.id" class="contrib">
            <span class="c-label">{{ c.label }}</span>
            <span class="c-val">{{ c.value == null ? '缺测' : c.value + ' ' + c.unit }}</span>
            <span class="c-bar"><i :style="{ width: (c.membership * 100) + '%', background: sevColor(membershipSev(c.membership)) }"></i></span>
            <span class="c-imp">贡献 {{ c.impact }}</span>
          </div>
        </div>
        <div class="muted contrib-note">贡献 = 权重 × 隶属度 × 100；隶属度为 S 型「超限程度」（0=正常，1=远超阈值）。</div>
        <div v-if="live.points.length > 1" class="point-list">
          <div class="pl-title">沿线点风险排名（Top {{ Math.min(6, live.points.length) }}）</div>
          <div v-for="(p, i) in live.points.slice(0, 6)" :key="'p' + i" class="pl-row">
            <span class="pl-rank">{{ i + 1 }}</span>
            <span class="pl-name">{{ p.nodeName || p.nodeId }}<i v-if="p.sandboxSim" class="pl-sim" title="沙盘滑杆模拟值">拟</i></span>
            <span class="pl-bar"><i :style="{ width: p.index + '%', background: sevColor(p.severity) }"></i></span>
            <span class="pl-idx" :style="{ color: sevColor(p.severity) }">{{ p.index }}</span>
          </div>
        </div>
      </template>
    </div>

    <!-- ③ 短临预报：以官方 3h 预报多时效降雨驱动，逐时效推算当前路线最不利指数演变 -->
    <div v-show="tab === 'nowcast'" class="amri-body">
      <div v-if="!nowcast.available" class="muted">
        {{ nowcast.message || '短临预报数据尚不可用（预报通道未配置 / 节流 / 熔断 / 上游无数据）。' }}
      </div>
      <template v-else>
        <div v-if="nowcastSvg.pts" class="curve-wrap">
          <svg :viewBox="`0 0 ${CW} ${CH}`" class="curve-svg" preserveAspectRatio="none">
            <line v-for="b in bandLines" :key="'nl' + b.v"
                  :x1="PAD_L" :x2="CW - PAD_R" :y1="b.y" :y2="b.y" class="band-line" :stroke="b.color" />
            <polyline :points="nowcastSvg.pts" class="now-line" />
            <circle v-for="(s, i) in nowcastSvg.dots" :key="'nd' + i"
                    :cx="s.px" :cy="s.py" r="3.5" :fill="s.color" />
            <line :x1="PAD_L" :x2="CW - PAD_R" :y1="CH - PAD_B" :y2="CH - PAD_B" class="axis" />
            <line :x1="PAD_L" :x2="PAD_L" :y1="PAD_T" :y2="CH - PAD_B" class="axis" />
            <text v-for="(s, i) in nowcastSvg.dots" :key="'nt' + i"
                  :x="s.px" :y="CH - 6" class="tick" text-anchor="middle">{{ s.label }}</text>
          </svg>
          <div class="muted curve-cap">
            当前路线「最不利点」AMRI 指数短临走势：now（实况）→ +3h → +6h。
            <!-- 沙盘叠加时风/能见度/气温其实取的是滑杆基线，不是实况；原硬编码文案说“沿用实况基线”
                 与实际行为矛盾（拖风滑杆本面页数字会变），改为直接渲染后端下发的 sandboxNote，单一数据源 -->
            <template v-if="nowcast.sandboxSim && nowcast.sandboxNote">{{ nowcast.sandboxNote }}。</template>
            <template v-else>风 / 能见度 / 气温沿用各点实况基线，降雨由 GOWFS 官方预报逐时效驱动。</template>
            起报 {{ nowcast.forecastRunTime }}。
          </div>
        </div>
        <div class="nc-table">
          <div v-for="s in nowcast.series" :key="s.nodeId" class="nc-point">
            <div class="nc-head">
              <span class="nc-name">{{ s.nodeName }}</span>
              <span class="sev-badge" :style="{ background: sevColor(s.peakSeverity) }">峰 {{ s.peakIndex }} · {{ sevText(s.peakSeverity) }}</span>
            </div>
            <div class="nc-steps">
              <span v-for="(st, i) in s.steps" :key="i" class="nc-step">
                +{{ st.leadHour }}h：{{ st.index }}<em :style="{ color: sevColor(st.severity) }">（雨 {{ st.rainMmH == null ? '—' : st.rainMmH }}mm/h）</em>
              </span>
            </div>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>

<script>
import axios from 'axios';

const CW = 320;   // SVG 宽
const CH = 168;   // SVG 高
const PAD_L = 30;
const PAD_R = 10;
const PAD_T = 12;
const PAD_B = 24;

// SVG 几何常量：模板与 computed 共用同一份，避免两处各写一遍数字而对不齐
const GEO = { CW, CH, PAD_L, PAD_R, PAD_T, PAD_B };

export default {
  name: 'AmriPanel',
  props: {
    originId: { type: String, default: '' },
    destinationId: { type: String, default: '' },
    // 本程在调度大屏/派单面板里选定的那条候选路线 key：
    // 传上来后，沿线打分点 = 那条线经过的点（选走芒街的线就能看见芒街），不再固定基准最短路
    routeKey: { type: String, default: '' },
    // 同一候选的展示名，仅用于界面上说清「现在打的是哪条线」
    routeLabel: { type: String, default: '' }
  },
  data() {
    return {
      // 必须挂到实例上：Vue 3 普通 <script>（非 script setup）的模板只认组件实例，
      // 直接写模块级常量会静默解析成 undefined —— 实测 viewBox 变成 "0 0 undefined undefined"、
      // 坐标轴 / 分档虚线 / 刻度文字全部拿到 NaN 坐标而整体不渲染（不报任何错）。
      ...GEO,
      tab: 'curve',
      hazard: 'rain',
      loading: false,
      spec: {},
      live: { points: [], worst: null },
      nowcast: { available: false, series: [] }
    };
  },
  computed: {
    hazards() {
      return this.spec.hazards || [];
    },
    activeHazard() {
      return this.hazards.find(h => h.id === this.hazard) || this.hazards[0] || { label: '', unit: '', x0: 0, k: 0, weight: 0 };
    },
    curve() {
      const c = (this.spec.curves || {})[this.hazard];
      return Array.isArray(c) ? c : [];
    },
    bands() {
      return this.spec.bands || { CRITICAL: 80, HIGH: 45, MEDIUM: 18, LOW: 0 };
    },
    /** 算路侧运营动作阈值在当前灾种曲线上的竖线位置（后端 opThresholds 下发，前端不写死数值）。
     *  气温等无阈值灾种、或阈值落在采样范围外时不画。 */
    opMark() {
      const t = (this.spec.opThresholds || {})[this.hazard];
      if (t == null || !this.curve.length) return null;
      const xs = this.curve.map(p => p[0]);
      const minX = Math.min(...xs);
      const maxX = Math.max(...xs);
      if (t < minX || t > maxX) return null;
      const plotW = CW - PAD_L - PAD_R;
      return {
        x: PAD_L + (maxX === minX ? 0 : (t - minX) / (maxX - minX) * plotW),
        value: t,
        dir: this.activeHazard.direction
      };
    },
    /** 圆点相对动作线的位置与是否触发该动作。
     *  能见度是反向轴（direction=down，越小越险），触发判据必须跟着翻——
     *  若一律用 value>=threshold，能见度 50m（实际已触发 <100m 动作线）会被说成未触发。 */
    opVerdict() {
      const m = this.opMark;
      const r = this.liveReading;
      if (!m || !r || r.missing || r.value == null) return null;
      const down = m.dir === 'down';
      return {
        side: r.value < m.value ? '左侧' : '右侧',
        triggered: down ? r.value < m.value : r.value >= m.value
      };
    },
    /** 本指数实际按哪条线打分：后端未命中候选快照时会退回基准线，这里据实回显，不拿线名冒充。 */
    scoredRouteName() {
      if (!this.live || !this.live.scoredRouteKey) return '基准最短路';
      return this.routeLabel || this.live.scoredRouteKey;
    },
    /** 请求参数：routeKey 为空时不带（axios 会剔掉 undefined），保持与老调用方一致的基准线口径。 */
    odParams() {
      const p = { originId: this.originId, destinationId: this.destinationId };
      if (this.routeKey) p.routeKey = this.routeKey;
      return p;
    },
    stateClass() {
      const s = this.live && this.live.worst ? this.live.worst.severity : null;
      if (s === 'CRITICAL' || s === 'HIGH') return 'blocked';
      if (s === 'MEDIUM') return 'warn';
      return 'ok';
    },
    stateText() {
      if (!this.live || !this.live.worst) return '待计算';
      return '最不利 ' + this.live.worst.index + ' ' + this.sevText(this.live.worst.severity);
    },
    // 标定曲线折线坐标：横轴物理量[minX,maxX]，纵轴风险指数[0,100]
    curvePts() {
      if (!this.curve.length) return '';
      const xs = this.curve.map(p => p[0]);
      const minX = Math.min(...xs);
      const maxX = Math.max(...xs);
      const plotW = CW - PAD_L - PAD_R;
      const plotH = CH - PAD_T - PAD_B;
      return this.curve
        .map(p => {
          const px = PAD_L + (maxX === minX ? 0 : (p[0] - minX) / (maxX - minX) * plotW);
          const py = PAD_T + (1 - p[1] / 100) * plotH;
          return px.toFixed(1) + ',' + py.toFixed(1);
        })
        .join(' ');
    },
    // 分档参考线（MEDIUM/HIGH/CRITICAL 的纵轴位置）
    bandLines() {
      const plotH = CH - PAD_T - PAD_B;
      const mk = (name, v, color) => ({ name, v, color, y: PAD_T + (1 - v / 100) * plotH });
      return [
        mk('CRITICAL', this.bands.CRITICAL, '#F44336'),
        mk('HIGH', this.bands.HIGH, '#FF9800'),
        mk('MEDIUM', this.bands.MEDIUM, '#FFC107')
      ];
    },
    /** 标定曲线页签的读数：最不利点在所选灾种上的取值与隶属度。
     *  有最不利点但该灾种缺测时只回 {missing:true}，让说明行改口、不硬印 null。 */
    liveReading() {
      if (!this.live.worst || !this.curve.length) return null;
      const c = this.live.worst.contributions && this.live.worst.contributions.find(x => x.id === this.hazard);
      if (!c || c.value == null) return { missing: true };
      return { missing: false, value: c.value, membership: c.membership };
    },
    // 当前实况工作点在所选灾种曲线上的位置（与说明行共用 liveReading，单一数据源）
    liveMark() {
      const r = this.liveReading;
      if (!r || r.missing) return null;
      const xs = this.curve.map(p => p[0]);
      const minX = Math.min(...xs);
      const maxX = Math.max(...xs);
      const plotW = CW - PAD_L - PAD_R;
      const plotH = CH - PAD_T - PAD_B;
      const v = Math.max(minX, Math.min(maxX, r.value));
      const px = PAD_L + (maxX === minX ? 0 : (v - minX) / (maxX - minX) * plotW);
      const py = PAD_T + (1 - (r.membership * this.activeHazard.weight * 100) / 100) * plotH;
      return { px, py };
    },
    // 短临折线：now(实况最不利) + 各时效走廊峰值
    nowcastSvg() {
      if (!this.nowcast.available) return {};
      const peak = this.nowcast.corridorPeak || [];
      if (!peak.length) return {};
      const series = [];
      if (this.live.worst) series.push({ label: 'now', index: this.live.worst.index });
      peak.forEach(p => series.push({ label: '+' + p.leadHour + 'h', index: p.index }));
      const plotW = CW - PAD_L - PAD_R;
      const plotH = CH - PAD_T - PAD_B;
      const n = series.length;
      const dots = series.map((s, i) => {
        const px = n === 1 ? PAD_L + plotW / 2 : PAD_L + i / (n - 1) * plotW;
        const py = PAD_T + (1 - Math.max(0, Math.min(100, s.index)) / 100) * plotH;
        return { px, py, label: s.label, color: this.sevColor(this.sevOfIndex(s.index)) };
      });
      return { pts: dots.map(d => d.px.toFixed(1) + ',' + d.py.toFixed(1)).join(' '), dots };
    }
  },
  mounted() {
    this.loadAll();
    // 沙盘滑杆/灾害卡/实况刷新都会广播 amri-refresh：工作点跟着动，面板才不是空壳
    // force（用户动作）立刻重拉；15 秒心跳类的非 force 走 8 秒节流自控频率
    this._onAmriEvent = (e) => this.loadLive(true, !!(e && e.detail && e.detail.force));
    window.addEventListener('amri-refresh', this._onAmriEvent);
  },
  beforeUnmount() {
    if (this._onAmriEvent) window.removeEventListener('amri-refresh', this._onAmriEvent);
  },
  watch: {
    originId() { this.loadAll(); },
    destinationId() { this.loadAll(); },
    // 换一条候选线（如从走友谊关改成走芒街）就是换一批打分点，必须重拉
    routeKey() { this.loadAll(); }
  },
  methods: {
    async loadAll() {
      this.loading = true;
      this._lastLiveAt = Date.now();
      const od = this.odParams;
      try {
        const [m, l, n] = await Promise.all([
          axios.get('/api/risk/model'),
          axios.get('/api/risk/model/live', { params: od }),
          axios.get('/api/risk/model/nowcast', { params: od })
        ]);
        this.spec = m.data.model || {};
        this.live = l.data || { points: [] };
        this.nowcast = n.data || { available: false };
      } catch (e) {
        this.live = { points: [], message: '模型接口暂不可达：' + (e && e.message ? e.message : e) };
      } finally {
        this.loading = false;
      }
    },
    /**
     * 轻刷新：只重算工作点（含可选的短临序列），模型规格不重拉。
     * 供父页在沙盘变更/灾害卡/实况刷新后调用；默认 8 秒节流防 15 秒心跳叠加拖滑块连环触发，
     * 沙盘提交这类用户动作传 force=true 立刻跟。后台失败静默：保留旧工作点，不打断演示。
     */
    async loadLive(withNowcast = false, force = false) {
      const now = Date.now();
      if (!force && now - (this._lastLiveAt || 0) < 8000) return;
      this._lastLiveAt = now;
      const od = this.odParams;
      try {
        const jobs = [axios.get('/api/risk/model/live', { params: od })];
        if (withNowcast) jobs.push(axios.get('/api/risk/model/nowcast', { params: od }));
        const [l, n] = await Promise.all(jobs);
        this.live = l.data || { points: [] };
        if (withNowcast && n) this.nowcast = n.data || { available: false };
      } catch (e) { /* 后台刷新失败：下一次广播/刷新会再试 */ }
    },
    sevColor(sev) {
      return { CRITICAL: '#F44336', HIGH: '#FF9800', MEDIUM: '#FFC107', LOW: '#4CAF50' }[sev] || '#9aa0a6';
    },
    sevText(sev) {
      return { CRITICAL: '极高风险', HIGH: '高风险', MEDIUM: '中等风险', LOW: '低风险' }[sev] || '—';
    },
    sevOfIndex(idx) {
      if (idx >= this.bands.CRITICAL) return 'CRITICAL';
      if (idx >= this.bands.HIGH) return 'HIGH';
      if (idx >= this.bands.MEDIUM) return 'MEDIUM';
      return 'LOW';
    },
    membershipSev(m) {
      if (m >= 0.8) return 'CRITICAL';
      if (m >= 0.5) return 'HIGH';
      if (m >= 0.25) return 'MEDIUM';
      return 'LOW';
    }
  }
};
</script>

<style scoped>
.amri-panel .amri-def { line-height: 1.5; }
.amri-formula {
  margin: 6px 0 8px; padding: 6px 8px; border-radius: 8px; cursor: help;
  background: rgba(79, 109, 245, 0.08); font-family: ui-monospace, Consolas, monospace;
  font-size: 12px; color: #4f6df5; line-height: 1.5;
}
.amri-tabs { display: flex; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.amri-tabs button {
  border: 1px solid #d0d5dd; background: #fff; border-radius: 8px; padding: 3px 10px;
  font-size: 12px; cursor: pointer;
}
.amri-tabs button.on { background: #4f6df5; color: #fff; border-color: #4f6df5; }
.amri-refresh { margin-left: auto !important; }
.amri-body { min-height: 60px; }
.hz-tabs { display: flex; gap: 5px; margin-bottom: 6px; }
.hz-tabs button {
  border: 1px solid #d0d5dd; background: #fff; border-radius: 6px; padding: 2px 9px; font-size: 12px; cursor: pointer;
}
.hz-tabs button.on { background: #eef2ff; border-color: #4f6df5; color: #4f6df5; }
.curve-wrap { position: relative; }
.curve-svg { width: 100%; height: 168px; display: block; }
.curve-line { fill: none; stroke: #4f6df5; stroke-width: 2; }
.now-line { fill: none; stroke: #ff9800; stroke-width: 2.5; }
.band-line { stroke-width: 1; stroke-dasharray: 4 3; opacity: 0.55; }
.band-txt { font-size: 8px; opacity: 0.8; }
.axis { stroke: #c4c9d4; stroke-width: 1; }
.tick { font-size: 9px; fill: #8a909c; }
.mark-line { stroke: #333; stroke-dasharray: 2 2; stroke-width: 1; opacity: 0.5; }
.mark-dot { fill: #333; stroke: #fff; stroke-width: 1.5; }
.curve-cap { margin-top: 4px; line-height: 1.5; }
/* 读数加对比：说明行整体是 .muted 浅灰，数值/节点名提深并加粗，投影仪后排也能读 */
.curve-cap .cap-val { color: #33415c; font-weight: 700; font-family: ui-monospace, Consolas, monospace; }
/* 运营动作阈值竖线：与横向分档线（指数轴）正交，用差别较大的玫红以免与四色分档线混淆 */
.op-line { stroke: #e0457b; stroke-width: 1.2; opacity: 0.9; }
.op-txt { font-size: 8px; fill: #e0457b; font-weight: 700; }
/* 打分路线标：把「本指数是按哪条线打的」摆在数字旁边，与地图上的主线对得上 */
.route-tag { display: inline-block; margin-right: 6px; padding: 1px 7px; font-size: 11px; border-radius: 8px;
  color: #1e5fa8; background: rgba(79, 109, 245, 0.10); border: 1px solid rgba(79, 109, 245, 0.28); cursor: help; }
.worst-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.worst-idx { font-size: 40px; font-weight: 800; font-family: ui-monospace, Consolas, monospace; line-height: 1; }
.worst-meta { flex: 1; }
.worst-name { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.sev-badge { color: #fff; border-radius: 6px; padding: 1px 8px; font-size: 11px; }
.contribs { display: flex; flex-direction: column; gap: 4px; }
.contrib { display: grid; grid-template-columns: 52px 96px 1fr 56px; align-items: center; gap: 6px; font-size: 12px; }
.c-label { color: #444; }
.c-val { color: #666; font-family: ui-monospace, Consolas, monospace; }
.c-bar { height: 8px; background: #eef0f4; border-radius: 4px; overflow: hidden; }
.c-bar i { display: block; height: 100%; border-radius: 4px; }
.c-imp { text-align: right; color: #666; font-family: ui-monospace, Consolas, monospace; }
.contrib-note { margin: 6px 0; line-height: 1.5; }
.point-list { margin-top: 8px; border-top: 1px dashed #e0e3ea; padding-top: 6px; }
.pl-title { font-size: 12px; color: #666; margin-bottom: 4px; }
.pl-row { display: grid; grid-template-columns: 18px 88px 1fr 34px; align-items: center; gap: 6px; font-size: 12px; margin-bottom: 3px; }
.pl-rank { color: #9aa0a6; }
.pl-bar { height: 8px; background: #eef0f4; border-radius: 4px; overflow: hidden; }
.pl-bar i { display: block; height: 100%; border-radius: 4px; }
.pl-idx { text-align: right; font-weight: 700; font-family: ui-monospace, Consolas, monospace; }
.nc-table { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.nc-point { border: 1px solid #eef0f4; border-radius: 8px; padding: 6px 8px; }
.nc-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 3px; }
.nc-name { font-weight: 600; font-size: 13px; }
.nc-steps { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; color: #555; }
.nc-step em { font-style: normal; margin-left: 2px; }
/* 沙盘模拟叠加标记：紫色小胶囊 + 说明条，把「哪些数字是滑杆推演值」说在白底外 */
.sim-chip {
  background: rgba(124, 58, 237, 0.12); color: #6d28d9; border: 1px solid rgba(124, 58, 237, 0.35);
  border-radius: 8px; padding: 1px 8px; font-size: 11px; font-weight: 600; cursor: help;
}
.sim-note {
  margin: 4px 0 6px; padding: 5px 8px; border-radius: 8px; font-size: 11px; line-height: 1.5;
  background: rgba(124, 58, 237, 0.07); border: 1px solid rgba(124, 58, 237, 0.22); color: #5b21b6;
}
.pl-sim {
  font-style: normal; margin-left: 4px; font-size: 10px; color: #6d28d9;
  border: 1px solid rgba(124, 58, 237, 0.4); border-radius: 4px; padding: 0 3px;
}
</style>
