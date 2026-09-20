/**
 * 路线按「风险 / 安全」分段。
 *
 * 后端下发的路径几何结构：
 *   pathCoords    —— 全路径点串 [[lat,lon],...]
 *   pathEdgeIds   —— 逐条边的 ID（与 pathCoords 不再一一对应，几何已加密）
 *   pathEdgeSpans —— 每条边对应 pathCoords 中的 [startIdx, endIdx] 区间
 *
 * 相邻的同类边会合并成一段，段与段共用衔接点，因此分段后折线不会出现断口。
 * 返回 [{ risk: Boolean, pts: [[lat,lon],...] }, ...]，顺序即行进顺序。
 */
export function splitRouteByRisk(rawCoords, edgeIds, edgeSpans, riskEdgeIds) {
  const cur = rawCoords || []
  const ids = edgeIds || []
  const spans = edgeSpans || []
  const riskSet = riskEdgeIds instanceof Set
    ? riskEdgeIds
    : new Set((riskEdgeIds || []).map(String))

  const groups = []
  let group = null
  for (let i = 0; i < ids.length; i++) {
    const sp = spans[i]
    const pts = []
    if (sp && sp.length === 2 && sp[1] >= sp[0]) {
      for (let k = sp[0]; k <= sp[1] && k < cur.length; k++) pts.push(cur[k])
    } else if (cur[i] && cur[i + 1]) {
      // 兜底：无区间信息时退化为两端点直连
      pts.push(cur[i], cur[i + 1])
    }
    if (!pts.length) continue
    const risk = riskSet.has(String(ids[i]))
    if (!group || group.risk !== risk) {
      group = { risk, pts: pts.slice() }
      groups.push(group)
    } else {
      // 同类相邻边：跳过与上段末点重复的衔接点，避免重复坐标
      const last = group.pts[group.pts.length - 1]
      const skip = last && pts[0] && last[0] === pts[0][0] && last[1] === pts[0][1] ? 1 : 0
      for (let k = skip; k < pts.length; k++) group.pts.push(pts[k])
    }
  }
  // 无逐段信息时整条按安全段处理
  if (!groups.length && cur.length) groups.push({ risk: false, pts: cur.slice() })
  return groups
}

/** 从风险段列表（后端 riskSegments）提取命中当前路径的边 ID 集合 */
export function riskEdgeSet(riskSegments, extraEdgeIds) {
  const s = new Set()
  ;(riskSegments || []).forEach(r => { if (r && r.edgeId) s.add(String(r.edgeId)) })
  ;(extraEdgeIds || []).forEach(id => { if (id) s.add(String(id)) })
  return s
}
