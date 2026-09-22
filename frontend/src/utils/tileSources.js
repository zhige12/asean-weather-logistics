// 在线底图源定义 + 天地图密钥（单一事实来源，大屏 MapView 与司机端 DriverApp 共用）。
// 主密钥从 Vite 环境变量注入：在 frontend/.env.local 配置 VITE_TIANDITU_TK=xxx；
// 另配多个备用密钥（TIANDITU_KEYS）：天地图按 key 限流/限额，单 key 打满会整屏 429 白屏，
// 每张瓦片请求在密钥池里轮转一个 tk，等效把配额乘以 key 数量，备用 key 兼作限流兵分。
import L from 'leaflet';

export const TIANDITU_TK = import.meta.env.VITE_TIANDITU_TK || '';

// 密钥池：环境变量主密钥 + 硬编码备用密钥（去重、去空）。用户提供的备用 key，
// 与主 key 同属一个账号体系下的多 key，天地图控制台按 key 分离计数。
export const TIANDITU_KEYS = [...new Set([
  TIANDITU_TK,
  '7f8ca63ad8984e9f2d4f0731c1e64fac',
  '05ff73a1e7f6750e0a413e8c4d350869',
  'a94951f2cd0628c79dbbad6fd6429898',
  '5c44f9d96a14869fbc175f4906817b2a'
].filter(Boolean))];

if (!TIANDITU_KEYS.length) {
  console.warn('[tileSources] 无可用天地图密钥，瓦片将返回 403，会自动降级到 D 盘离线瓦片。');
}

// 轮转游标：每次取瓦请求换一个 key，均摊限额压力
let _tkCursor = 0;
export function nextTiandituKey() {
  return TIANDITU_KEYS[(_tkCursor++) % TIANDITU_KEYS.length];
}

// 在线底图（按降级顺序）：天地图影像(img_w)+注记(cia_w) → 底图4 天地图矢量(vec_w)+注记(cva_w)
// → 底图5 OSM。都不可用时，才由调用方降级到 D 盘离线瓦片（EPSG:4326）。
// DataServer 端点是标准 XYZ 风格；天地图 URL 里 tk 用 {tk} 占位符，由 TiandituTileLayer
// 每张瓦片轮转填充（见下）；天地图不设 crossOrigin（未必回 CORS 头）。
export const ONLINE_BASES = [
  {
    key: 'tianditu',
    name: '天地图',
    url: 'https://t{s}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk={tk}',
    annoUrl: 'https://t{s}.tianditu.gov.cn/DataServer?T=cia_w&x={x}&y={y}&l={z}&tk={tk}',
    subdomains: '01234567',
    attribution: '&copy; 天地图',
    maxZoom: 18,
    // 天地图影像 img_w 在中越走廊高层级无覆盖（>z16 返回 200 的纯白瓦片），钉住原生级别由 Leaflet overzoom 拉伸
    maxNativeZoom: 16
  },
  {
    // 底图4：天地图矢量底图（导航用，道路分级清晰）+ 矢量注记（地名/路名）
    key: 'tdtvec',
    name: '底图4',
    url: 'https://t{s}.tianditu.gov.cn/DataServer?T=vec_w&x={x}&y={y}&l={z}&tk={tk}',
    annoUrl: 'https://t{s}.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk={tk}',
    subdomains: '01234567',
    attribution: '&copy; 天地图',
    maxZoom: 18
  },
  {
    // 底图5：OSM 在线栅格（早期版本从 git 历史恢复并更名）。境内直连 tile.openstreetmap.org
    // 无 CDN、可能慢/超时，故排在天地图两级之后作第三层兜底；失败瓦片仅隐藏不重试。
    key: 'osm',
    name: '底图5',
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    subdomains: 'abc',
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
    crossOrigin: true
  }
];

// 底图循环/降级链（大屏与司机端同序）：天地图影像(在线) → 底图4 天地图矢量(在线)
// → 底图5 OSM(在线) → D 盘离线瓦片(4326)
export const BASE_CHAIN = ['tianditu', 'tdtvec', 'osm', 'vector'];

// 按 key 取在线源定义；'vector' 不在 ONLINE_BASES 内，返回 null（由调用方走 D 盘离线瓦片分支）。
export function baseDef(key) {
  return ONLINE_BASES.find(b => b.key === key) || null;
}

// 天地图专用栅格层：重写 getTileUrl，每张瓦片从密钥池轮转取一个 tk 填入 {tk} 占位符。
// 只处3857（infinite CRS，无需 vanilla 的 -y 倒序）；URL 模板里也没有 {r}/@2x 占位符，
// detectRetina 对天地图本就是空操作，不必补。
const TiandituTileLayer = L.TileLayer.extend({
  getTileUrl(coords) {
    const data = {
      s: this._getSubdomain(coords),
      x: coords.x,
      y: coords.y,
      z: this._getZoomForUrl(),
      tk: nextTiandituKey()
    };
    return L.Util.template(this._url, L.extend(data, this.options));
  }
});

/**
 * 统一的在线瓦片层工厂：URL 含 {tk} 占位符的天地图源走密钥轮转层，
 * 其余源（OSM 等）走普通 L.tileLayer。大屏/司机端创建底图都过这里，
 * 避免两处各写一套判断。
 */
export function makeTileLayer(url, opts) {
  return url.includes('{tk}') ? new TiandituTileLayer(url, opts) : L.tileLayer(url, opts);
}
