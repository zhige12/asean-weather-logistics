<template>
  <div class="map-root">
    <div ref="mapEl" class="map-canvas"></div>
    <div v-if="pickHint" class="pick-hint">{{ pickHint }}</div>
    <div class="map-overlay">
      <div class="overlay-card">
        <div class="overlay-toolbar">
          <strong>跨境路网</strong>
          <button class="tile-switch" @click="cycleTileSource">底图:{{ tileSourceName }}</button>
        </div>
        <div class="overlay-row"><span class="dot" style="background:orange"></span> 路网风险边</div>
        <div class="overlay-row"><span class="dot" style="background:#16a34a"></span> 安全路段</div>
        <div class="overlay-row"><span class="dot" style="background:#e53935"></span> 风险路段</div>
        <div class="overlay-row"><span class="dot" style="background:#22b8cf;border:1px dashed #22b8cf"></span> 备选路线（点击切换主线）</div>
        <div class="overlay-row"><span class="dot" style="background:#ea7a2e;border:1px dashed #ea7a2e"></span> 降级兜底（硬熔断降级）</div>
        <div class="overlay-row"><span class="dot" style="background:#888;border:1px dashed #555"></span> 基准原路线（红虚线=已避开的风险段）</div>
        <div class="overlay-row"><span class="hazard-dot">×</span> 灾害点</div>
        <div class="overlay-row"><span class="dot" style="background:#d32f2f;border-radius:50%"></span> 口岸节点</div>
      </div>
    </div>
  </div>
</template>

<script>
import L from 'leaflet';
import 'maplibre-gl/dist/maplibre-gl.css';
import '@maplibre/maplibre-gl-leaflet';
import localBaseStyle from '../data/local-base-style';
import { createRouteFlow } from '../utils/routeFlow.js';
import { reshapeForZoom } from '../utils/pathReshape.js';
import { splitRouteByRisk, riskEdgeSet } from '../utils/routeSegments.js';
// 天地图密钥与在线底图源定义统一到 utils/tileSources.js（大屏与司机端共用，单一事实来源）
import { TIANDITU_TK } from '../utils/tileSources.js';
// Leaflet Canvas 渲染器销毁竞态防护（控制台偶发 Canvas._clear 读空 _ctx 报 reading 'save'），一次性 patch，两端共用
import '../utils/leafletCanvasGuard.js';

export default {
  name: 'MapView',
  props: {
    network: { type: Object, default: null },
    risks: { type: Array, default: () => [] },
    route: { type: Array, default: () => [] },
    // 路径边 ID 序列 + 每条边在 route 中的 [startIdx,endIdx] 区间：
    // 用于把整条路线拆成「风险红 / 安全绿」多段，与司机端着色一致
    routeEdgeIds: { type: Array, default: () => [] },
    routeEdgeSpans: { type: Array, default: () => [] },
    baseline: { type: Array, default: () => [] },
    // 基准路线的边序列 + 边区间：用于把"原路线穿过风险区"的段标红虚线
    baselineEdgeIds: { type: Array, default: () => [] },
    baselineEdgeSpans: { type: Array, default: () => [] },
    hazardPoints: { type: Array, default: () => [] },
    rerouted: { type: Boolean, default: false },
    selectedEdge: { type: String, default: null },
    pickMode: { type: Boolean, default: false },
    pickOrigin: { type: Object, default: null },
    pickDestination: { type: Object, default: null },
    // 候选路线：除主线外的其他可选路径，统一以浅蓝虚线叠加展示
    candidates: { type: Array, default: () => [] },
    // 当前主线对应的候选 key（与主线条目同步，不重复画）
    selectedCandidateKey: { type: String, default: '' }
  },
  watch: {
    network: { handler() { this.drawNetwork(); }, immediate: true },
    // 风险变化：既要重绘网络层的风险走廊，也要立刻重绘路线的红/绿分段与基准线标红。
    // 原先这里只调 highlightRisks()，而 drawRoute/drawBaseline 只由「路线几何」的 watcher 驱动，
    // 于是熔断后必须等 planCrossBorder() 把新几何拉回来才变色 —— 这就是"会红但慢"的主因。
    // 路线几何与风险集合同步下发，这里两条链一起走，熔断那一段立即变红。
    risks: {
      handler() {
        this.highlightRisks();
        this._scheduleDrawRoute();
        this.drawBaseline(this.baseline);
      },
      immediate: true
    },
    // 坐标与「边区间」可能同一次刷新里一起变化，合并到同一微任务，避免重复重绘闪烁
    route: { handler() { this._scheduleDrawRoute(); } },
    routeEdgeIds: { handler() { this._scheduleDrawRoute(); } },
    routeEdgeSpans: { handler() { this._scheduleDrawRoute(); } },
    baseline: { handler(n) { this.drawBaseline(n); } },
    baselineEdgeIds: { handler() { this.drawBaseline(this.baseline); } },
    baselineEdgeSpans: { handler() { this.drawBaseline(this.baseline); } },
    hazardPoints: { handler(n) { this.drawHazardPoints(n); } },
    rerouted: { handler() { this.drawRoute(this.route); } },
    selectedEdge: { handler() { this.styleSelected(); } },
    pickOrigin: { handler(v) { this.drawPickMarker('origin', v); } },
    pickDestination: { handler(v) { this.drawPickMarker('destination', v); } },
    // 候选列表变化（拉回 / 选中切换）→ 重新画备选线
    candidates: { handler() { this.drawAlternates(); }, deep: true },
    selectedCandidateKey: { handler() { this.drawAlternates(); } }
  },
  data() {
    return {
      map: null,
      edgeInfo: {},   // 全部边的 id -> 几何折线 [[lat,lon],...]，供选中高亮建层
      edgeLayers: {},  // 口岸/风险交互边
      nodeLayers: {},  // 地名标签
      routeLayers: [],      // 安全段（绿色）多段线集合
      riskRouteLayers: [],  // 风险段（红色）多段线集合
      baselineLayers: [],  // 基准路线：灰虚线（安全段）+ 红虚线（原路线穿过的风险段）
      hazardLayers: [],
      selectedLayer: null,
      tileErrors: 0,
      // 默认底图：天地图在线影像（img_w）+ 注记（cia_w），WMTS 栅格，合规、边界安全。
      // 在线服务，拔网线即失效——离线演示请用 cycleTileSource 切到「矢量(GPU)」本地瓦片。
      // 矢量瓦片在 tools/data/tiles（z0-14），后端 /tiles/** 托管，由 MapLibre WebGL GPU 渲染。
      tileSourceIdx: 0,
      tileSourceName: '天地图',
      localMbLayer: null,
      // 天地图注记层（cia_w），叠在影像底图之上，只画地名/边界
      tileAnnoLayer: null,
      // 底图源。每项可带 match：用于从瓦片 URL 反推源（见 guessSourceIdx），
      // 以前那里写着硬编码域名判断，增删源时会与数组错位。
      tileSources: [
        {
          // 天地图在线 WMTS 栅格：影像底图 img_w + 影像注记 cia_w。
          // DataServer 端点是标准 XYZ 风格，L.tileLayer 直接可用；tk 从环境变量注入。
          // 不设 crossOrigin：天地图未必回 CORS 头，加了反而会让 <img> 加载失败。
          name: '天地图',
          url: `https://t{s}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=${TIANDITU_TK}`,
          annoUrl: `https://t{s}.tianditu.gov.cn/DataServer?T=cia_w&x={x}&y={y}&l={z}&tk=${TIANDITU_TK}`,
          subdomains: '01234567',
          match: 'tianditu.gov.cn',
          attribution: '&copy; 天地图',
          maxZoom: 18,
          minZoom: 3
        },
        {
          name: 'OSM',
          url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
          subdomains: 'abc',
          match: 'openstreetmap.org',
          attribution: '&copy; OpenStreetMap contributors',
          crossOrigin: true
        },
        {
          // 本地矢量瓦片 + MapLibre WebGL 渲染：GPU 合成、完全离线（拔网线兜底）。
          // 滑动/缩放由 GPU 合成，不再逐张解码 jpg/png。
          name: '矢量(GPU)',
          url: '/tiles/{z}/{x}/{y}.pbf',
          subdomains: null,
          match: '/tiles/',
          attribution: '&copy; OpenStreetMap contributors',
          local: true,
          vector: true,
          maxZoom: 14,
          minZoom: 7
        }
      ],
      pickMarkerOrigin: null,
      pickMarkerDest: null,
      // 非选中候选路线的图层集合（浅蓝虚线）
      alternateLayers: []
    };
  },
  computed: {
    pickHint() {
      if (!this.pickMode) return '';
      if (this.pickOrigin && !this.pickDestination) return '已选起点，请点击地图选择终点';
      if (!this.pickOrigin) return '请点击地图选择起点';
      return '再次点击地图重新规划（先点起点再点终点）';
    }
  },
  mounted() {
    this.initMap();
    this.loadTiles(this.tileSourceIdx);
    if (!this.tileSources[this.tileSourceIdx].vector) this.startTileWatcher(); // 矢量底图由 MapLibre 自管理，无需瓦片巡检
    this.map.on('click', e => {
      if (this.pickMode) this.$emit('map-click', { lat: e.latlng.lat, lon: e.latlng.lng });
    });
  },
  beforeUnmount() {
    clearInterval(this._tileWatcher);
    if (this._zoomRedrawTimer) { clearTimeout(this._zoomRedrawTimer); this._zoomRedrawTimer = null; }
    if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null; }
    if (this.localMbLayer) { try { this.map.removeLayer(this.localMbLayer); } catch (e) {} this.localMbLayer = null; }
    if (this.tileAnnoLayer) { try { this.map.removeLayer(this.tileAnnoLayer); } catch (e) {} this.tileAnnoLayer = null; }
    if (this.alternateLayers) {
      this.alternateLayers.forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      this.alternateLayers = [];
    }
    if (this.map) { this.map.off('click'); this.map.remove(); }
  },
  methods: {
    initMap() {
      const s = this.tileSources[this.tileSourceIdx] || this.tileSources[0];
      const isLocal = !!s.local;
      // 矢量瓦片（pbf）是 EPSG:3857，与在线天地图/OSM 同系，不再需要 4326 特殊分支；
      // maxZoom 由各源声明：矢量封顶 14（原生 z14，再上靠 MapLibre overzoom），天地图/OSM 到 18。
      const options = {
        preferCanvas: true,
        // MapLibre GL 5 + maplibre-gl-leaflet 桥接下 zoomAnimation 必须为 true：关掉缩放
        // 动画会让 WebGL canvas 全程空白（矢量底图一片白），见 DEV_LOG「地图缩放空白」
        // 条目——false 是被实测更差的配置，别再改回去。栅格源用 true 也是 Leaflet 默认。
        zoomAnimation: true,
	        fadeAnimation: false,
        minZoom: s.minZoom || 2,
        maxZoom: s.maxZoom || 18,
        crs: L.CRS.EPSG3857
      };
      if (this.map) {
        this.map.off('click');
        this.map.remove();
      }
      // 地图重建后原特效层已失效，置空以便 drawRoute 时重建
      if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null; }
      this.map = L.map(this.$refs.mapEl, options).setView([21.0, 106.0], isLocal ? 7 : 6);
      this.map.on('click', e => {
        if (this.pickMode) this.$emit('map-click', { lat: e.latlng.lat, lon: e.latlng.lng });
      });
      // 缩放结束后按新 zoom 重新塑形路线（路线几何是按 zoom 抽稀/插值过的，
      // 缩放后不重绘就仍是旧 zoom 的形状，看起来像"线断了/没了"）。
      //
      // 这段以前只写在 cycleTileSource() 里，而 initMap() 会 map.remove() 重建地图实例、
      // 旧实例的监听随之失效；默认加载路径（mounted → initMap → loadTiles）根本不经过
      // cycleTileSource，所以默认情况下**压根没有缩放重绘**。改绑到这里，每个新实例都绑。
      this.map.on('zoomend', this._onZoomRedraw);
    },
    // 手动切换底图源（按 tileSources 顺序循环）
    cycleTileSource() {
      this.tileSourceIdx = (this.tileSourceIdx + 1) % this.tileSources.length;
      this.tileSourceName = this.tileSources[this.tileSourceIdx].name;
      this.initMap();
      this.loadTiles(this.tileSourceIdx);
      // 默认源是矢量（不需要巡检），切到在线栅格源时才把自愈巡检挂上；
      // 切回矢量则停掉，避免定时器空转
      if (!this.tileSources[this.tileSourceIdx].vector) this.startTileWatcher();
      else clearInterval(this._tileWatcher);
      if (this.network) this.drawNetwork(true);
      if (this.route && this.route.length) this.drawRoute(this.route);
      if (this.baseline && this.baseline.length) this.drawBaseline(this.baseline);
      if (this.hazardPoints && this.hazardPoints.length) this.drawHazardPoints(this.hazardPoints);
      // 切底图后重新叠加备选路线
      if (this.candidates && this.candidates.length) this.drawAlternates();
    },
    /**
     * 缩放结束后的路线重绘（绑定在每个地图实例上，见 initMap）。
     * 只监听 zoomend、不监听 moveend，避免拖拽时高频重绘。
     * 280ms 防抖：连续缩放只重绘最后一次。
     */
    _onZoomRedraw() {
      if (this._zoomRedrawTimer) clearTimeout(this._zoomRedrawTimer);
      this._zoomRedrawTimer = setTimeout(() => {
        this._zoomRedrawTimer = null;
        // 换缩放后签名必然变化（签名含 zoom），不会被 drawRoute 的跳过逻辑拦掉
        if (this._lastRoute && this._lastRoute.length) this.drawRoute(this._lastRoute);
        if (this._lastBaseline && this._lastBaseline.length) this.drawBaseline(this._lastBaseline);
      }, 280);
    },
    // 自动切换到底图列表中的下一个源，用于当前源大量失败时兜底
    autoSwitchSource() {
      const next = (this.tileSourceIdx + 1) % this.tileSources.length;
      console.warn(`当前底图[${this.tileSources[this.tileSourceIdx].name}]大量加载失败，自动切换为[${this.tileSources[next].name}]`);
      this.tileSourceIdx = next;
      this.tileSourceName = this.tileSources[next].name;
      this.loadTiles(next);
    },
    // 从瓦片 src 反推属于哪个源：按各源声明的 match 片段匹配（数据驱动）。
    // 以前这里是硬编码的「0=高德/1=OSM/2=Carto」，与 tileSources 数组下标是两套编号，
    // 增删底图源时必然错位——删掉高德后它还会被当作兜底源继续请求。
    guessSourceIdx(src) {
      const i = this.tileSources.findIndex(s => s.match && src.includes(s.match));
      return i >= 0 ? i : this.tileSourceIdx;
    },
    // 生成某个源的同坐标瓦片 URL（跨源兜底重试用）。
    // 用 tileSources 里的模板做占位符替换，保证"数组里没有的源就永远不会被请求"。
    buildSourceUrl(idx, z, x, y, extra) {
      const s = this.tileSources[idx];
      if (!s || !s.url) return null;
      const subs = s.subdomains
        ? (Array.isArray(s.subdomains) ? s.subdomains : String(s.subdomains).split(''))
        : [];
      const sub = subs.length ? subs[(x + y) % subs.length] : '';
      const base = s.url
        .replace('{s}', sub)
        .replace('{z}', z).replace('{x}', x).replace('{y}', y)
        .replace('{r}', '');
      return base + (base.includes('?') ? '&' : '?') + 'r=' + (extra || Date.now());
    },
    // 备用候选 URL：除当前源外的其它**在线栅格**源，按数组顺序。
    // 排除 local/vector 源——矢量瓦片不是图片，不能当栅格候选重试。
    buildCandidateUrls(z, x, y, currentIdx) {
      return this.tileSources
        .map((_, i) => i)
        .filter(i => i !== currentIdx && !this.tileSources[i].local && !this.tileSources[i].vector)
        .map(i => this.buildSourceUrl(i, z, x, y))
        .filter(Boolean);
    },
    // 从瓦片 src 解析 z/x/y（兼容 OSM 路径式、天地图 DataServer 查询式 URL）
    parseTileCoords(src) {
      let m = src.match(/\/(\d+)\/(\d+)\/(\d+)(@2x)?\.png/i);
      if (m) return { z: +m[1], x: +m[2], y: +m[3] };
      m = src.match(/[?&]x=(\d+)&y=(\d+)&z=(\d+)/);
      if (m) return { z: +m[3], x: +m[1], y: +m[2] };
      // 天地图 DataServer 端点用 l= 表示 zoom（?T=img_w&x=..&y=..&l=..）
      m = src.match(/[?&]x=(\d+)&y=(\d+)&l=(\d+)/);
      if (m) return { z: +m[3], x: +m[1], y: +m[2] };
      return null;
    },
    // 对彻底失败的瓦片执行隐藏，避免灰色占位块一直留在地图上
    hideBrokenTile(tile) {
      if (!tile) return;
      tile.style.visibility = 'hidden';
      tile.style.display = 'none';
      tile.dataset.allFailed = '1';
    },
    // 瓦片源：按 tileSources 数组顺序（天地图 / OSM / 矢量(GPU)）。
    // 矢量源直接交给 L.maplibreGL（WebGL 渲染）；在线栅格源挂白块自愈策略：
    // 单瓦片按 同源随机重试 → 多候选跨源(仅在线栅格源) → 全部失败则隐藏灰块。
    // 连续失败过多则整体切源。
    loadTiles(idx) {
      if (idx >= this.tileSources.length) return;
      const s = this.tileSources[idx];
      if (this.localMbLayer) { try { this.map.removeLayer(this.localMbLayer); } catch (e) {} this.localMbLayer = null; }
      if (this.tileLayer) { this.map.removeLayer(this.tileLayer); this.tileLayer = null; }
      if (this.tileAnnoLayer) { try { this.map.removeLayer(this.tileAnnoLayer); } catch (e) {} this.tileAnnoLayer = null; }
      this.tileErrors = 0;

      // 本地矢量瓦片：MapLibre WebGL 渲染（GPU 合成、支持 overzoom），完全离线。
      // 以前这里是 D 盘 jpg 栅格分支（EPSG:4326），选错会永久白屏且不挂自愈；
      // 现在矢量层由 MapLibre 自己管理加载/重试，无 tileerror 自愈需求。
      if (s.vector) {
        this.localMbLayer = L.maplibreGL({
          style: localBaseStyle,
          pane: 'tilePane',
          attribution: s.attribution
        }).addTo(this.map);
        return;
      }

      const tileOpts = {
        subdomains: s.subdomains,
        attribution: s.attribution,
        maxZoom: s.maxZoom || 18,
        updateWhenIdle: false,
        keepBuffer: 2,
        crossOrigin: s.crossOrigin || false
      };
      this.tileLayer = L.tileLayer(s.url, tileOpts).addTo(this.map);
      // 天地图注记层（cia_w）：叠在影像之上显示地名/边界。不挂 tileerror 跨源自愈
      //（注记换成 OSM 底图无意义且会盖在影像上），破损块由 sweepTiles 静默隐藏。
      if (s.annoUrl) {
        this.tileAnnoLayer = L.tileLayer(s.annoUrl, { ...tileOpts, attribution: '', className: 'tdt-anno-layer' }).addTo(this.map);
      }
      this.tileLayer.on('tileerror', (e) => {
        const tile = e.tile;
        if (!tile) return;
        const c = this.parseTileCoords(tile.src);
        if (!c) {
          // 解析不出坐标：同源带随机戳重试一次
          const sep = tile.src.includes('?') ? '&' : '?';
          tile.src = tile.src + sep + '_retry=' + Date.now();
          tile.dataset.src = tile.src;
          return;
        }
        const currentIdx = this.guessSourceIdx(tile.src);
        const candidates = this.buildCandidateUrls(c.z, c.x, c.y, currentIdx);
        const fi = Number(tile.dataset.fallbackIdx || 0);
        if (fi < candidates.length) {
          tile.src = candidates[fi];
          tile.dataset.src = tile.src;
          tile.dataset.fallbackIdx = String(fi + 1);
          tile.style.visibility = '';
          tile.style.display = '';
          return;
        }
        // 所有候选源都失败，隐藏灰块，避免一直占位
        this.hideBrokenTile(tile);
        this.tileErrors++;
        if (this.tileErrors > 4) this.autoSwitchSource();
      });
    },
    // 挂起瓦片自愈：部分瓦片请求会一直 pending（无 error 事件）或静默失败，
    // 高频巡检（1s）视口内超 2s 未完成/损坏的瓦片，立即跨源补瓦，让白块快速消失
    startTileWatcher() {
      clearInterval(this._tileWatcher);
      this.sweepTiles(true); // 进图即扫一轮，不等第一个巡检周期
      this._tileWatcher = setInterval(() => this.sweepTiles(false), 3000);
    },
    // 瓦片自愈巡检：普通轮询(force=false)或缩放结束强制清扫(force=true)。
    // 关键：Leaflet 缩放/平移时复用 img 元素加载新瓦片，src 变化后必须重置自愈标记，
    // 否则残留的 retried/fallback 会让新瓦片永远不被巡检，导致缩放后出现方块残影。
    sweepTiles(force) {
      if (!this.map || !this.tileLayer) return;
      const curSrc = this.tileSources[this.tileSourceIdx];
      if (curSrc && curSrc.vector) return; // 矢量底图（MapLibre）无瓦片自愈需求
      const now = Date.now();
      const zoom = this.map.getZoom();
      const panes = this.map.getPanes();
      if (!panes || !panes.tilePane) return;
      panes.tilePane.querySelectorAll('img.leaflet-tile').forEach(img => {
        if (img.dataset.src !== img.src) { // 元素被 Leaflet 复用换了新瓦片
          img.dataset.src = img.src;
          delete img.dataset.retried;
          delete img.dataset.fallback;
          delete img.dataset.fallbackIdx;
          delete img.dataset.allFailed;
          delete img.dataset.startTs;
          img.style.visibility = '';
          img.style.display = '';
        }
        const c = this.parseTileCoords(img.src);
        if (c && c.z !== zoom) {
          // 非当前 zoom 层级：强制隐藏，避免缩放残留灰块
          img.style.visibility = 'hidden';
          img.style.display = 'none';
          return;
        }
        if (!img.dataset.startTs) img.dataset.startTs = String(now);
        const age = now - Number(img.dataset.startTs);
        // 缩放刚结束时瓦片多半才发出请求，留 400ms 窗口再判定；
        // 普通轮询则按 2s/1.5s 判定挂起与静默失败
        const pending = !img.complete && age > (force ? 400 : 2000);
        const broken = img.complete && img.naturalWidth === 0 && age > (force ? 100 : 1500);
        if (pending || broken) {
          // 天地图注记层（cia_w）不做跨源自愈：换成 OSM 底图会盖在影像上，视觉错乱。
          // 只对「确实损坏」的注记块隐藏，仍在加载(pending)的留给它自己完成。
          if (img.src.includes('cia_w')) { if (broken) this.hideBrokenTile(img); return; }
          const currentIdx = this.guessSourceIdx(img.src);
          const candidates = this.buildCandidateUrls(c.z, c.x, c.y, currentIdx);
          const fi = Number(img.dataset.fallbackIdx || 0);
          if (c && fi < candidates.length) {
            img.src = candidates[fi];
            img.dataset.src = img.src;
            img.dataset.fallbackIdx = String(fi + 1);
            img.style.visibility = '';
            img.style.display = '';
            return;
          }
          this.hideBrokenTile(img);
          this.tileErrors++;
          if (this.tileErrors > 4) this.autoSwitchSource();
        }
      });
    },
    // 一次性全量构建路网交互层（底图自带道路与地名，不再自绘路网）
    drawNetwork(preserveView = false) {
      if (!this.network || !this.map) return;
      // ---- 清空交互层 ----
      Object.values(this.edgeLayers || {}).forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      Object.values(this.riskLayers || {}).forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      Object.values(this.nodeLayers || {}).forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      if (this.selectedLayer) { try { this.map.removeLayer(this.selectedLayer); } catch (e) {} }
      this.edgeLayers = {};
      this.riskLayers = {};
      this.nodeLayers = {};
      this.selectedLayer = null;
      this.edgeInfo = {};

      const nodesById = {};
      (this.network.nodes || []).forEach(n => { nodesById[n.id] = n; });
      const allLatLngs = [];

      // ---- 节点：口岸/港区 → divIcon（可点击弹窗）；其余节点不画（底图自带） ----
      // 「港区」也要标注：平陆运河方案会把司机导航到南宁港六景作业区，
      // 只标口岸的话港区在图上没有名字，看不出车是往港区开的
      const portMarkers = [];
      (this.network.nodes || []).forEach(n => {
        if (/口岸|作业区|港区/.test(n.name || '')) {
          const m = L.marker([n.latitude, n.longitude], {
            icon: L.divIcon({
              className: 'place-label-wrap',
              html: `<span class="place-label-text port">${n.name}</span>`,
              iconSize: [0, 0],
              iconAnchor: [0, 0]
            }),
            interactive: true,
            keyboard: false
          });
          const isPort = /作业区|港区/.test(n.name || '');
          m.bindPopup(`<b>${n.name}</b><br/>${n.latitude.toFixed(4)}, ${n.longitude.toFixed(4)}<br/><i>${isPort ? '港区（公水联运换装点）' : '口岸（通关虚拟边）'}</i>`);
          portMarkers.push(m);
        }
      });

      // ---- 边：口岸/风险 → Leaflet 交互层；普通边不绘制（底图瓦片自带道路走向） ----
      const riskSet = new Set((this.risks || []).map(r => r.edgeId));
      this._riskEdgeIds = riskSet;
      const interactiveJobs = [];
      (this.network.edges || []).forEach(e => {
        const from = nodesById[e.fromNodeId];
        const to = nodesById[e.toNodeId];
        if (!from || !to) return;
        // 几何折线：有 c（OSM 真实走向）用它，否则退化为两端点直线
        const geom = (e.c && e.c.length >= 2)
          ? e.c
          : [[from.latitude, from.longitude], [to.latitude, to.longitude]];
        // 几何索引与视图适配只在"首次装载"时需要。
        // highlightRisks 走的是 preserveView=true，原来这里仍为全部 6.2 万条边存几何、
        // 白建一份 12 万元素的 allLatLngs 再丢弃 —— 每次风险变化都白跑一遍，是"标红慢"的一部分。
        if (!preserveView) {
          allLatLngs.push(geom[0], geom[geom.length - 1]);
          this.edgeInfo[e.id] = geom;   // 供选中高亮使用
        }
        if (e.customs) {
          // 口岸虚拟边：红色粗线，可点击
          const poly = L.polyline(geom, { color: '#c62828', weight: 4, pane: 'markerPane', interactive: true });
          poly.bindTooltip(e.name || e.id, { sticky: true });
          poly.on('click', () => { this.$emit('edge-click', e.id); });
          interactiveJobs.push([e.id, poly]);
          this.edgeLayers[e.id] = poly;
        } else if (riskSet.has(e.id)) {
          // 风险路段：橙色粗线，可点击
          const poly = L.polyline(geom, { color: 'orange', weight: 5, pane: 'markerPane', interactive: true });
          poly.bindTooltip(e.name || e.id, { sticky: true });
          poly.on('click', () => { this.$emit('edge-click', e.id); });
          interactiveJobs.push([e.id, poly]);
          this.edgeLayers[e.id] = poly;
        }
      });

      // ---- 交互层直接 addTo（口岸/风险量少，无分帧） ----
      interactiveJobs.forEach(([, l]) => { this.map.addLayer(l); });
      portMarkers.forEach((m, i) => {
        this.map.addLayer(m);
        this.nodeLayers['port-' + i] = m;
      });

      // ---- 一次性把全路网装进视野（封顶 z8，避免加载后再自动缩放“变来变去”） ----
      if (allLatLngs.length && !preserveView) {
        // animate:false：初始 fitBounds 若开缩放动画，其 _onZoomTransitionEnd 回调是异步延后触发的，
        // 一旦这中间地图被重建/卸载（dev 下 HMR 重挂等），回调读到已销毁的 _mapPane 会抛
        // "Cannot read properties of undefined (reading '_leaflet_pos')"，且外层 try/catch 兜不到。初始定位无需动画。
        try { this.map.fitBounds(allLatLngs, { padding: [40, 40], maxZoom: 8, animate: false }); } catch (e) {}
      }
      this.styleSelected();
    },
    // 风险集合变化：重建交互层；若 15 秒轮询后风险没变则直接跳过，避免整图重绘导致闪跳
    highlightRisks() {
      if (!this.map || !this.network) return;
      const riskSet = new Set((this.risks || []).map(r => r.edgeId));
      const old = this._riskEdgeIds;
      if (old && old.size === riskSet.size) {
        let same = true;
        riskSet.forEach(id => { if (!old.has(id)) same = false; });
        if (same) return;
      }
      this.drawNetwork(true);
    },
    // 选中边：金色高亮层（叠加在交互层之上）
    styleSelected() {
      const map = this.map;
      if (!map) return;
      if (this.selectedLayer) { try { map.removeLayer(this.selectedLayer); } catch (e) {} this.selectedLayer = null; }
      if (!this.selectedEdge) return;
      const c = this.edgeInfo[this.selectedEdge];
      if (!c) return;
      // c 为几何折线 [[lat,lon],...]（含真实走向），高亮层贴合底图
      this.selectedLayer = L.polyline(c, {
        color: '#FFD700', weight: 7, opacity: 0.95, pane: 'markerPane'
      }).addTo(map);
    },
    _scheduleDrawRoute() {
      if (this._routeDrawScheduled) return;
      this._routeDrawScheduled = true;
      Promise.resolve().then(() => {
        this._routeDrawScheduled = false;
        this.drawRoute(this.route);
      });
    },
    // 按「风险 / 安全」分段着色绘制路线：风险段红、安全段绿（与司机端 DriverApp 一致）
    drawRoute(coords) {
      // 无谓重绘免疫：签名不变且图层仍在时直接返回。
      // 大屏每 15 秒轮询都会给同一份路线，清空再重建 5~6 个图层会让线闪一下，
      // 肉眼就是"线抖了一下/短暂不见了"。
      const sig = this._routeSignature(coords);
      if (sig && sig === this._lastRouteSig
          && (this.routeLayers.length || this.riskRouteLayers.length)) {
        return;
      }
      this._lastRouteSig = sig;

      this._clearRouteLayers();
      // 记住"当前实际画的是什么"（空则置 null）：缩放重绘据此还原。
      // 置 null 而非保留旧值，否则清空路线后一缩放又会把旧线画回来。
      const hasCoords = !!(coords && coords.length && this.map);
      this._lastRoute = hasCoords ? coords : null;
      if (!hasCoords) {
        // 路线被清空时同步停掉流光/箭头，否则特效会残留在空地图上
        if (this._routeFlow) this._routeFlow.setSegments([]);
        return;
      }
      const z = (this.map.getZoom && this.map.getZoom()) || 7;
      const raw = coords.map(p => [p.lat, p.lon]);

      // 风险边集合：路网风险列表 ∩ 当前路径（splitRouteByRisk 内部只认路径上的边）
      const riskSet = riskEdgeSet(this.risks, []);
      const groups = splitRouteByRisk(raw, this.routeEdgeIds, this.routeEdgeSpans, riskSet);

      const flowSegs = [];
      groups.forEach(g => {
        // 逐段塑形（段间共用衔接点，塑形后不会断口），保证线仍贴合公路走向
        const latlngs = reshapeForZoom(g.pts, z, this.map);
        if (latlngs.length < 2) return;
        if (g.risk) {
          // 风险段：宽半透明红光晕 + 亮红主线（光晕在下层，主线在上层）
          this.riskRouteLayers.push(L.polyline(latlngs, {
            color: '#e53935', weight: 14, opacity: 0.28, pane: 'markerPane'
          }).addTo(this.map));
          this.riskRouteLayers.push(L.polyline(latlngs, {
            color: '#e53935', weight: 6, opacity: 0.95, pane: 'markerPane'
          }).addTo(this.map));
        } else {
          // 安全段：绿色实线
          this.routeLayers.push(L.polyline(latlngs, {
            color: '#16a34a', weight: 5, opacity: 0.9, pane: 'markerPane'
          }).addTo(this.map));
        }
        flowSegs.push({ latlngs, color: g.risk ? '#e94560' : '#16a34a' });
      });

      // 前进箭头 + 流光带也要按段着色，否则单色特效会盖住红绿分段
      if (!this._routeFlow) {
        this._routeFlow = createRouteFlow(this.map, { color: '#16a34a' });
      }
      this._routeFlow.setSegments(flowSegs);
      this._frontRouteLayers();
    },
    /**
     * 主路线置顶：备选候选线（虚线）与主线走同一条走廊，
     * 若在主线之后绘制就会盖住主线，看起来像主线"断了一截/消失"。
     */
    _frontRouteLayers() {
      if (!this.map) return;
      [...this.routeLayers, ...this.riskRouteLayers].forEach(l => {
        try { if (l && l.bringToFront) l.bringToFront(); } catch (e) {}
      });
      if (this._routeFlow && typeof this._routeFlow.bringToFront === 'function') {
        try { this._routeFlow.bringToFront(); } catch (e) {}
      }
    },
    /**
     * 路线绘制指纹：zoom + 点数 + 边区间数 + 抽样坐标 + 风险集合。
     * 用于判断"这次要画的是不是和上次一模一样"，从而跳过重绘。
     * 采样而非全量拼串：几千个点时全量拼接会明显拖慢主线程。
     */
    _routeSignature(coords) {
      if (!coords || !coords.length || !this.map) return '';
      const n = coords.length;
      const z = (this.map.getZoom && this.map.getZoom()) || 7;
      const step = Math.max(1, Math.floor(n / 8));
      let acc = '';
      for (let i = 0; i < n; i += step) {
        const p = coords[i];
        acc += (p.lat || 0).toFixed(5) + ',' + (p.lon || 0).toFixed(5) + ';';
      }
      const last = coords[n - 1];
      acc += (last.lat || 0).toFixed(5) + ',' + (last.lon || 0).toFixed(5);
      // 风险集合必须参与：注入/解除风险后红绿分段要跟着变，否则会被误判为无变化
      const riskAcc = (this.risks || []).map(r => r.edgeId).join(',');
      // rerouted 也参与：它有独立 watcher 主动触发重绘，不能被跳过逻辑吃掉
      return `${z}|${n}|${(this.routeEdgeIds || []).length}|${(this.routeEdgeSpans || []).length}`
        + `|${this.rerouted ? 1 : 0}|${acc}|${riskAcc}`;
    },
    _clearRouteLayers() {
      if (!this.map) return;
      this.routeLayers.forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      this.riskRouteLayers.forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      this.routeLayers = [];
      this.riskRouteLayers = [];
    },
    // 把非选中的候选路线画为细虚线（统一浅蓝灰色），不抢主线视觉
    // 选中那条由 drawRoute 走风险红/安全绿 + 流光主样式，无需重复画
    drawAlternates() {
      if (!this.map) return;
      // 清旧层
      this.alternateLayers.forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      this.alternateLayers = [];
      if (!this.candidates || !this.candidates.length) return;
      const z = (this.map.getZoom && this.map.getZoom()) || 7;
      this.candidates.forEach((c) => {
        if (!c || c.key === this.selectedCandidateKey) return;
        const coords = c.coords || [];
        if (!coords.length) return;
        const raw = coords.map(p => [p[0], p[1]]);
        const latlngs = reshapeForZoom(raw, z, this.map);
        if (!latlngs || latlngs.length < 2) return;
        // 推荐候选用较显眼的青色虚线，其他用淡蓝色
        const isRec = c.key === 'recommended' || c.softened;
        const color = c.softened ? '#ea7a2e' : (isRec ? '#22b8cf' : '#6c8ebf');
        const weight = c.softened ? 4 : (isRec ? 3 : 3);
        const opacity = c.softened ? 0.85 : 0.65;
        const dashArray = c.softened ? '8 4' : '4 6';
        const line = L.polyline(latlngs, {
          color, weight, opacity, dashArray,
          pane: 'markerPane',
          interactive: true,
          className: 'route-alternate'
        }).addTo(this.map);
        const riskTag = c.riskCount ? `${c.riskCount} 风险` : '无风险';
        const tip = `${c.label || c.key}\n${c.hours != null ? c.hours + 'h' : '-'} · ${c.distanceKm != null ? c.distanceKm + 'km' : '-'} · ${riskTag}${c.via && c.via.length ? '\n途经 ' + (Array.isArray(c.via) ? c.via.join('→') : c.via) : ''}${c.softened ? '\n⚠ 降级兜底' : ''}`;
        line.bindTooltip(tip, { sticky: true, direction: 'top', className: 'route-alt-tooltip' });
        line.on('click', () => {
          // 点击备选线 → 上抛 App 切换主线
          this.$emit('candidate-pick', c.key);
        });
        this.alternateLayers.push(line);
      });
      // 备选线是后画的，会把主线压在下面（同一走廊上尤其明显），最后把主线提回顶层
      this._frontRouteLayers();
    },
    // 基准（未绕行）路线：灰色虚线；其中穿过风险区的段改红色虚线，直观对比"绕行避开了哪一段"
    drawBaseline(coords) {
      if (!this.map) return;
      this.baselineLayers.forEach(l => { try { this.map.removeLayer(l); } catch (e) {} });
      this.baselineLayers = [];
      // 同 drawRoute：记住当前实际画出的是什么，供缩放重绘还原；
      // 此前 _lastBaseline 从未赋值，缩放重绘里的基准路线分支是死代码，
      // 灰虚线在缩放后一直保持旧 zoom 的塑形结果。
      this._lastBaseline = (coords && coords.length) ? coords : null;
      if (!coords || !coords.length) return;
      const z = (this.map.getZoom && this.map.getZoom()) || 7;
      const raw = coords.map(p => [p.lat, p.lon]);
      const riskSet = riskEdgeSet(this.risks, []);
      const groups = splitRouteByRisk(raw, this.baselineEdgeIds, this.baselineEdgeSpans, riskSet);
      groups.forEach(g => {
        const latlngs = reshapeForZoom(g.pts, z, this.map);
        if (latlngs.length < 2) return;
        this.baselineLayers.push(L.polyline(latlngs, {
          color: g.risk ? '#e53935' : '#888',
          weight: g.risk ? 4 : 3,
          dashArray: g.risk ? '2 6' : '8 6',
          opacity: g.risk ? 0.9 : 0.7,
          pane: 'markerPane'
        }).addTo(this.map));
      });
    },
    drawHazardPoints(points) {
      if (this.hazardLayers && this.hazardLayers.length) {
        this.hazardLayers.forEach((l) => { try { this.map.removeLayer(l); } catch (e) {} });
      }
      this.hazardLayers = [];
      if (!points || !points.length || !this.map) return;
      points.forEach((p) => {
        if (p.lat == null || p.lon == null) return;
        const m = L.marker([p.lat, p.lon], {
          icon: L.divIcon({
            className: 'hazard-cross-wrap',
            html: '<span class="hazard-cross">×</span>',
            iconSize: [18, 18],
            iconAnchor: [9, 9]
          })
        }).addTo(this.map);
        if (p.reason) m.bindTooltip(p.reason, { sticky: true });
        this.hazardLayers.push(m);
      });
    },
    drawPickMarker(kind, node) {
      const key = kind === 'origin' ? 'pickMarkerOrigin' : 'pickMarkerDest';
      if (this[key]) { this.map.removeLayer(this[key]); this[key] = null; }
      if (!node || !this.map) return;
      const color = kind === 'origin' ? '#16a34a' : '#c62828';
      const label = kind === 'origin' ? '起' : '终';
      this[key] = L.marker([node.latitude, node.longitude], {
        icon: L.divIcon({
          className: 'pick-divicon',
          html: `<div class="pick-marker" style="background:${color}">${label}</div>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13]
        })
      }).addTo(this.map);
      this[key].bindPopup(`<b>${kind === 'origin' ? '起点' : '终点'}</b>：${node.name}<br/>${node.latitude.toFixed(4)}, ${node.longitude.toFixed(4)}`);
    }
  }
};
</script>

<style scoped>
.map-root { position:relative; width:100%; height:100%; }
.map-canvas { position:absolute; inset:0; }
.pick-hint { position:absolute; top:12px; left:50%; transform:translateX(-50%); z-index:500; background:rgba(11,83,148,0.92); color:#fff; padding:8px 16px; border-radius:20px; font-size:13px; box-shadow:0 2px 10px rgba(0,0,0,0.25); pointer-events:none; }
.pick-marker { width:26px; height:26px; border-radius:50%; color:#fff; font-weight:700; font-size:14px; display:flex; align-items:center; justify-content:center; border:2px solid #fff; box-shadow:0 1px 6px rgba(0,0,0,0.4); }
/* 地名注记：纯文字 + 黑色描边（去掉蓝色方块），任何底图上都清晰 */
:deep(.place-label-text) { position:absolute; transform:translate(-50%,-100%); margin-top:-5px; color:#fff; font-size:11px; font-weight:600; white-space:nowrap; pointer-events:auto; text-shadow:0 0 3px rgba(0,0,0,0.95),0 0 3px rgba(0,0,0,0.95),1px 1px 2px rgba(0,0,0,0.85),-1px -1px 2px rgba(0,0,0,0.85),1px -1px 2px rgba(0,0,0,0.85),-1px 1px 2px rgba(0,0,0,0.85); }
:deep(.place-label-text.port) { color:#ffd54f; font-size:12px; font-weight:800; }
.map-overlay { position:absolute; top:12px; right:12px; z-index:400; }
:deep(.zh-label-wrap) { position:absolute; transform:translate(-50%,-100%); margin-top:-4px; pointer-events:none; white-space:nowrap; }
:deep(.zh-label) { color:#fff; font-weight:700; text-shadow:0 0 3px rgba(0,0,0,0.95),0 0 3px rgba(0,0,0,0.95),1px 1px 2px rgba(0,0,0,0.85),-1px -1px 2px rgba(0,0,0,0.85),1px -1px 2px rgba(0,0,0,0.85),-1px 1px 2px rgba(0,0,0,0.85); }
:deep(.zh-label.lv1) { font-size:14px; }
:deep(.zh-label.lv2) { font-size:12px; }
:deep(.zh-label.lv3) { font-size:10px; opacity:0.92; }
.overlay-card { background:rgba(255,255,255,0.95); padding:8px 10px; border-radius:6px; box-shadow:0 2px 8px rgba(0,0,0,0.12); }
.overlay-toolbar { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.tile-switch { font-size:11px; padding:3px 8px; border:1px solid #0b5394; color:#0b5394; background:#fff; border-radius:4px; cursor:pointer; }
.tile-switch:hover { background:#e8f0fa; }
.overlay-row { font-size:12px; color:#444; margin-top:5px; display:flex; align-items:center; gap:6px; }
.dot { display:inline-block; width:14px; height:8px; border-radius:2px; }
.hazard-dot { display:inline-flex; width:14px; height:14px; align-items:center; justify-content:center; color:#d32f2f; font-weight:800; font-size:14px; line-height:1; }

/* 备选路线 tooltip */
.route-alt-tooltip {
  background:rgba(15, 24, 40, 0.92) !important;
  border:1px solid rgba(34, 184, 207, 0.5) !important;
  color:#3a4657 !important;
  font-size:12px !important;
  white-space:pre-line;
  padding:6px 8px !important;
  border-radius:6px !important;
}
.route-alternate { cursor:pointer; }
.route-alternate:hover { filter:brightness(1.4); }
</style>

<!-- 非 scoped：作用于 Leaflet 动态插入的 DOM -->
<style>
/* 瓦片加载中的占位背景：接近底图色调，避免缩放/拖动时出现刺眼白色方块 */
.leaflet-tile-pane { background:#dcebf2; }
/* 加载失败或尚未加载的瓦片 img 本身也兜底成浅蓝，避免浏览器默认灰白破损图 */
.leaflet-tile-pane img.leaflet-tile { background:#dcebf2 !important; }
/* 天地图注记层（cia_w）是透明 PNG（只有地名/边界），不能被上面的占位背景填成不透明浅蓝，
   否则会盖住影像底图；这里按图层容器 class 精确覆盖回透明。 */
.tdt-anno-layer img.leaflet-tile { background:transparent !important; }
.hazard-cross {
  display:inline-flex;
  width:18px;
  height:18px;
  align-items:center;
  justify-content:center;
  border-radius:50%;
  background:#fff;
  border:2px solid #d32f2f;
  color:#d32f2f;
  font-size:14px;
  font-weight:800;
  box-shadow:0 0 6px rgba(211,47,47,0.35);
}
</style>
