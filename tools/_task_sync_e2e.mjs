/**
 * 两端响应实测（任务 E）：
 *  1) 打开调度大屏，等 SSE 连上，读派单面板状态（应为「已接单」）；
 *  2) 从外部 POST /api/task/reset 模拟司机退出接单 → 大屏面板必须在不刷新页面的情况下变「未派单」；
 *  3) 反向：POST /api/task/dispatch + /api/task/accept → 大屏必须立刻回到「已接单」；
 *  4) 收尾把任务态还原成探针开始前的样子（taskId 会变，其余字段按原样重放）。
 *
 * usage: node tools/_task_sync_e2e.mjs
 */
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const PORT = 9365;
const APP = 'http://localhost:8080/';
const API = 'http://localhost:8080';
const bin = ['C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'].find(existsSync);

const proc = spawn(bin, [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--no-first-run', '--disable-extensions',
  `--user-data-dir=${process.env.TEMP}\\tasksync-${Date.now()}`, '--window-size=1600,1000', 'about:blank',
], { stdio: 'ignore' });

let ws, mid = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++mid; pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const ev = (expression) => send('Runtime.evaluate', { expression, returnByValue: true })
  .then(r => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails.exception)); return r.result.value; });

const post = async (path, body) => {
  const r = await fetch(API + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? '{}' : JSON.stringify(body)
  });
  const t = await r.text();
  try { return JSON.parse(t); } catch { return { _raw: t.slice(0, 200) }; }
};
// 后端刚重启/瞬时断流时回包会是空体，重试几次再放弃
const get = async (path) => {
  for (let i = 0; i < 5; i++) {
    try {
      const t = await (await fetch(API + path)).text();
      if (t && t.trim()) return JSON.parse(t);
    } catch (e) { /* 重试 */ }
    await sleep(1200);
  }
  return null;
};

// 面板上的状态徽标 + 详情状态行（.task-state 是「已接单/未派单」那颗胶囊）
const READ = `(() => {
  const badge = document.querySelector('.task-state');
  const line = document.querySelector('.detail-line');
  return { badge: badge ? badge.textContent.trim() : 'NO-BADGE',
           line: line ? line.textContent.trim() : '',
           esReady: (() => { try { return 'n/a'; } catch (e) { return 'ERR'; } })() };
})()`;

/** 轮询等面板文本变成期望值，返回耗时 ms（超时返回 -1） */
async function waitFor(expectRe, budgetMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < budgetMs) {
    const r = await ev(READ);
    if (expectRe.test(r.badge)) return { ms: Date.now() - t0, badge: r.badge, line: r.line };
    await sleep(120);
  }
  const r = await ev(READ);
  return { ms: -1, badge: r.badge, line: r.line };
}

const log = [];
let snapshot = null;
try {
  snapshot = await get('/api/task/current');
  log.push('before: ' + JSON.stringify({ status: snapshot?.status, taskId: snapshot?.task?.taskId }));

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
  // 等首屏 + SSE 连上 + 挂载时补拉的 /api/task/current 回包
  await sleep(18000);
  log.push('panel@boot ' + JSON.stringify(await ev(READ)));

  // —— 司机退出接单 ——
  const rr = await post('/api/task/reset');
  log.push('reset -> ' + JSON.stringify({ status: rr?.status, task: rr?.task, raw: rr?._raw }));
  const r1 = await waitFor(/未派单/, 13000);
  log.push('after reset -> ' + JSON.stringify(r1));

  // —— 大屏重新派单 + 司机接单（反向：服务端→SSE→面板） ——
  const t = (snapshot && snapshot.task) || {};
  const body = {
    plate: t.plate, driverName: t.driverName, driverNameVn: t.driverNameVn,
    cargoType: t.cargoType, weightT: t.weightT, temp: t.temp,
    originId: t.originId, destinationId: t.destinationId, deadline: t.deadline,
    routeChoice: t.routeChoice, routeLabel: t.routeLabel, routeSummary: t.routeSummary,
    hazardProbability: t.hazardProbability, aiAnalysis: t.aiAnalysis
  };
  Object.keys(body).forEach(k => { if (body[k] === undefined || body[k] === null) delete body[k]; });
  const d1 = await post('/api/task/dispatch', body);
  log.push('dispatch -> ' + JSON.stringify({ status: d1?.status, taskId: d1?.task?.taskId, raw: d1?._raw }));
  const r2 = await waitFor(/待接单/, 13000);
  log.push('after dispatch -> ' + JSON.stringify(r2));
  if (d1 && d1.task) await post('/api/task/accept', { taskId: d1.task.taskId, driverName: t.driverName });
  const r3 = await waitFor(/已接单/, 13000);
  log.push('after accept -> ' + JSON.stringify(r3));

  const after = await get('/api/task/current');
  log.push('restored: ' + JSON.stringify({ status: after?.status, taskId: after?.task?.taskId, plate: after?.task?.plate }));
  const PASS = r1.ms >= 0 && r2.ms >= 0 && r3.ms >= 0;
  log.push('RESULT ' + (PASS ? 'PASS' : 'FAIL') + ' reset_ms=' + r1.ms + ' accept_ms=' + r3.ms);
} catch (e) {
  log.push('probe error: ' + e.message);
} finally {
  writeFileSync('tools/_task_sync_e2e.out', log.join('\n') + '\n', 'utf8');
  try { ws && ws.close(); } catch {}
  proc.kill('SIGTERM');
}
console.log('done -> tools/_task_sync_e2e.out');
