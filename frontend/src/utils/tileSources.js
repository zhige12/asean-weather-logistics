// 在线底图源定义（单一事实来源，大屏 MapView 与司机端 DriverApp 共用）。
// 天地图瓦片一律走同源后端代理 /api/tianditu/{layer}/{z}/{x}/{y}.png：
// 密钥只存后端（tianditu.keys 密钥池轮转 + per-key 熔断），代理两级缓存
// （内存 LRU + 磁盘 data/tile-cache），响应带 30 天 immutable 长缓存——
// 前端不再持有任何密钥，热门瓦片全生命周期只消耗一次天地图配额。
import L from 'leaflet';

// 在线底图（按降级顺序）：天地图影像(img_w)+注记(cia_w) → 底图4 天地图矢量(vec_w)+注记(cva_w)
// → 底图5 OSM（无密钥、直连）。都不可用时，才由调用方降级到 D 盘离线瓦片（EPSG:4326）。
export const ONLINE_BASES = [
  {
    key: 'tianditu',
    name: '天地图',
    url: '/api/tianditu/img_w/{z}/{x}/{y}.png',
    annoUrl: '/api/tianditu/cia_w/{z}/{x}/{y}.png',
    attribution: '&copy; 天地图',
    maxZoom: 18,
    // 天地图影像 img_w 在中越走廊高层级无覆盖，钉住原生级别由 Leaflet overzoom 拉伸
    maxNativeZoom: 16
  },
  {
    // 底图4：天地图矢量底图（导航用，道路分级清晰）+ 矢量注记（地名/路名）
    key: 'tdtvec',
    name: '底图4',
    url: '/api/tianditu/vec_w/{z}/{x}/{y}.png',
    annoUrl: '/api/tianditu/cva_w/{z}/{x}/{y}.png',
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

/**
 * 按与运行时图层完全相同的规则拼某张瓦片的请求 URL（子域 = |x+y| mod 子域表，
 * 与 Leaflet _getSubdomain 同式；代理源 URL 无 {s} 占位符时原样保留）。
 * 供司机端导航开场瓦片预取使用：预取 URL 必须与运行时逐字节一致才能命中
 * HTTP 缓存（代理响应 Cache-Control: max-age=30d immutable）。
 */
export function tileUrlFromDef(def, x, y, z) {
  if (!def) return '';
  const subs = typeof def.subdomains === 'string' ? def.subdomains.split('') : (def.subdomains || ['a']);
  return L.Util.template(def.url, {
    s: subs[Math.abs(x + y) % subs.length],
    x,
    y,
    z
  });
}

/**
 * 统一的在线瓦片层工厂。天地图改走同源代理后不再需要密钥轮转层，
 * 所有源都是标准 L.tileLayer；保留工厂签名以免大屏/司机端两处调用点跟着改。
 */
export function makeTileLayer(url, opts) {
  return L.tileLayer(url, opts);
}
