/**
 * 语音抢占优先级实测：灾害播报必须能打断「导航开始」的独占播报，且补读不重复。
 *
 * 做法：把 window.speechSynthesis 换成记录器（不发声、只按顺序登记每句文本），
 * 直接驱动组件方法复现真实时序 —— 点开始导航触发出发播报（force+lock），
 * 出发播报还在念的时候让灾害到达（SSE 入口 _notifyHazard / 绕行入口 announceHazard）。
 *
 * 断言：
 *  A) 出发播报独占期间，普通（非 force）播报被丢弃 —— 原有保护没被拆掉；
 *  B) 灾害播报能在出发播报念到一半时插进来（灾害句出现在出发句之后）；
 *  C) 补读内容 == 打断那一刻登记在册的「还没完整念到的那几段」（精确比对，不是大概像）；
 *  D) 已经念完的段落不再重复：打断得越晚，补读段数越少；
 *  E) announceHazard（绕行灾害播报）同样能抢占。
 *  出发播报的开场句会被 _expandToSentences 按「，」拆成多条短句，所以一切定位都按片段做。
 * usage: node tools/_voice_preempt_probe.mjs   （需后端已在 8080 上跑着）
 */
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const PORT = 9375;
const APP = 'http://localhost:8080/driver.html?voicedebug=1';
const bin = ['C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'].find(existsSync);

const proc = spawn(bin, [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--no-first-run', '--disable-extensions',
  `--user-data-dir=${process.env.TEMP}\\voip-${Date.now()}`, '--window-size=520,900', 'about:blank',
], { stdio: 'ignore' });

let ws, mid = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++mid; pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const ev = (expression) => send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  .then(r => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails.exception)); return r.result.value; });

// 轮询直到播报记录不再增长（最多 maxMs），返回最终文本序列
const DRAIN = (maxMs) => `(async () => {
  const t0 = Date.now(); let last = -1, stable = 0;
  while (Date.now() - t0 < ${maxMs}) {
    const n = window.__spoken.length;
    if (n === last) { if (++stable > 6) break } else { last = n; stable = 0 }
    await new Promise(r => setTimeout(r, 120));
  }
  return window.__spoken.slice();
})()`;

const SETUP = `(() => {
  const el = document.querySelector('#app');
  if (!el) return 'NO-APP-EL';
  // 生产构建不往容器上挂 __vue_app__（只有 devtools 特性开着才挂），但渲染器会无条件写
  // container._vnode，根组件实例就在它的 component 上
  const app = el.__vue_app || el.__vue_app__;
  let comp = (app && app._instance) || (el._vnode && el._vnode.component);
  if (!comp && el._vnode) {
    let n = el._vnode
    while (n && !n.component) n = (n.children && n.children[0]) || null
    if (n) comp = n.component
  }
  if (!comp || !comp.proxy) {
    return 'NO-VM diag title=' + document.title + ' hasVnode=' + !!el._vnode
      + ' keys=' + Object.keys(el).join(',') + ' bodyLen=' + (document.body.innerText || '').length
      + ' fatal=' + !!document.getElementById('fatal-error-bar')
      + ' err=' + (document.getElementById('fatal-error-bar') ? document.getElementById('fatal-error-bar').textContent.slice(0, 200) : '')
  }
  const vm = comp.proxy;
  window.__vm = vm;
  window.__spoken = [];
  // 假的 Web Speech 引擎：只按顺序登记文本，onstart/onend 快速回，让播报链正常推进
  const fake = {
    speaking: false, pending: false, paused: false,
    getVoices: () => [{ lang: 'zh-CN', name: 'fake' }, { lang: 'vi-VN', name: 'fake-vi' }],
    addEventListener() {}, removeEventListener() {},
    cancel() { this.speaking = false; this.pending = false; },
    speak(u) {
      window.__spoken.push(String(u.text || ''));
      this.speaking = true; this.pending = false;
      setTimeout(() => { try { u.onstart && u.onstart(); } catch (e) {} }, 20);
      setTimeout(() => { this.speaking = false; try { u.onend && u.onend(); } catch (e) {} }, 100);
    }
  };
  // window.speechSynthesis 在 Window.prototype 上是只读 getter，直接赋值会静默失效（sloppy mode），
  // 必须用 defineProperty 覆盖，否则探针读到的是浏览器真引擎（headless 下无语音→走原生兜底）
  Object.defineProperty(window, 'speechSynthesis', { value: fake, configurable: true, writable: true });
  // 最小可用行程数据：出发播报与灾害播报都只依赖 route/起终点/voiceOn
  vm.voiceOn = true;
  vm.navigating = true;
  vm.originId = 'NN';
  vm.destinationId = 'HP';
  vm.route = {
    estimatedHours: 6, totalDistanceKm: 270.5, extraHours: 1.5, cargoLossYuan: 0,
    pathEdgeIds: ['1', '2'], pathNodeIds: ['NN', 'LS', 'HP'], baselinePathNodeIds: ['NN', 'LS', 'HP'],
    pathCoords: [[22.8, 108.3], [21.8, 106.6]], riskSegments: [], estimatedArrival: ''
  };
  vm._hazardVoiceKey = ''; vm._hazardVoiceAt = 0; vm._hazardNotifyKey = '';
  vm.rerouteOptions = [];
  return 'OK';
})()`;

// 出发播报起播 + 独占期间塞一条普通播报（应被出发锁丢弃）
const ROUND = () => `(() => {
  window.__spoken.length = 0;
  window.__expect = null;
  const vm = window.__vm;
  vm._announceDeparture();
  const duringDeparture = !!vm._voiceLocked;
  vm.speakQueue(['普通轮询噪音播报应当被出发锁丢弃']);
  return { duringDeparture, chunks: vm._departureIntroChunks.slice() };
})()`;

// 等 waitMs（出发播报正念到第几段由它决定），记下此刻的待补读段，然后驱动灾害到达
const FIRE = (waitMs, call) => `(async () => {
  await new Promise(r => setTimeout(r, ${waitMs}));
  const vm = window.__vm;
  window.__expect = vm._departureIntroChunks.slice();
  ${call}
  return 1;
})()`;

const log = [];
try {
  for (let i = 0; i < 60; i++) { try { await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; } catch { await sleep(400); } }
  const tab = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise(r => { ws.onopen = r; });
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); }
  };
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Page.navigate', { url: APP });
  await sleep(16000);

  const results = [];
  const rounds = [
    // 打断得晚：开场前两段已完整念过，补读必须从「全程约…」起，不能再念「导航开始」
    { name: 'notifyHazard(SSE 灾害入口) 打断于第 3 段', wait: 1200,
      call: `vm._notifyHazard({ hazardEdgeIds: ['9001'], risks: [] }, [{ edgeId: '9001', reason: '友谊关路段暴雨红色预警，已触发通行熔断' }])` },
    // 打断得早：第一段「导航开始，」正念到一半就被掐，重读它是设计使然（半句不能缺字）
    { name: 'announceHazard(绕行灾害) 打断于第 1 段', wait: 260,
      call: `vm.announceHazard('谅山路段台风暴雨', 0)` },
  ];

  for (const r of rounds) {
    const setup = await ev(SETUP);
    if (setup !== 'OK') { log.push('SETUP 失败：' + setup); break }
    log.push('=== ' + r.name + ' ===');
    const a = await ev(ROUND());
    log.push('depart 起播 locked=' + a.duringDeparture + ' 登记待补读段(' + a.chunks.length + ')=' + a.chunks.join('/'));
    const fired = await ev(FIRE(r.wait, r.call));
    if (!fired) break
    const expect = await ev(`window.__expect`);
    const spoken = await ev(DRAIN(14000));
    log.push('voiceDebug 末条：' + await ev(`window.__vm.voiceDebug || '-'`));
    log.push('打断时刻待补读段(' + expect.length + ')=' + expect.join('/'));
    log.push('spoken(' + spoken.length + '):');
    spoken.forEach((t, i) => log.push('  [' + i + '] ' + t.slice(0, 42)));

    const idxHaz = spoken.findIndex(t => /气象预警|紧急提示/.test(t));
    const idxNoise = spoken.findIndex(t => t.indexOf('普通轮询噪音') >= 0);
    const depIdx = spoken.map((t, i) => (/^导航开始/.test(t) ? i : -1)).filter(i => i >= 0);
    const idxKmAfter = spoken.findIndex((t, i) => i > idxHaz && /全程约/.test(t));
    // 补读段必须正好是打断时刻登记的那几段，且排在全部灾害内容之后
    const tail = spoken.slice(-expect.length);
    const tailExact = expect.length > 0 && JSON.stringify(tail) === JSON.stringify(expect);
    const tailStart = spoken.length - expect.length;
    const afterHaz = idxHaz >= 0 && tailStart > idxHaz;
    // 已完整念过的段不重复：打断越晚，「导航开始」只应出现一次（第一次那句被念完了）
    const noRepeat = depIdx.length === (expect[0] && /^导航开始/.test(expect[0]) ? 2 : 1)
    const ok = a.duringDeparture && idxNoise < 0 && depIdx[0] === 0 && idxHaz > 0
      && tailExact && afterHaz && noRepeat
    log.push('CHK 出发先起播=' + (depIdx[0] === 0) + ' 灾害抢占=' + (idxHaz > 0)
      + ' 补读=打断时剩余段' + tailExact + ' 补读在灾害后=' + afterHaz
      + ' 普通播报未出声=' + (idxNoise < 0)
      + ' 导航开始出现' + depIdx.length + '次(不重复=' + noRepeat + ')'
      + ' 里程句=' + (idxKmAfter >= 0 ? '在[' + idxKmAfter + ']' : '缺失'))
    results.push(ok);
    log.push('');
    await sleep(1200);
  }

  const pass = results.length === rounds.length && results.every(Boolean);
  log.push('RESULT ' + (pass ? 'PASS' : 'FAIL') + ' rounds=' + results.join(','));
  await send('Page.captureScreenshot', { format: 'jpeg', quality: 60 })
    .then(x => writeFileSync('tools/_voice_preempt_probe.jpg', Buffer.from(x.data, 'base64'), 'base64'));
} catch (e) {
  log.push('probe error: ' + e.message);
} finally {
  writeFileSync('tools/_voice_preempt_probe.out', log.join('\n') + '\n', 'utf8');
  try { ws && ws.close(); } catch {}
  proc.kill('SIGTERM');
}
console.log('done -> tools/_voice_preempt_probe.out');
