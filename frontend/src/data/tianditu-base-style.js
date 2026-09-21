// 天地图影像底图的 MapLibre 样式（GPU 合成渲染，供司机端经 maplibre-gl-leaflet 桥接层挂载）。
//
// 与旧的 Leaflet L.tileLayer 直连天地图不同：这里瓦片一律走后端「同源代理」 /api/tianditu/...，
// 由后端带密钥 + 合规 Referer 回源，再回吐 CORS/缓存头。原因见 TiandituTileProxyController：
// 天地图在线瓦片无 CORS 头、且有防盗链 Referer 校验（错误来源返回 418），
// MapLibre 用 WebGL 上传栅格纹理时浏览器强制要求同源或 CORS，直连必然失败。
//
// 注意：MapLibre 在 Web Worker 里加载瓦片，Worker 没有文档基址，相对 URL 无法解析
// （new Request('/api/...') 抛 "Failed to parse URL"）→ 必须拼成绝对 URL（与 local-base-style 同理）。
import { TIANDITU_TK } from '../utils/tileSources.js'

const ORIGIN = (typeof location !== 'undefined' && location.origin) ? location.origin : ''
// tk 随查询串带给同源代理；后端优先用它，缺省再回落到配置。URL 模板里 {z}/{x}/{y} 由 MapLibre 替换。
const TK_SUFFIX = TIANDITU_TK ? `?tk=${encodeURIComponent(TIANDITU_TK)}` : ''

function tileUrl(layer) {
  return `${ORIGIN}/api/tianditu/${layer}/{z}/{x}/{y}.png${TK_SUFFIX}`
}

export default {
  version: 8,
  name: 'Tianditu GPU',
  sources: {
    // img_w：卫星影像底图
    'tdt-img': {
      type: 'raster',
      tiles: [tileUrl('img_w')],
      tileSize: 256,
      minzoom: 0,
      maxzoom: 18
    },
    // cia_w：影像注记（地名 / 边界，透明 PNG），叠在影像之上
    'tdt-cia': {
      type: 'raster',
      tiles: [tileUrl('cia_w')],
      tileSize: 256,
      minzoom: 0,
      maxzoom: 18
    }
  },
  layers: [
    { id: 'tdt-img-layer', type: 'raster', source: 'tdt-img', minzoom: 0, maxzoom: 18 },
    { id: 'tdt-cia-layer', type: 'raster', source: 'tdt-cia', minzoom: 0, maxzoom: 18 }
  ]
}
