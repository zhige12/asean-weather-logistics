<template>
  <section class="card amri-panel">
    <h3>
      气象风险模型 · {{ spec.name || 'AMRI' }}
      <span class="route-state" :class="stateClass">{{ stateText }}</span>
    </h3>
    <div class="muted amri-def">{{ spec.fullName }}</div>
    <div class="amri-formula" :title="spec.membership">{{ spec.formula }}</div>

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
          <template v-if="liveMark"> 圆点为当前走廊最不利点实况值。</template>
        </div>
      </div>
      <div v-else class="muted">标定曲线尚在加载…</div>
    </div>

    <!-- ② 实时工作点：全走廊最不利点的 AMRI 指数 + 逐灾种贡献条 -->
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
            </div>
            <div class="muted">全走廊 {{ live.count }} 个沿线气象点中最不利 · 数据源 {{ live.servedSourceLabel || live.servedSource }}</div>
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
            <span class="pl-name">{{ p.nodeName || p.nodeId }}</span>
            <span class="pl-bar"><i :style="{ width: p.index + '%', background: sevColor(p.severity) }"></i></span>
            <span class="pl-idx" :style="{ color: sevColor(p.severity) }">{{ p.index }}</span>
          </div>
        </div>
      </template>
    </div>

    <!-- ③ 短临预报：以官方 3h 预报多时效降雨驱动，逐时效推算走廊最不利指数演变 -->
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
            走廊「最不利点」AMRI 指数短临走势：now（实况）→ +3h → +6h。
            降雨由 GOWFS 官方预报逐时效驱动，风 / 能见度 / 气温沿用实况基线。起报 {{ nowcast.forecastRunTime }}。
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

export default {
  name: 'AmriPanel',
  props: {
    originId: { type: String, default: '' },
    destinationId: { type: String, default: '' }
  },
  data() {
    return {
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
    // 当前实况工作点在所选灾种曲线上的位置（取走廊最不利点的该灾种实测值）
    liveMark() {
      if (!this.live.worst || !this.curve.length) return null;
      const c = this.live.worst.contributions && this.live.worst.contributions.find(x => x.id === this.hazard);
      if (!c || c.value == null) return null;
      const xs = this.curve.map(p => p[0]);
      const minX = Math.min(...xs);
      const maxX = Math.max(...xs);
      const plotW = CW - PAD_L - PAD_R;
      const plotH = CH - PAD_T - PAD_B;
      const v = Math.max(minX, Math.min(maxX, c.value));
      const px = PAD_L + (maxX === minX ? 0 : (v - minX) / (maxX - minX) * plotW);
      const py = PAD_T + (1 - (c.membership * this.activeHazard.weight * 100) / 100) * plotH;
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
  },
  watch: {
    originId() { this.loadAll(); },
    destinationId() { this.loadAll(); }
  },
  methods: {
    async loadAll() {
      this.loading = true;
      const od = { originId: this.originId, destinationId: this.destinationId };
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
</style>
