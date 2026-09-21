// 在线底图源定义 + 天地图密钥（单一事实来源，大屏 MapView 与司机端 DriverApp 共用）。
// 密钥从 Vite 环境变量注入：在 frontend/.env.local 配置 VITE_TIANDITU_TK=xxx，
// 不硬编码进源码；缺失时天地图瓦片会返回 403，调用方据此自动降级到 OSM / 离线矢量。
export const TIANDITU_TK = import.meta.env.VITE_TIANDITU_TK || '';

if (!TIANDITU_TK) {
  console.warn('[tileSources] 未检测到 VITE_TIANDITU_TK，天地图瓦片将返回 403，会自动降级到 OSM/离线矢量。请在 frontend/.env.local 配置密钥后重启 dev。');
}

// 在线底图（按降级顺序）：天地图影像(img_w)+注记(cia_w) → OSM。
// 两者都不可用时，才由调用方降级到本地离线矢量瓦片（MapLibre，/tiles/*.pbf）。
// DataServer 端点是标准 XYZ 风格，L.tileLayer 直接可用；不设 crossOrigin（天地图未必回 CORS 头）。
export const ONLINE_BASES = [
  {
    key: 'tianditu',
    name: '天地图',
    url: `https://t{s}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=${TIANDITU_TK}`,
    annoUrl: `https://t{s}.tianditu.gov.cn/DataServer?T=cia_w&x={x}&y={y}&l={z}&tk=${TIANDITU_TK}`,
    subdomains: '01234567',
    attribution: '&copy; 天地图',
    maxZoom: 18
  },
  {
    key: 'osm',
    name: 'OSM',
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    subdomains: 'abc',
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
    crossOrigin: true
  }
];

// 底图降级链：天地图(在线) → OSM(在线) → 本地离线矢量(MapLibre, /tiles/*.pbf)
export const BASE_CHAIN = ['tianditu', 'osm', 'vector'];

// 按 key 取在线源定义；'vector' 不在 ONLINE_BASES 内，返回 null（由调用方走 MapLibre 分支）。
export function baseDef(key) {
  return ONLINE_BASES.find(b => b.key === key) || null;
}
