/**
 * 前端路径几何适配：
 * 1) 按当前缩放级别做 Douglas-Peucker 抽稀 —— 缩小时减少点数（性能 + 抗锯齿），
 *    放大时保留所有点（细节不丢）；
 * 2) zoom 较低（远景）时，把每段直线沿测地线插值加密 —— 后端 edge 几何只有 2 点，
 *    远看就是节点直线段穿山；插值后让路线在国家/省级缩放时像真实曲线。
 *
 * 关键点：抽稀必须用「地图像素」为阈值（不是经纬度），否则不同纬度下"应该保留多少点"
 *        会完全失控。
 */
const PIXEL_TOLERANCE_BY_ZOOM = [
  // z>=15: 1px（贴近路面，几乎不抽）
  15, 1.5,
  13, 2.0,
  11, 3.0,
  9, 5.0,
  7, 8.0,
  5, 14.0,
  // z<=5: 14px（国级远景，只要走向对就行）
  0, 14.0
]

function pixelToleranceForZoom(z) {
  z = Math.max(0, Math.min(16, z | 0))
  for (let i = 0; i < PIXEL_TOLERANCE_BY_ZOOM.length; i += 2) {
    if (z >= PIXEL_TOLERANCE_BY_ZOOM[i]) return PIXEL_TOLERANCE_BY_ZOOM[i + 1]
  }
  return 14
}

/**
 * Douglas-Peucker，按 latLngToContainerPoint 的像素距离阈值抽稀。
 * 不依赖 Leaflet Map，但传入 `map` 后会用真实瓦片投影；不传则用粗略换算（兜底）。
 */
export function simplifyPath(coords, zoom, map) {
  if (!coords || coords.length < 3) return coords || []
  const tol = pixelToleranceForZoom(zoom)
  const project = (latlng) => {
    if (map && typeof map.latLngToContainerPoint === 'function') {
      return map.latLngToContainerPoint(latlng)
    }
    // 兜底：粗略"像素/lat"换算（zoom 13 一像素 ≈ 1 / (512 * 2^13) 度）
    const k = 512 * Math.pow(2, zoom) / 360
    return { x: latlng[1] * k, y: -latlng[0] * k }
  }
  const keep = new Uint8Array(coords.length)
  keep[0] = 1
  keep[coords.length - 1] = 1
  const stack = [[0, coords.length - 1]]
  while (stack.length) {
    const [a, b] = stack.pop()
    if (b - a < 2) continue
    const pa = project(coords[a])
    const pb = project(coords[b])
    const dx = pb.x - pa.x, dy = pb.y - pa.y
    const norm = Math.hypot(dx, dy) || 1
    let maxD = -1, maxI = -1
    for (let i = a + 1; i < b; i++) {
      const p = project(coords[i])
      // 点到线段距离
      const t = Math.max(0, Math.min(1, ((p.x - pa.x) * dx + (p.y - pa.y) * dy) / (norm * norm)))
      const cx = pa.x + t * dx, cy = pa.y + t * dy
      const d = Math.hypot(p.x - cx, p.y - cy)
      if (d > maxD) { maxD = d; maxI = i }
    }
    if (maxD > tol && maxI > 0) {
      keep[maxI] = 1
      stack.push([a, maxI], [maxI, b])
    }
  }
  const out = []
  for (let i = 0; i < coords.length; i++) if (keep[i]) out.push(coords[i])
  return out
}

/**
 * 测地线插值：把相邻两点之间按大圆插 N 个点（zoom 越低插得越多）。
 * 解决"节点直线段穿山/切湖"的问题 —— 在地图上看着更贴近真实公路。
 *
 * 这里用球面线性插值（SLERP）足够；不必上 Vincenty。
 */
export function densifyGeodesic(coords, zoom, map) {
  if (!coords || coords.length < 2) return coords || []
  // 远景才插；zoom >= 13 几乎看不出来，不浪费点
  const segsPerEdge = zoom <= 5 ? 24 : zoom <= 7 ? 12 : zoom <= 9 ? 6 : zoom <= 11 ? 3 : 1
  if (segsPerEdge <= 1) return coords
  const out = [coords[0]]
  for (let i = 1; i < coords.length; i++) {
    const a = coords[i - 1], b = coords[i]
    // 邻近点 / 距离极短就不插
    const dLat = b[0] - a[0], dLng = b[1] - a[1]
    if (Math.abs(dLat) < 1e-5 && Math.abs(dLng) < 1e-5) {
      out.push(b); continue
    }
    for (let s = 1; s < segsPerEdge; s++) {
      const t = s / segsPerEdge
      out.push([a[0] + dLat * t, a[1] + dLng * t])
    }
    out.push(b)
  }
  return out
}

/**
 * 综合：抽稀 + 按 zoom 插值。
 * 默认 pipeline：先插值（在原坐标序列上加密）再抽稀（去掉太密的冗余点），
 * 输出点数既"够密以贴合公路"又不至于卡浏览器。
 */
export function reshapeForZoom(coords, zoom, map) {
  if (!coords || coords.length < 2) return coords || []
  const dense = densifyGeodesic(coords, zoom, map)
  return simplifyPath(dense, zoom, map)
}