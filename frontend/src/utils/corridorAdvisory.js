/**
 * 全域受阻建议（corridor advisory）的共用口径。
 *
 * 后端 CorridorAdvisoryService 只在「公路全部走廊已封闭 且 平陆运河禁航」时下发 advisory：
 * 此时算路引擎给出的任何"向前"路线，都是把已封闭路段降级放行换来的，
 * 等于从一个风险区换进另一个风险区——建议改为返回起点 / 就近安全停靠 / 暂缓发车。
 *
 * 判据本身一律不在前端重算（两端各判一套就会回到「AI 说等、算路却放行」的老问题），
 * 这里只做两件事：
 *   1) 按调用方持有的进度复算动作（SSE 推送时后端不知道车在哪，只会给 DEFER）；
 *   2) 动作与后端不一致时，用后端下发的字段拼出与局势相符的短句。
 */

export const ADVISORY_CODE = 'CORRIDOR_ALL_BLOCKED'

/** advisory 是否成立（旧后端 / 计算失败时为空，绝不报错阻断导航） */
export function hasAdvisory(a) {
  return !!(a && a.code === ADVISORY_CODE)
}

/**
 * 按进度定动作：与后端同一套判据。
 * crossRatio 是出境点在全程里程中的位置，进度超过它 = 已出境（折返要重新通关，只能就地等）。
 */
export function resolveAction(advisory, progressRatio) {
  if (!hasAdvisory(advisory)) return ''
  const p = Number(progressRatio)
  if (!(p >= 0)) return 'DEFER'
  const cross = Number(advisory.customsCrossRatio)
  if (cross >= 0 && p > cross) return 'HOLD'
  return 'RETURN'
}

function fmtHours(h) {
  const n = Number(h)
  if (!Number.isFinite(n) || n <= 0) return ''
  return (Number.isInteger(n) ? n : n.toFixed(1)) + '小时'
}

/**
 * 渲染视图：{action, title, text, waitText, blockedText}。
 * 动作与后端算出来的一致时，原样引用后端整句文案（不改写一个字，避免两端措辞漂移）；
 * 只有司机端用本地进度把 DEFER 纠正成 RETURN/HOLD 时，才按字段现拼一句。
 */
export function advisoryView(advisory, progressRatio) {
  if (!hasAdvisory(advisory)) return null
  const action = resolveAction(advisory, progressRatio)
  const waitText = fmtHours(advisory.waitHours) ? `约 ${fmtHours(advisory.waitHours)} 后复判` : ''
  const corridors = (advisory.blockedCorridors || []).filter(Boolean)
  const blockedText = advisory.detail
    || ((corridors.length ? corridors.join('、') + ' 已封闭' : '全部出境走廊已封闭')
      + (advisory.waterReason ? `，平陆运河禁航（${advisory.waterReason}）` : '，平陆运河禁航'))
  if (String(advisory.action || '') === action && advisory.advice) {
    return { action, title: advisory.title || '全线受阻', text: advisory.advice, waitText, blockedText }
  }
  const origin = advisory.originName || '起点'
  let title
  let text
  if (action === 'RETURN') {
    const leg = advisory.returnDistanceKm != null
      ? `（折返约 ${Math.round(advisory.returnDistanceKm)}km`
        + `${advisory.returnHours != null ? ' / ' + advisory.returnHours + 'h' : ''}）`
      : ''
    title = `全线受阻：建议返回${origin}`
    text = `${blockedText}。现在继续向前等于从一个大风险区换进另一个，建议沿当前路线折返${origin}${leg}，货物回库待命`
      + (waitText ? `，${waitText}` : '') + '。'
  } else if (action === 'HOLD') {
    title = '已出境且全线受阻：建议就近安全停靠等待'
    text = `${blockedText}。车辆已越过口岸进入越方一侧，折返需重新通关、不经济；`
      + '建议就近安全停靠、开启双闪，人员留在车内'
      + (waitText ? `，${waitText}` : '') + '，请勿强行穿越已封闭路段。'
  } else {
    title = '全线受阻：建议暂缓发车'
    text = `${blockedText}。现在发车等于从一个大风险区换进另一个，`
      + `建议车辆留在物流公司/${origin}待命` + (waitText ? `，${waitText}` : '') + '。'
  }
  return { action, title, text, waitText, blockedText }
}

/** 动作的短标签（用于徽标 / 播报开头），三态各一个 */
export const ACTION_SHORT = {
  DEFER: '暂缓发车',
  RETURN: '返回起点',
  HOLD: '就地停靠等待'
}

/** 语音播报文本：只留一句结论，长说明交给卡片正文（TTS 念长句会被引擎截断） */
export function advisorySpeakText(view, originName) {
  if (!view) return ''
  const o = originName || '起点'
  if (view.action === 'RETURN') return `全线受阻，前方所有出境走廊与平陆运河均已封闭。建议沿当前路线返回${o}，等待灾害解除。`
  if (view.action === 'HOLD') return '已出境且全线受阻。建议就近安全停靠，开启双闪，留在车内等待解除，请勿强行穿越封闭路段。'
  return '全线受阻，公路与运河均不可通行。建议暂缓发车，留在物流公司等待解除。'
}
