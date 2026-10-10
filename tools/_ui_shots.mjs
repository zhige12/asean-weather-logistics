/**
 * 交付材料《11-界面原型图》取真实界面截图：不打开发服务器，直接打后端托管的 dist
 * （Spring 从 file:frontend/dist 托管，所以截到的就是评委双击跑起来的那一版）。
 *
 * usage: node tools/_ui_shots.mjs          两个端全取
 *        node tools/_ui_shots.mjs big      只取调度大屏左栏
 *        node tools/_ui_shots.mjs drv      只取司机端
 *   产物：D:\Temp\ui_shots\*.jpg + layout.json
 * 要点：
 *   1) 左栏卡片是 6000~9500 px 的滚动容器，折在容器外不会被绘制 → 逐卡拉 scrollTop 再按视口坐标 clip；
 *   2) 卡片清单随运行状态变化（熔断时才有「路线状态」，沙盘展开时高度翻倍）→ 按标题定位，不按序号；
 *   3) 容器滚到底 scrollTop 会被钳制，卡片会低于容器顶 → clip 一律取实测矩形，不硬编 y；
 *   4) 司机端五页共用同一段地图外壳文本，去重签名必须取正文尾部。
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const MODE = process.argv[2] || 'all';
const PORT = 9351;
const OUT = 'D:\\Temp\\ui_shots';
const BIG = 'http://127.0.0.1:8080/';
const DRV = 'http://127.0.0.1:8080/driver.html';
const VIEW_W = 1600, VIEW_H = 1000;
const COL_X = 14, COL_W = 372, COL_TOP = 106;   // 左栏取景框（视口坐标）
const SEG = 860;                                  // 单张截图最大高度
const bin = ['C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'].find(existsSync);

mkdirSync(OUT, { recursive: true });
// 上一轮的布局探测结果保留（卡片清单会随运行状态变），但本轮 shot 列表重新开始
const prev = (() => { try { return JSON.parse(readFileSync(`${OUT}\\layout.json`, 'utf8')); } catch { return {}; } })();
const report = { mode: MODE, shots: [], big: prev.big || null, driver: null };
const flush = () => writeFileSync(`${OUT}\\layout.json`, JSON.stringify(report, null, 1), 'utf8');

const proc = spawn(bin, [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--no-first-run', '--disable-extensions',
  '--disable-features=Spellcheck,TextPrediction',
  '--hide-scrollbars', `--user-data-dir=${process.env.TEMP}\\uishots-${Date.now()}`,
  '--window-size=1620,1020', 'about:blank',
], { stdio: 'ignore' });

let ws, mid = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++mid; pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const ev = (expression) => send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  .then(r => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails.exception)); return r.result.value; });

async function shot(name, clip = null) {
  const p = await send('Page.captureScreenshot', {
    format: 'jpeg', quality: 88, captureBeyondViewport: !clip,
    ...(clip ? { clip: { ...clip, scale: 1 } } : {}),
  });
  const buf = Buffer.from(p.data, 'base64');
  writeFileSync(`${OUT}\\${name}.jpg`, buf);
  report.shots.push({ name, kb: Math.round(buf.length / 1024), clip });
  flush();
  console.log(`SHOT ${name}.jpg ${Math.round(buf.length / 1024)}KB h=${clip ? Math.round(clip.height) : VIEW_H}`);
}

/* ================= 大屏左栏 ================= */
// (输出名, 卡片标题前缀, 期望覆盖高度——用于把相邻小卡并到一张里)
const TARGETS = [
  ['A-路径规划', '跨境路径规划', 0],
  ['B-公司派单', '公司派单', 0],
  ['C-决策沙盘', '决策沙盘', 0],
  ['D-AI决策分析', 'AI 决策分析', 0],
  ['E-触达叫应', '多角色触达', 470],
  ['F-实时气象', '实时气象', 0],
  ['G-开放平台', '开放平台', 0],
  ['H-日志与风险', '决策日志', 560],
  ['I-路线状态', '路线状态', 0],
  ['J-简报小卡组', '任务简报', 560],
];

/** 按可见文本点按钮（状态靠界面自身流程点亮，不去猜 API 参数） */
const clickText = (re) => ev(`(() => {
  const b = [...document.querySelectorAll('button')].find(x => new RegExp(${JSON.stringify(re)}).test(x.textContent));
  if (!b) return null; b.click(); return b.textContent.trim().replace(/\\s+/g, ' ').slice(0, 24); })()`);

/** 重新打标取卡片清单：Vue 重渲染会改卡片个数与顺序，序号必须现取现用 */
const stamp = () => ev(`(() => {
  const pick = (el) => (el.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 44);
  const out = [];
  document.querySelectorAll('.panel, .card, .platform-card').forEach((el, i) => {
    el.setAttribute('data-shot-i', String(i));
    const r = el.getBoundingClientRect();
    const h = el.querySelector('h1,h2,h3,h4,.panel-title,.card-title,.title');
    out.push({ i, title: h ? pick(h) : pick(el), x: Math.round(r.x), y: Math.round(r.y),
               w: Math.round(r.width), hh: Math.round(r.height) });
  });
  return out; })()`);

async function captureBig() {
  await send('Emulation.setDeviceMetricsOverride', { width: VIEW_W, height: VIEW_H, deviceScaleFactor: 2, mobile: false });
  await send('Page.navigate', { url: BIG });
  await sleep(16000);   // 底图瓦片 + 首轮算路 + 气象拉取
  // 先走一遍真实流程：起终点确认规划 → 拉实时气象 → AI 气象分析并派单，让路线/沙盘/分析卡有真内容
  report.bigArmed = [
    await clickText('确认选择并规划'), await sleep(5000).then(() => 1),
    await clickText('拉取实时气象'), await sleep(6000).then(() => 1),
    await clickText('AI ?气象分析并规划路线'), await sleep(12000).then(() => 1),
  ].filter(Boolean);
  await ev(`(() => { const c = document.querySelector('.panel'); if (c) c.scrollTop = 0; return 1; })()`);
  await sleep(600);
  await shot('big-overview');   // 首屏总览必须在任何滚动之前拍
  const blocks = await stamp();
  report.big = { blocks, docH: await ev('document.documentElement.scrollHeight'),
                 header: await ev(`(document.querySelector('.app-header, header') || {}).innerText`) };
  flush();

  const colTop = await ev(`(() => { const c = document.querySelector('[data-shot-i="0"]');
    c.scrollTop = 0; return Math.round(c.getBoundingClientRect().top); })()`);

  for (const [key, wantTitle, capH] of TARGETS) {
    const bs = await stamp();
    const b = bs.slice(1).find(x => x.title.replace(/\s+/g, '').startsWith(wantTitle.replace(/\s+/g, '')));
    if (!b) { console.log(`SKIP ${key} (${wantTitle} 当前状态下不存在)`); continue; }
    const total = capH || b.hh;
    const segs = Math.max(1, Math.ceil((total - 60) / SEG));
    for (let s = 0; s < segs; s++) {
      await ev(`(() => { const c = document.querySelector('[data-shot-i="0"]');
        const e = document.querySelector('[data-shot-i="${b.i}"]');
        c.scrollTop = (e.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop) + ${s * SEG}; return c.scrollTop; })()`);
      await sleep(340);
      const r = await ev(`(() => { const e = document.querySelector('[data-shot-i="${b.i}"]');
        if (!e) return null; const q = e.getBoundingClientRect(); return { x: q.x, y: q.y, w: q.width, h: q.height }; })()`);
      if (!r) continue;
      // s>0 时卡片顶已被滚到视口之上，取容器顶为准；末段按剩余高度夹裁
      const y = Math.max(colTop, r.y - (s > 0 ? 0 : 6));
      const h = Math.min(SEG + 40, VIEW_H - y - 4, total - s * SEG + 12);
      await shot(key + (segs > 1 ? '-' + (s + 1) : ''),
        { x: COL_X, y, width: COL_W, height: Math.max(60, h) });
    }
  }
}

/* ================= 司机端 ================= */
async function captureDriver() {
  await send('Emulation.setDeviceMetricsOverride', { width: 430, height: 932, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: DRV });
  await sleep(20000);   // 司机端底图瓦片比大屏多一轮，等不够会在图上留空白瓦片块
  const drvTabs = await ev(`(() => {
    const out = [];
    document.querySelectorAll('.hm-tab').forEach((el, i) => {
      el.setAttribute('data-drv-tab', String(i));
      out.push({ i, label: (el.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 12) });
    });
    return out; })()`);
  report.driver = { tabs: drvTabs, pages: {} };
  flush();
  const sig = () => ev(`(() => { const t = document.body.innerText.replace(/\\s+/g,' ');
      return t.slice(Math.max(0, t.length - 260)); })()`);   // 取正文尾部：五页共用同一段地图外壳
  const seen = new Set();
  for (const t of drvTabs) {
    await ev(`(() => { const e = document.querySelector('[data-drv-tab="${t.i}"]'); if (e) e.click(); return 1; })()`);
    await sleep(5200);
    const s = await sig();
    if (seen.has(s)) { console.log('DUP  ' + t.label); continue; }
    seen.add(s);
    const nm = 'drv-' + t.label.replace(/[^\u4e00-\u9fa5A-Za-z0-9]/g, '');
    await shot(nm);
    report.driver.pages[t.label] = s;
    flush();
  }
  // 订单详情弹层（待接单 / 接单·拒单按钮）——大屏派单后司机端的核心叫应界面
  await ev(`(() => { const e = [...document.querySelectorAll('.hm-tab')][0]; if (e) e.click(); return 1; })()`);
  await sleep(1800);
  const opened = await ev(`(() => { const b = [...document.querySelectorAll('button')]
      .find(x => /预览订单/.test(x.textContent)); if (b) { b.click(); return b.textContent.trim(); } return null; })()`);
  await sleep(3000);
  if (opened) { await shot('drv-订单详情'); report.driver.orderSheet = await sig(); flush(); }
  report.driverButtons = await ev(`[...document.querySelectorAll('button')]
    .map(b => b.textContent.trim().replace(/\\s+/g,' ').slice(0,24)).filter(Boolean)`);
  flush();
}

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
  if (MODE === 'all' || MODE === 'big') await captureBig();
  if (MODE === 'all' || MODE === 'drv') await captureDriver();
} catch (e) {
  report.error = String(e && e.message || e);
  console.log('ERROR: ' + e.message);
} finally {
  flush();
  try { ws && ws.close(); } catch {}
  proc.kill('SIGTERM');
  console.log('shots=' + report.shots.length);
}
