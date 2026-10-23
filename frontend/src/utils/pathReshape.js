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
  const n = coords.length
  // 先把每个点投影成容器像素各一次，存进 Float64Array：原实现在 DP 递归里对同一个点
  // 会随其所属活动区间被反复 project()（典型 O(n·log n)、退化可到 O(n²) 次），且每次新建
  // 一个 {x,y} 对象。投影在同一 map 状态下确定、且下面所有算术只依赖 x/y，故缓存后
  // 逐点结果与旧写法完全一致（同公式、同遍历序、同并列取点），但投影次数降到恰好 n 次。
  const px = new Float64Array(n)
  const py = new Float64Array(n)
  for (let i = 0; i < n; i++) {
    const p = project(coords[i])
    px[i] = p.x
    py[i] = p.y
  }
  const keep = new Uint8Array(n)
  keep[0] = 1
  keep[n - 1] = 1
  const stack = [[0, n - 1]]
  while (stack.length) {
    const [a, b] = stack.pop()
    if (b - a < 2) continue
    const pax = px[a], pay = py[a], pbx = px[b], pby = py[b]
    const dx = pbx - pax, dy = pby - pay
    const norm = Math.hypot(dx, dy) || 1
    const norm2 = norm * norm
    let maxD = -1, maxI = -1
    for (let i = a + 1; i < b; i++) {
      const cx = px[i], cy = py[i]
      // 点到线段距离
      const t = Math.max(0, Math.min(1, ((cx - pax) * dx + (cy - pay) * dy) / norm2))
      const fx = pax + t * dx, fy = pay + t * dy
      const d = Math.hypot(cx - fx, cy - fy)
      if (d > maxD) { maxD = d; maxI = i }
    }
    if (maxD > tol && maxI > 0) {
      keep[maxI] = 1
      stack.push([a, maxI], [maxI, b])
    }
  }
  const out = []
  for (let i = 0; i < n; i++) if (keep[i]) out.push(coords[i])
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
 * 顶点转角（度）：0=直行，180=原路折返。用局部平面（经度×cos 纬度）算向量夹角，
 * 与地图投影无关，任何缩放级别下结果稳定。
 */
function turnAngleDeg(a, b, c) {
  const cosLat = Math.cos(b[0] * Math.PI / 180)
  const x1 = (b[1] - a[1]) * cosLat, y1 = b[0] - a[0]
  const x2 = (c[1] - b[1]) * cosLat, y2 = c[0] - b[0]
  const l1 = Math.hypot(x1, y1), l2 = Math.hypot(x2, y2)
  // 任一侧段近乎零长（重复点）不判定为折返，避免噪声误删
  if (l1 < 1e-7 || l2 < 1e-7) return 0
  let dot = (x1 * x2 + y1 * y2) / (l1 * l2)
  dot = Math.max(-1, Math.min(1, dot))
  return Math.acos(dot) * 180 / Math.PI
}

/**
 * 折返尖刺消除：删掉「走进去又原路退回来」的尖角顶点。
 *
 * 真凶（对照用户「缩小全览没问题、放到最大才斜」的现象）：OSM 路网里个别路口节点
 * 被画在路面之外（如金湖广场附近节点 3973027877，离前后两个路面点约 130m），
 * Dijkstra 忠实穿过它 → 折线在这里「戳出去 130m 再折回来」，转角 ~175°。
 * 远景时 Douglas-Peucker 把这个尖刺当冗余点抽掉（所以全览正常）；
 * 近景 z15 阈值仅 1.5px，尖刺偏离远超阈值被判定为必须保留 → 放到最大才现形。
 *
 * 判据只用「转角 > 150°」：真实道路/路口/立交的转角都远小于此，只有数据尖刺才会
 * 出现近乎原路折返（≥150°）的顶点，故据此删除不会误伤真实拐弯。用链表单趟处理，
 * 能顺带收敛「连续多个尖点」成串的情况（删一个后前驱更新，下一个接着判）。保护首尾点。
 */
export function collapseSpikes(coords, maxTurnDeg = 150) {
  if (!coords || coords.length < 3) return coords || []
  const n = coords.length
  const prev = new Int32Array(n)
  const next = new Int32Array(n)
  const dead = new Uint8Array(n)
  for (let i = 0; i < n; i++) { prev[i] = i - 1; next[i] = i + 1 }
  next[n - 1] = -1
  // 多趟收敛：删掉一个尖点后，可能让「已扫过的」相邻点暴露成新的折返（成串尖刺），
  // 单趟前向扫会漏掉这种「回头暴露」的尖点，故重复整趟直到不再有删除为止（尖刺稀少，几趟即稳）。
  // dead 标记已删节点：被删节点的 prev/next 仍指向旧邻，若不跳过会被反复重新判定为尖刺，
  // 使 changed 恒真而死循环。
  let changed = true
  while (changed) {
    changed = false
    for (let i = 1; i < n - 1; i++) {
      if (dead[i]) continue
      const p = prev[i], nx = next[i]
      if (p < 0 || nx < 0) continue
      if (turnAngleDeg(coords[p], coords[i], coords[nx]) > maxTurnDeg) {
        next[p] = nx; prev[nx] = p; dead[i] = 1; changed = true
      }
    }
  }
  const out = []
  for (let i = 0; i >= 0 && i < n; i = next[i]) out.push(coords[i])
  return out
}

/**
 * 往返回溯尖刺消除：删掉「从某点出发、绕出去一段、又回到该点附近」的成串顶点。
 *
 * 为什么需要它（collapseSpikes 的盲区）：collapseSpikes 只删转角 >150° 的近折返点，
 * 但路网里错位的路口节点造成的尖刺往往是「出去 200m 再折回」的一小段往返，
 * 单个顶点的转角只有 79°~92°（远不到 150°），collapseSpikes 放它过关，
 * 而 z18 的 DP 阈值仅 1.5px 更会原样保留 → 放到最大就看见线在转弯处戳出去一个箭头再折回来。
 *
 * 判据用「自邻近回归」而非转角：最短路径不该重访同一位置，故只要路径在离开 A 后
 * 又回到距 A 很近（≤radiusM）的点 B，且中间确实绕出去过（离 A 最远 ≥excursionM），
 * 这段 A..B 之间的顶点就是数据往返毛刺，删掉、直接从 A 接到 B。
 * 真实路口拐角（如金湖南路↔汇春路）离开后不会回到先前经过的点，故不受影响、完整保留。
 */
export function removeBacktrackSpikes(coords, radiusM = 45, excursionM = 45, window = 8) {
  if (!coords || coords.length < 3) return coords || []
  const n = coords.length
  const cosLat = new Float64Array(n)
  for (let i = 0; i < n; i++) cosLat[i] = Math.cos(coords[i][0] * Math.PI / 180)
  // 平面近似米制：dx 按各点纬度缩放，短程内误差可忽略
  const distM = (i, j) => {
    const cl = (cosLat[i] + cosLat[j]) / 2
    const dx = (coords[j][1] - coords[i][1]) * cl * 111320
    const dy = (coords[j][0] - coords[i][0]) * 110540
    return Math.hypot(dx, dy)
  }
  const out = [coords[0]]
  let i = 0
  while (i < n - 1) {
    let hit = -1
    for (let k = i + 2; k <= Math.min(i + window, n - 1); k++) {
      if (distM(i, k) > radiusM) continue
      // 确认中间真的「绕出去过」：任一中间点离 A 超过 excursionM 才算往返毛刺，
      // 否则只是几个近乎重合的抖动点（删不删都看不见），不必动，避免误伤密集真实几何
      let maxOut = 0
      for (let j = i + 1; j < k; j++) maxOut = Math.max(maxOut, distM(i, j))
      if (maxOut >= excursionM) { hit = k; break }
    }
    if (hit > 0) {
      i = hit
      out.push(coords[i]) // 回到 A 附近的那个点收尾，跳过中间绕出去的毛刺
    } else {
      i++
      out.push(coords[i])
    }
  }
  return out
}

/**
 * 综合：折返尖刺消除 + 按 zoom 插值 + 抽稀。
 * 默认 pipeline：先删数据尖刺（否则近景 DP 会把尖刺当细节保留，放大才斜），
 * 再插值（在原坐标序列上加密）后抽稀（去掉太密的冗余点），
 * 输出点数既"够密以贴合公路"又不至于卡浏览器。
 */
export function reshapeForZoom(coords, zoom, map) {
  if (!coords || coords.length < 2) return coords || []
  const back = removeBacktrackSpikes(coords)
  const clean = collapseSpikes(back)
  const dense = densifyGeodesic(clean, zoom, map)
  return simplifyPath(dense, zoom, map)
}