import { createApp } from 'vue'
// Leaflet 销毁/缩放竞态守卫：入口顶层先打补丁，再加载任何地图组件（同 src/main.js）
import '../utils/leafletCanvasGuard.js'
import DriverApp from './DriverApp.vue'
// 轻界面模式：与大屏共用 localStorage 里的开关，挂载前先落地（见 utils/perfMode.js）
import { initPerfMode } from '../utils/perfMode.js'

initPerfMode()

const app = createApp(DriverApp)

/**
 * 屏上错误上报（排障用）。
 *
 * 白屏时 Vue 自己已经渲染不出来，任何"渲染在应用内"的报错提示都会跟着消失，
 * 只能靠猜。这里用**原生 DOM 直接挂到 body**，不经过 Vue —— 应用崩了它照样显示。
 * 只在真出错时出现，正常演示不会看到。
 */
function showFatalError(label, err) {
  try {
    let bar = document.getElementById('fatal-error-bar')
    if (!bar) {
      bar = document.createElement('div')
      bar.id = 'fatal-error-bar'
      bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;'
        + 'background:#b91c1c;color:#fff;font:12px/1.5 ui-monospace,Consolas,monospace;'
        + 'padding:8px 10px;white-space:pre-wrap;word-break:break-all;max-height:45vh;overflow:auto'
      document.body.appendChild(bar)
    }
    const detail = err && (err.stack || err.message) ? (err.stack || err.message) : String(err)
    const line = '[' + label + '] ' + detail
    bar.textContent = (bar.textContent ? bar.textContent + '\n' : '') + line
    console.error('[fatal]', label, err)
  } catch (e) { /* 兜底展示失败就放弃，不影响应用 */ }
}

app.config.errorHandler = (err, instance, info) => showFatalError('vue:' + info, err)
window.addEventListener('error', (e) => showFatalError('window', e.error || e.message))
window.addEventListener('unhandledrejection', (e) => showFatalError('promise', e.reason))

app.mount('#app')

/**
 * 加载动画至少展示 MIN_BOOT_MS 再淡出移除（或点右上角「跳过」立即移除）。
 * 加载层已从 #app 挪到外面，Vue 挂载不会再替换它，这里手动控制移除时机：
 * performance.now() 以页面开始加载为 0，补足到 5s，本地/缓存命中时
 * Vue 秒挂载也不会让「致力·致宁」一闪而过。
 */
const MIN_BOOT_MS = 5000
let _bootDismissed = false
function dismissBootVeil() {
  if (_bootDismissed) return
  _bootDismissed = true
  const veil = document.getElementById('boot-veil')
  if (!veil) return
  veil.classList.add('boot-hide')
  setTimeout(() => { if (veil.parentNode) veil.parentNode.removeChild(veil) }, 520)
}
const _bootElapsed = typeof performance !== 'undefined' && performance.now ? performance.now() : 0
const _bootRemainMs = Math.max(0, MIN_BOOT_MS - _bootElapsed)
setTimeout(dismissBootVeil, _bootRemainMs)

// 跳过按钮：倒计时数字随剩余时间刷新，点击则提前淡出
const _skipBtn = document.getElementById('boot-skip')
if (_skipBtn) {
  const _numEl = _skipBtn.querySelector('b')
  if (_numEl) {
    const _tick = () => {
      const left = Math.max(0, Math.ceil((_bootRemainMs - (performance.now() - _bootElapsed)) / 1000))
      _numEl.textContent = String(left)
      return left > 0
    }
    _tick()
    const _timer = setInterval(() => { if (!_tick()) clearInterval(_timer) }, 250)
  }
  _skipBtn.addEventListener('click', dismissBootVeil)
}

// 注册 Service Worker 以支持离线（拔网线）演示。
// 仅在生产构建注册：开发环境的 HMR 模块与 SW 缓存会冲突；
// 且 SW 需要安全上下文（HTTPS / localhost / capacitor://），手机以 http://IP 访问时不会注册，
// 打包成 APK 后为安全上下文，离线能力自动生效。
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // 注册失败（如非安全上下文）不影响应用正常使用
    })
  })
}
