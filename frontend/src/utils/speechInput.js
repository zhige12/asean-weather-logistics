/**
 * 语音输入（STT）统一通道：AI 志宁「按住说话」与司机端搜索框麦克风共用这一份实现。
 *
 * 通道选择（与播报侧 TTS 的「原生优先」策略对称）：
 * 1. Capacitor 原生容器内 → @capacitor-community/speech-recognition，走 Android/iOS 系统语音识别，
 *    可离线（取决于设备是否装了离线语言包），这是现场演示的主通道；
 * 2. 普通浏览器 → **服务端识别通道**：MediaRecorder 录音上传 /api/ai/speech，
 *    后端代理阿里云一句话识别（境内可达）。这是主力：浏览器自带的 Web Speech 把音频
 *    送到 Google 服务器识别，国内网络（现场 WiFi 与手机流量都一样）到不了它，
 *    权限给到位也只会报 network 错——手机端实测就是这样；
 * 3. 服务端未启用（没配凭证）且浏览器有 Web Speech → 仍退回 Web Speech 兜底
 *    （开发机挂了代理时能用）；
 * 4. 全都不可用 → 返回 null，界面上把按钮置灰并说明原因，不静默失败。
 *
 * 识别到的文本只是「一句话指令」的原料，交给谁执行由调用方决定（两端都发给 /api/copilot/chat 解析意图）。
 */

// 原生模块缓存：与 tts 侧 loadNativeTts() 同一套路，避免每次调用重复解析
let nativeModPromise = null
function loadNativeSpeech() {
  if (!nativeModPromise) {
    nativeModPromise = import('@capacitor-community/speech-recognition').catch(() => null)
  }
  return nativeModPromise
}

/** 是否运行在 Capacitor 原生容器内（普通浏览器里 isNativePlatform() 返回 false） */
export function isNativeShell() {
  const c = typeof window !== 'undefined' ? window.Capacitor : null
  return !!(c && typeof c.isNativePlatform === 'function' && c.isNativePlatform())
}

/** 浏览器是否提供 Web Speech 识别能力（iOS Safari 至今没有，要据实提示） */
export function hasWebSpeech() {
  if (typeof window === 'undefined') return false
  return !!(window.SpeechRecognition || window.webkitSpeechRecognition)
}

/**
 * 当前可用的识别通道：'native' / 'server' / 'web' / null。
 * null 表示这台设备/这个浏览器都做不了语音输入，调用方应把按钮禁用而不是点了没反应。
 */
export async function resolveSpeechChannel() {
  if (isNativeShell()) {
    const m = await loadNativeSpeech()
    if (m && m.SpeechRecognition) {
      try {
        const r = await m.SpeechRecognition.available()
        if (r && r.available) return 'native'
      } catch (e) {
        // 设备没有识别组件（部分国产 ROM 精简掉了）：继续往下试服务端，不直接判死
      }
    }
  }
  if (await speechServerEnabled()) return 'server'
  // 不再自动落到 Web Speech：Chrome 的 Web Speech 把音频送到 Google 识别，境内网络
  // （现场 WiFi、手机流量）基本不可达，点了只会报 network 错、看着像功能坏了。
  // 原生与服务端都不可用时干脆判死 → 上层按钮置灰，引导“请改用文字输入”而不是报错。
  // （开发机挂代理时想手动用 Web Speech 调试的，可以自己直接调 startListening）
  return null
}

/** 识别不可用时给用户的解释（按通道如实说明，不写"网络错误"这种万能套话） */
export function channelUnavailableText() {
  if (isNativeShell()) {
    return '这台设备没有可用的系统语音识别组件，请先在系统设置里安装语音数据（离线语言包）'
  }
  return '语音识别未启用（原生与服务端都不可用），请改用文字输入'
}

/** 把 Web Speech 的 error 码翻成人话：'network' 单独点出来，这是离线演示最常见的死法 */
function webErrorText(err) {
  switch (err) {
    case 'network': return '浏览器语音识别走的是在线服务，网络不通（离线演示时属正常，请改用司机端 App 内的原生语音）'
    case 'not-allowed':
    case 'service-not-allowed': return '麦克风权限被拒绝，请在浏览器地址栏把麦克风改为允许'
    case 'no-speech': return '没听清，请再说一遍'
    case 'audio-capture': return '没找到麦克风设备'
    case 'aborted': return '已取消'
    default: return '语音识别失败：' + (err || '未知原因')
  }
}

// 同一时刻只允许一个识别会话（原生 start() 与 Web Recognition 都不支持并发）
let session = null

/** 是否正在聆听：调用方用它做按钮态回显 */
export function isListening() {
  return !!session
}

/**
 * 开始一次聆听，返回最终识别文本。
 * @param {Object} opts
 * @param {string} [opts.lang] 识别语言：zh-CN / vi-VN（司机端切越南语界面时跟着切）
 * @param {(text:string)=>void} [opts.onInterim] 中间结果回调（边说边上屏，体验上像"实时字幕"）
 * @param {()=>void} [opts.onProcessing] 仅服务端通道：录音已停、正在上传识别时触发一次，
 *   用于把「按住说话」的 UI 切到「识别中…」——这条通道没有中间结果，不给出处理态
 *   就会让用户对着静止的录音界面干等，把「没反应」误读成「识别慢」
 * @param {()=>void} [opts.onEnd] 会话结束回调（无论成功/失败/取消都会调一次）
 * @returns {Promise<{text:string, channel:'native'|'web'}>}
 * @throws Error(message 为可读中文) 权限被拒 / 通道不可用 / 识别失败
 */
export function startListening({ lang = 'zh-CN', onInterim, onEnd, onProcessing } = {}) {
  if (session) return Promise.reject(new Error('上一次识别还没结束'))
  // 原生容器才碰插件：这个插件的 web 实现全是 unimplemented()，浏览器里调它会先抛一串
  // "not implemented" 再靠兜底绕回来，白绕一圈还得多拉一个 JS chunk。浏览器走服务端/ Web Speech。
  if (!isNativeShell()) {
    return (serverStatus === true
      ? Promise.resolve(true)
      : speechServerEnabled()).then((ok) =>
      ok ? startServer({ lang, onEnd, onProcessing }) : startWeb({ lang, onInterim, onEnd }))
  }
  return startNative({ lang, onInterim, onEnd }).catch((e) => {
    // 原生通道启动失败（插件在但系统识别组件异常等）才退回软件通道，能退就退一次
    if (e && e.__fallbackToWeb) {
      if (serverStatus === true) return startServer({ lang, onEnd, onProcessing })
      if (hasWebSpeech()) return startWeb({ lang, onInterim, onEnd })
    }
    throw e
  })
}

/** 主动结束当前会话（松开手指/再点一次/组件卸载时调用） */
export function stopListening() {
  const s = session
  if (!s) return
  try { s.cancel() } catch (e) { /* 已结束，忽略 */ }
}

// ---------------------------------------------------------------- 原生通道
/*
 * Android 侧的实测语义（读插件 SpeechRecognition.java 得到，不是猜的）：
 *  - partialResults:true 时 start() 会**立即 resolve(undefined)**（源码 if(partialResults) call.resolve()），
 *    识别内容只能从 partialResults 事件里拿，最终结果也走同一个事件；
 *  - 识别出错时插件是 call.resolve({status:'error'}) 而不是 reject，且此时 start() 早就 resolve 过了，
 *    所以错误只能表现成「一个结果都没有 + listeningState:stopped」，必须自己兜住；
 *  - 用户松手调 stop() 后，系统会派一次 listeningState:stopped —— 用它作为收口时机最准，
 *    但事件丢了（后台/省电模式）时得有宽限定时器兜底，不能把用户的白说当成静默失败。
 */
async function startNative({ lang, onInterim, onEnd }) {
  const m = await loadNativeSpeech()
  if (!m || !m.SpeechRecognition) {
    const err = new Error(channelUnavailableText())
    err.__fallbackToWeb = hasWebSpeech()
    throw err
  }
  const SR = m.SpeechRecognition
  let permission
  try {
    permission = await SR.requestPermissions()
  } catch (e) {
    const err = new Error('语音识别权限申请失败：' + ((e && e.message) || e))
    err.__fallbackToWeb = hasWebSpeech()
    throw err
  }
  if (permission && permission.speechRecognition && permission.speechRecognition !== 'granted') {
    throw new Error('麦克风/语音识别权限未授予，请在系统设置里允许本应用使用麦克风')
  }

  return new Promise((resolve, reject) => {
    let settled = false
    let lastText = ''
    let stopRequested = false
    const handles = []
    let graceTimer = null
    let hardTimer = null

    // 收口：有文本就成功，没文本如实报「没听清」，绝不编造识别结果
    const settle = () => {
      const text = String(lastText || '').trim()
      if (text) return finish(resolve, { text, channel: 'native' })
      finish(reject, new Error(stopRequested ? '已取消' : '没听清，请再说一遍'))
    }
    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      clearTimeout(graceTimer)
      clearTimeout(hardTimer)
      session = null
      handles.forEach(h => { try { h.remove && h.remove().catch(() => {}) } catch (e) { /* 忽略 */ } })
      if (onEnd) { try { onEnd() } catch (e) { /* 回显失败不影响结果 */ } }
      fn(arg)
    }

    // 最长聆听看门狗：手指一直按着不松 / 事件全丢时，12s 后主动收尾，不能无限挂着
    hardTimer = setTimeout(() => {
      if (settled) return
      SR.stop().catch(() => {})
      settle()
    }, 12000)

    session = {
      cancel: () => {
        stopRequested = true
        SR.stop().catch(() => {})
        // stop() 只是发指令，真正的结束信号是 listeningState:stopped；等它 1.5s，
        // 等不到就用手上已有的最新识别内容收口（宁可早点出字，也不要让用户白说）
        clearTimeout(graceTimer)
        graceTimer = setTimeout(settle, 1500)
      }
    }

    // 中间与最终结果都从这里来：data.matches 是候选数组，取第一条（置信度最高）
    SR.addListener('partialResults', (data) => {
      const matches = (data && data.matches) || []
      const best = matches.length ? String(matches[0]) : ''
      if (best) {
        lastText = best
        if (onInterim) onInterim(best)
      }
    }).then(h => handles.push(h)).catch(() => {})

    SR.addListener('listeningState', (data) => {
      if (data && data.status === 'stopped') {
        clearTimeout(hardTimer)
        clearTimeout(graceTimer)
        settle()
      }
    }).then(h => handles.push(h)).catch(() => {})

    SR.start({ language: lang, partialResults: true, popup: false })
      .then(res => {
        // popup=false + partialResults=true 时 res 正常是 undefined，继续等事件；
        // 个别设备仍会把结果直接 resolve 回来，那就当场收口，别白等
        const matches = res && res.matches
        if (matches && matches.length) {
          lastText = String(matches[0])
          if (onInterim) onInterim(lastText)
          settle()
        }
      })
      .catch(e => {
        const err = new Error('语音识别启动失败：' + ((e && e.message) || e))
        err.__fallbackToWeb = hasWebSpeech()
        finish(reject, err)
      })
  })
}

// ---------------------------------------------------------------- 服务端识别通道
/*
 * MediaRecorder 录音 → POST /api/ai/speech → 后端代理阿里云一句话识别。
 * 识别端在国内，不依赖 Google，手机浏览器也能用；代价是没有中间结果（松手后
 * 等一个往返才出字），onInterim 在这条通道不触发，调用方已容忍。
 */
let serverStatus = null // null 未知 / true 可用 / false 不可用（探测一次就缓存，失败可重试一次）
export async function speechServerEnabled() {
  if (serverStatus === true) return true
  try {
    const r = await fetch('/api/ai/speech/status')
    const j = await r.json()
    serverStatus = !!(j && j.enabled)
  } catch (e) {
    serverStatus = false
  }
  return serverStatus
}

function startServer({ lang, onEnd, onProcessing }) {
  if (typeof navigator === 'undefined' || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia
    || typeof MediaRecorder === 'undefined') {
    return Promise.reject(new Error('这个浏览器不支持录音（请用较新的 Chrome/Edge/Safari）'))
  }
  return new Promise((resolve, reject) => {
    let settled = false
    let stream = null
    let rec = null
    let hardTimer = null
    let userStop = false
    const chunks = []
    const startedAt = Date.now()

    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      clearTimeout(hardTimer)
      if (stream) stream.getTracks().forEach((t) => t.stop())
      session = null
      if (onEnd) { try { onEnd() } catch (e) { /* 回显失败不影响结果 */ } }
      fn(arg)
    }

    // 单声道 + 回声/噪声抑制 + 限低码率：默认 MediaRecorder 常按 48k 立体声 ~128kbps 编码，
    // 说一句就是 100~200KB，手机走公网 HTTPS 上传这一段能再花 0.5~1s+（实测服务器侧只有
    // 0.5~1.9s，体感“很久”的大头其实在上传）。压成单声道 opus ~24kbps 后体积降 ~1/5~1/10，
    // 语音识别精度不受影响（后端还要重采样成 16k 单声道 wav 再送 NLS）。不强制 sampleRate
    // 精确约束，避免部分设备 NotReadableError（用 ideal 提示即可）。
    const audioConstraints = {
      channelCount: { ideal: 1 },
      sampleRate: { ideal: 16000 },
      echoCancellation: true,
      noiseSuppression: true,
    }
    navigator.mediaDevices.getUserMedia({ audio: audioConstraints }).then((s) => {
      if (settled) { s.getTracks().forEach((t) => t.stop()); return }
      stream = s
      const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus']
        .find((m) => { try { return MediaRecorder.isTypeSupported(m) } catch (e) { return false } }) || ''
      const opts = {}
      if (mime) opts.mimeType = mime
      opts.audioBitsPerSecond = 24000   // 一句话指令够用，进一步压小上传体积
      rec = new MediaRecorder(s, Object.keys(opts).length ? opts : undefined)
      rec.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data) }
      rec.onstop = () => {
        // 手速太快（<0.4s）：不浪费一次上传，按「没听清」收口
        if (Date.now() - startedAt < 400) {
          finish(reject, new Error(userStop ? '已取消' : '没听清，请再说一遍'))
          return
        }
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' })
        const ext = blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm'
        // 录音已停、即将上传：这条通道没有中间结果，先给调用方一个「识别中」信号，
        // 否则松手到出字之间界面静止，用户以为卡住了（体感「识别很久」的真正来源）
        if (onProcessing) { try { onProcessing() } catch (e) { /* 反馈失败不影响识别 */ } }
        const fd = new FormData()
        fd.append('audio', blob, 'speech.' + ext)
        fd.append('lang', lang || 'zh-CN')
        fetch('/api/ai/speech', { method: 'POST', body: fd })
          .then((r) => r.json())
          .then((j) => {
            if (j && j.ok && j.text) finish(resolve, { text: String(j.text), channel: 'server' })
            else finish(reject, new Error((j && j.message) || '语音识别失败'))
          })
          .catch(() => finish(reject, new Error('录音上传失败，请检查网络')))
      }
      // 长按看门狗：说满 12 秒自动收口发送（与原生通道时长一致）
      hardTimer = setTimeout(() => { try { if (rec && rec.state !== 'inactive') rec.stop() } catch (e) { /* 忽略 */ } }, 12000)
      session = {
        cancel: () => {
          userStop = true
          try {
            if (rec && rec.state !== 'inactive') rec.stop()
            else finish(reject, new Error('已取消'))
          } catch (e) { finish(reject, new Error('已取消')) }
        }
      }
      rec.start()
    }).catch((e) => {
      const n = (e && e.name) || ''
      let msg
      if (n === 'NotAllowedError' || n === 'SecurityError') msg = '麦克风权限被拒绝，请在浏览器地址栏把麦克风改为允许'
      else if (n === 'NotFoundError' || n === 'OverconstrainedError') msg = '没找到麦克风设备'
      else msg = '无法打开麦克风：' + ((e && e.message) || n || e)
      finish(reject, new Error(msg))
    })
  })
}

// ---------------------------------------------------------------- Web 通道（兜底）

function startWeb({ lang, onInterim, onEnd }) {
  const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition
  if (!Ctor) return Promise.reject(new Error(channelUnavailableText()))

  return new Promise((resolve, reject) => {
    const rec = new Ctor()
    let settled = false
    let finalText = ''
    let stopped = false

    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      session = null
      if (onEnd) { try { onEnd() } catch (e) { /* 忽略 */ } }
      fn(arg)
    }

    rec.lang = lang
    rec.interimResults = true
    rec.continuous = false       // 一句指令就够，不开连续模式，避免迟迟不结束
    rec.maxAlternatives = 1

    session = {
      cancel: () => {
        stopped = true
        try { rec.stop() } catch (e) { /* 已停，忽略 */ }   // stop 而非 abort：让 onresult 收尾拿到文本
      }
    }

    rec.onresult = (ev) => {
      let interim = ''
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const r = ev.results[i]
        if (r.isFinal) finalText += r[0].transcript
        else interim += r[0].transcript
      }
      if (onInterim) onInterim((finalText + interim).trim())
    }
    rec.onerror = (ev) => {
      // 出错信息记下来，交给 onend 统一收口（Chrome 常在 error 之后仍要触发 end）
      rec.__err = ev.error
    }
    rec.onend = () => {
      clearTimeout(watchdog)
      const text = finalText.trim()
      if (text) { finish(resolve, { text, channel: 'web' }); return }
      if (stopped && !rec.__err) { finish(reject, new Error('已取消')); return }
      finish(reject, new Error(webErrorText(rec.__err || 'no-speech')))
    }

    // 兜底看门狗：个别浏览器既不触发 onend 也不触发 onerror，15s 后强制收口
    const watchdog = setTimeout(() => {
      try { rec.stop() } catch (e) { /* 忽略 */ }
      const text = finalText.trim()
      if (text) finish(resolve, { text, channel: 'web' })
      else finish(reject, new Error('语音识别超时，请重试'))
    }, 15000)

    try {
      rec.start()
    } catch (e) {
      clearTimeout(watchdog)
      finish(reject, new Error('无法启动语音识别：' + ((e && e.message) || e)))
    }
  })
}
