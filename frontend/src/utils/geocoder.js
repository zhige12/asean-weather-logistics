import axios from 'axios'

/**
 * 地名地理编码前端工具（PC 大屏与手机端共用）：
 * - 地名库从后端 /api/geocode/list 拉一次缓存住（单一事实来源，后端 classpath place-names.json）；
 * - 输入先在前端本地库预匹配（命中零请求、离线演示也能用），未命中才走 GET /api/geocode
 *   （后端再查本地库 → 天地图在线地理编码，配额只花在冷门地名上）。
 */

let listCache = null
let listFetching = null

/** 全量地名库（下拉候选 + 本地预匹配），失败返回 [] 不抛 */
export async function fetchPlaceList() {
  if (listCache) return listCache
  if (!listFetching) {
    listFetching = axios.get('/api/geocode/list', { timeout: 8000 })
      .then(r => {
        listCache = (r.data && r.data.places) || []
        return listCache
      })
      .catch(e => {
        console.warn('地名库拉取失败，仅支持在线地理编码', e)
        listCache = []
        return listCache
      })
      .finally(() => { listFetching = null })
  }
  return listFetching
}

/** 去尾部行政区划后缀："南宁市"→"南宁"，与后端 GeocodeService 同规则 */
function stripSuffix(s) {
  const r = String(s).replace(/(壮族自治区|特别行政区|自治区|市|省|县|区|镇|乡)$/, '')
  return r || s
}

/** 本地库预匹配：精确 → 去后缀精确 → 双向包含（取名字最短命中）；未命中 null */
export function matchLocalPlace(list, q) {
  if (!q || !list || !list.length) return null
  const nq = stripSuffix(q.trim())
  let best = null
  for (const p of list) {
    const exact = p.name === q.trim() || stripSuffix(p.name) === nq
    const contains = p.name.includes(q.trim()) || q.trim().includes(p.name)
    if (!exact && !contains) continue
    const bestExact = best && (best.name === q.trim() || stripSuffix(best.name) === stripSuffix(q.trim()))
    if (!best || (exact && !bestExact) || (exact === !!bestExact && p.name.length < best.name.length)) best = p
    if (p.name === q.trim()) return p
  }
  return best
}

/**
 * 地名 → 坐标。优先本地库（source: local），未命中走后端在线通道（source: tianditu）。
 * @returns {name, lat, lng, source}
 * @throws 后端 400（查无此地名）时抛 Error(message)
 */
export async function geocodeName(name) {
  const list = await fetchPlaceList()
  const hit = matchLocalPlace(list, name)
  if (hit) return { name: hit.name, lat: hit.lat, lng: hit.lng, source: 'local' }
  const r = await axios.get('/api/geocode', { params: { name }, timeout: 12000 })
  return r.data
}

/** 取 axios 错误的可读消息（后端 GlobalExceptionHandler 统一 {message} 结构） */
export function apiErrorMessage(e, fallback) {
  return (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || fallback
}

/**
 * 坐标 → 最近路网节点（吸附）。手机端把地名编码成坐标后调此接口拿到 nodeId，
 * 再复用司机端既有的按节点算路流程。超 5km 覆盖范围外时后端返回 400（带可读消息）。
 * @returns {nodeId, nodeName, lat, lng, distanceKm}
 */
export async function snapCoords(lat, lng, label) {
  const r = await axios.get('/api/geocode/snap', {
    params: { lat, lng, label: label || '' }, timeout: 12000
  })
  return r.data
}

/**
 * 地名 → 路网节点 id（地理编码 + 吸附一步到位）。
 * 用于把用户输入的任意中文地名归一到司机端认识的 nodeId。
 * @returns {nodeId, nodeName, source}
 * @throws Error(可读消息) 查无此地名或超出路网覆盖范围
 */
export async function resolvePlaceToNode(name, label) {
  const g = await geocodeName(name)
  const s = await snapCoords(g.lat, g.lng, label || g.name)
  return { nodeId: s.nodeId, nodeName: s.nodeName, source: g.source }
}
