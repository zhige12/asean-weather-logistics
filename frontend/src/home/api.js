/**
 * 门户取数层：
 * - 业务数据只允许 GET，绝不触碰沙盘/注入类写接口（/sandbox/*、/weather/risk POST、/scenario/* 等）——
 *   首页定位是「看得到、可溯源」的信息门户，不是推演台。
 * - 例外只有两类且逐个命名：/api/auth/*（身份壳自身）与 /api/copilot/portal（门户 AI，
 *   服务端已收窄为导航/知识问答，不会执行沙盘写操作，见 PortalCopilotService）。
 * - 统一降级：网络失败 / 非 2xx / 拿到 HTML（Capacitor fallback）都归一成 {ok:false, error}，
 *   由界面如实显示「暂不可用」，不臆造数字。
 */
const BASE = ''

async function getJson(path, params) {
  let url = BASE + path
  if (params) {
    const q = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
      .join('&')
    if (q) url += (url.includes('?') ? '&' : '?') + q
  }
  try {
    const r = await fetch(url, { headers: { Accept: 'application/json' } })
    if (!r.ok) return { ok: false, error: 'HTTP ' + r.status, data: null }
    const ct = String(r.headers.get('content-type') || '')
    if (ct.includes('text/html')) return { ok: false, error: '返回了 HTML（服务未就绪）', data: null }
    return { ok: true, error: '', data: await r.json() }
  } catch (e) {
    return { ok: false, error: (e && e.message) || '网络错误', data: null }
  }
}

// POST 只给身份壳与门户 AI 用（同源带 Cookie，维持 HttpSession）
async function postJson(path, body) {
  try {
    const r = await fetch(BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body || {})
    })
    const ct = String(r.headers.get('content-type') || '')
    if (ct.includes('text/html')) return { ok: false, error: '返回了 HTML（服务未就绪）', data: null }
    const data = await r.json().catch(() => null)
    if (!r.ok) return { ok: false, error: (data && data.message) || 'HTTP ' + r.status, data }
    return { ok: true, error: '', data }
  } catch (e) {
    return { ok: false, error: (e && e.message) || '网络错误', data: null }
  }
}

// 端点清单（命名即声明用途，方便审计「首页没有推演写操作」）
export const api = {
  weatherSource: () => getJson('/api/weather/source'),
  weatherReal: () => getJson('/api/weather/real'),
  riskModel: () => getJson('/api/risk/model'),
  riskModelLive: (originId, destinationId) => getJson('/api/risk/model/live', { originId, destinationId }),
  waterwayStatus: () => getJson('/api/waterway/status'),
  tributariesLive: () => getJson('/api/canal/tributaries/live'),
  intermodal: () => getJson('/api/canal/intermodal'),
  corridor: () => getJson('/api/canal/corridor'),
  customsBulletin: () => getJson('/api/customs/bulletin'),
  customsEfficiency: () => getJson('/api/customs/efficiency'),
  // 身份壳（演示级：内存账号，重启归零）；登录/注册需先取图形验证码（同源 Cookie 将会话与答案绑定）
  authSession: () => getJson('/api/auth/session'),
  authCaptcha: () => getJson('/api/auth/captcha'),
  authLogin: (username, password, captcha) => postJson('/api/auth/login', { username, password, captcha }),
  authRegister: (username, password, captcha) => postJson('/api/auth/register', { username, password, captcha }),
  authLogout: () => postJson('/api/auth/logout', {}),
  // 门户 AI·快速导航（后端只出 open/navigate/knowledge/say 四类动作，不执行沙盘操作）
  portalChat: (message) => postJson('/api/copilot/portal', { message })
}

export const fmt = {
  num(v, digits = 1) {
    if (v === null || v === undefined || v === '' || isNaN(Number(v))) return '—'
    return Number(v).toFixed(digits).replace(/\.0+$/, '')
  },
  // 时间戳 → 本地时:分；0/空 → 「—」（这些接口未取到时是 0，不是 null）
  time(ts) {
    const n = Number(ts)
    if (!n || n <= 0) return '—'
    try { return new Date(n).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
    catch { return '—' }
  },
  dash(v) { return (v === null || v === undefined || v === '') ? '—' : String(v) }
}
