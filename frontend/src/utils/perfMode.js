/**
 * 轻界面模式（弱机专用开关）
 *
 * 为什么需要它：大屏的「毛玻璃 + 常亮特效」在独显上几乎免费，在集显 / 小内存机器上
 * 会让整页每帧重排重绘，表现就是**不只拖地图卡，点任何按钮都跟着卡** —— 输入事件排在
 * 已经饱和的绘制帧后面，要等几十到几百毫秒才被处理。
 *
 * 三处真正的开销来源（逐条核过代码，不是猜）：
 *   1. backdrop-filter: blur() —— .panel 是 360px × 满屏高、.app-header 是满屏宽。
 *      模糊结果只要背后有任何变化就必须整块重算，而它背后恰恰一直在动（见 2）。
 *   2. 无限动画动的是不能交给合成器的属性：.panel 的 prism-drift 动 background-position、
 *      prism-glow 动 box-shadow；.app-header::after 的 header-line 动 background-position；
 *      .app-root::before 是全屏 fixed 极光层。这些都是逐帧重绘。
 *   3. 路线特效层 .rf-arrow-pulse 动的是 filter: drop-shadow()，最多 16 个箭头 60fps 重算滤镜；
 *      同一批 SVG 上还挂着 rf-dash（stroke-dashoffset）。
 *
 * 关掉后只失去「流光 / 呼吸 / 毛玻璃」这类纯装饰，功能、数据、红绿分段、加载指示全部不变。
 *
 * 何时开启（评委/同事的机器好坏我们无法预知，所以三层判定）：
 *   1. 用户点过按钮 → 完全听用户的，写 localStorage，不再自动改；
 *   2. 没点过时先看静态信号（核心数 ≤2 / 设备内存 ≤4GB / 系统要求减少动效）；
 *   3. 仍判不出来就首屏约 5 秒后实测约 1.2 秒帧率，低于阈值自动降级（**不写** localStorage，
 *      避免一次偶发低帧把这台机器的观感永久锁死）。
 * 离线演示包会交给不知底细的评委机器跑，“自动变轻”比“好看”重要。
 */

const LS_KEY = 'aseanPerfLite';
const STYLE_ID = 'asean-perf-lite-style';
/**
 * 自动降级阈值（实测得出，不看配置猜）：
 *   AVG_FPS  —— 1 秒采样窗内的平均帧率下限
 *   MAX_GAP  —— 单帧最大间隔上限；一次长帧就是肉眼可见的“按下去没反应”
 * 取偏保守的值：宁可漏一台（用户还能手动点），不可误伤一台好看机器上的演示观感。
 */
const AVG_FPS = 40;
const MAX_GAP = 250;

const listeners = new Set();
let lite = false;
/** 当前开启是否由自检判定（而非用户手点）：影响按钮文案，且不写 localStorage */
let auto = false;
/** 自检实测结果，只供排障：在控制台敲 window.__perfLiteInfo() 就能看到本机被判成了什么 */
let lastSample = null;

/** 弱机信号：只靠硬件参数能抠出“肯定跑不动”的那批，剩下的交给帧率实测 */
function weakMachine() {
  try {
    const cores = navigator.hardwareConcurrency || 8;
    const mem = navigator.deviceMemory; // Chrome/Edge 才有，其他浏览器 undefined
    const reduceMotion = !!(window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    return reduceMotion || cores <= 2 || (typeof mem === 'number' && mem <= 4);
  } catch (e) {
    return false;
  }
}

function stored() {
  try {
    const v = localStorage.getItem(LS_KEY);
    return v === 'on' ? true : (v === 'off' ? false : null);
  } catch (e) { /* 无 localStorage（隐私模式 / WebView 限制）：走自动判断 */ }
  return null;
}

/**
 * 真实帧率采样：不猜配置，直接量。
 * 为什么不能只看硬件参数：同是 8 核 16G，独显和集显跑这套毛玻璃+逐帧重绘动画差出好几倍，
 * 而评委机到底是什么水平我们无从得知——只有它自己知道。
 */
function sampleFrames(windowMs) {
  return new Promise(resolve => {
    let frames = 0;
    let maxGap = 0;
    let prev = 0;
    const t0 = performance.now();
    const step = ts => {
      if (prev) maxGap = Math.max(maxGap, ts - prev);
      prev = ts;
      frames++;
      const elapsed = ts - t0;
      if (elapsed >= windowMs) {
        resolve({ fps: frames * 1000 / elapsed, maxGap });
      } else {
        requestAnimationFrame(step);
      }
    };
    requestAnimationFrame(step);
  });
}

const CSS = `
/* 1) 毛玻璃整体关掉：这是"点什么都没反应"的头号来源。
      backdrop-filter 是全局装饰，不影响任何信息量，可以一刀切 */
html[data-perf-lite="on"] *,
html[data-perf-lite="on"] *::before,
html[data-perf-lite="on"] *::after {
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/* 2) 去掉模糊后必须补回实底，否则半透明面板直接透出地图，字看不清 */
html[data-perf-lite="on"] .panel {
  background-color: #eef2f9 !important;
  background-image: none !important;
  box-shadow: none !important;
}
html[data-perf-lite="on"] .app-header,
html[data-perf-lite="on"] .card { background: #ffffff !important; }

/* 3) 停掉"逐帧重绘"的大面积动画。
      只点名这几个，不用 * { animation: none } —— 面板里的转圈加载指示要留着，
      它代表"后端在算"，且只有十几像素，成本可以忽略 */
html[data-perf-lite="on"] .app-root::before,
html[data-perf-lite="on"] .app-header::after { display: none !important; }
html[data-perf-lite="on"] .app-root > *,
html[data-perf-lite="on"] .panel,
html[data-perf-lite="on"] .btn.pick-on,
html[data-perf-lite="on"] .start-btn,
html[data-perf-lite="on"] .eta-sheen,
html[data-perf-lite="on"] .preview-start::after,
html[data-perf-lite="on"] .hazard-panel::before,
html[data-perf-lite="on"] .hazard-panel { animation: none !important; }

/* 4) 路线特效层兜底：MapView 拿到 lite 后本就不会加这些 class，
      这里再挡一道，防止切换瞬间旧图层还在逐帧重算 drop-shadow */
html[data-perf-lite="on"] .rf-outer-pulse,
html[data-perf-lite="on"] .rf-glow-anim,
html[data-perf-lite="on"] .rf-arrow-pulse,
html[data-perf-lite="on"] .rf-endpoint-pulse { animation: none !important; }
html[data-perf-lite="on"] .rf-arrow,
html[data-perf-lite="on"] .rf-chev { filter: none !important; }
`;

function apply() {
  const root = document.documentElement;
  const existing = document.getElementById(STYLE_ID);
  if (lite) {
    root.setAttribute('data-perf-lite', 'on');
    if (!existing) {
      const style = document.createElement('style');
      style.id = STYLE_ID;
      style.textContent = CSS;
      document.head.appendChild(style);
    }
  } else {
    root.removeAttribute('data-perf-lite');
    if (existing) existing.remove();
  }
}

/** 入口调用一次：决定初始状态并注入样式（在挂载任何组件之前） */
export function initPerfMode() {
  const saved = stored();
  if (saved === null) {
    lite = weakMachine();
    auto = lite;
  } else {
    lite = saved;
  }
  apply();
  // 硬件参数看不出显卡与负载，没被用户明确设定过、也没被静态判为弱机时，再量一次真实帧率
  if (saved === null && !lite) scheduleFpsProbe();
  return lite;
}

function scheduleFpsProbe() {
  const run = () => {
    // 延后 5 秒再量：太早的话瓦片与路线特效还没上屏，量的不是真负载；
    // 也不能太晚，否则评委已经卡了好几下才降级。5s 是“首屏画完 + 路线特效已建好”的分界。
    setTimeout(async () => {
      try {
        if (stored() !== null) return;                    // 期间用户手动选过就不越权
        if (document.visibilityState !== 'visible') return; // 后台标签 rAF 被节流，数据不可信
        const { fps, maxGap } = await sampleFrames(1200);
        lastSample = { fps: Math.round(fps), maxGap: Math.round(maxGap), weak: weakMachine() };
        if (document.visibilityState !== 'visible') return;
        if (stored() !== null) return;
        if (fps < AVG_FPS || maxGap > MAX_GAP) {
          auto = true;
          setLite(true, false);
        }
      } catch (e) { /* 量不了就当不卡，维持满特效 */ }
    }, 5000);
  };
  if (document.readyState === 'complete') run();
  else window.addEventListener('load', run, { once: true });
}

export function isLite() {
  return lite;
}

/** 当前轻界面是否由自检自动判定（供按钮文案区分“自动”与“手动”） */
export function isAutoLite() {
  return auto && lite;
}

/**
 * @param on      是否开启
 * @param persist 是否写入 localStorage；自动自检那条必须传 false，
 *                否则一次偶发低帧会把这台机器的观感永久锁死。
 */
export function setLite(on, persist = true) {
  const next = !!on;
  if (next !== lite) {
    lite = next;
    if (!persist) auto = true;
    else auto = false;
  } else if (persist) {
    auto = false;
  }
  if (persist) {
    try { localStorage.setItem(LS_KEY, lite ? 'on' : 'off'); } catch (e) { /* 存不下就用当场生效 */ }
  }
  apply();
  listeners.forEach(fn => { try { fn(lite); } catch (e) { /* 单个监听失败不影响其余 */ } });
  return lite;
}

export function toggleLite() {
  // 手动一点即接管：之后不再受自检影响（包括从“自动开启”切回去）
  auto = false;
  return setLite(!lite, true);
}

/** 订阅开关变化（MapView 用它重建路线特效层），返回取消订阅函数 */
export function onLiteChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** 自检内部暴露给调试用的快照 */
export function perfLiteInfo() {
  return { lite, auto, saved: stored(), sample: lastSample, thresholds: { avgFps: AVG_FPS, maxGap: MAX_GAP } };
}

// 排障钩子：同事/评委机器上到底被自动判成了什么，控制台敲一行就能看，不需要重新打包
try {
  window.__perfLiteInfo = perfLiteInfo;
} catch (e) { /* 拿不到 window 的环境（不会发生在此路径）就静默跳过 */ }
