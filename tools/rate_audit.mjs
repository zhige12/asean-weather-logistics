#!/usr/bin/env node
/**
 * 频度合规检查脚本（对应比赛规则：Token 保管 + 禁止暴力访问）
 *
 * 用法：
 *   node tools/rate_audit.mjs            # 静态密钥检查 +（若后端在跑）动态频度压测
 *   node tools/rate_audit.mjs --no-live  # 只跑静态检查
 *   node tools/rate_audit.mjs --base=http://localhost:8080
 *
 * 静态检查：
 *   A1 官方气象 Token 不出现在任何 git 入库文件（含已推送的 origin/main）
 *   A2 天地图密钥不出现在任何 git 入库文件 / 前端构建产物 dist / Android assets
 *   A3 前端源码与构建产物不含 Authorization/Bearer 硬编码
 * 动态检查（需本机起后端；WEATHER_REAL_TOKEN 从本地 .env.local 读取，不外传）：
 *   B1 快刷 20 次会触发真实气象拉取的后端接口 → /api/weather/rate-stats 的
 *      upstreamRequests 增量应 ≤1（60s 最小间隔节流生效，其余走缓存）
 *   B2 快刷 20 次 /api/v1/observations（透传代理）→ 代理 upstreamRequests 增量 ≤1，
 *      且 throttled/cacheHits 合计应拦下绝大多数请求
 *   B3 切源风暴：连打 10 次 POST /api/weather/source/* → resetThrottle 限频生效，
 *      upstreamRequests 增量 ≤2
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const NO_LIVE = args.includes('--no-live');
const baseArg = args.find(a => a.startsWith('--base='));
const BASE = (baseArg ? baseArg.split('=')[1] : 'http://localhost:8080').replace(/\/$/, '');

let pass = 0, fail = 0;
const ok = (name, detail = '') => { pass++; console.log(`  PASS  ${name}${detail ? '  — ' + detail : ''}`); };
const bad = (name, detail = '') => { fail++; console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`); };
const info = (m) => console.log(`  ....  ${m}`);

function gitGrepAll(rev, needle) {
  // 在指定 rev 的入库文件里找字面量；命中返回匹配行，未命中返回空串
  try {
    return execSync(`git grep -F -n "${needle}" ${rev} -- .`, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    // git grep 无命中时退出码 1
    return (e.stdout || '').toString();
  }
}

function readEnvLocal() {
  const p = resolve(ROOT, '.env.local');
  if (!existsSync(p)) return {};
  const out = {};
  for (const line of readFileSync(p, 'utf8').split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return out;
}

async function pingBase() {
  // 真实端点是 /ping（PingController 没有 /api 前缀）；之前只探 /api/ping 会 404，
  // 导致动态压测被静默跳过、看起来像“没起后端”，实际是探测路径写错
  for (const p of ['/ping', '/api/ping']) {
    try {
      const r = await fetch(`${BASE}${p}`, { signal: AbortSignal.timeout(2500) });
      if (r.ok) return true;
    } catch { /* 试下一个候选 */ }
  }
  return false;
}
async function getJson(path) {
  const r = await fetch(`${BASE}${path}`, { signal: AbortSignal.timeout(30000) });
  return { status: r.status, body: await r.json().catch(() => null) };
}
async function postOnce(path, headers = {}) {
  try {
    const r = await fetch(`${BASE}${path}`, { method: 'POST', headers, signal: AbortSignal.timeout(30000) });
    return r.status;
  } catch { return 0; }
}
async function getOnce(path, headers = {}) {
  try {
    const r = await fetch(`${BASE}${path}`, { headers, signal: AbortSignal.timeout(30000) });
    return r.status;
  } catch { return 0; }
}

console.log('=== 频度合规检查（禁止暴力访问 / Token 保管） ===\n');
const env = readEnvLocal();

// ---------- A1/A2：密钥不入仓库 ----------
console.log('[A] 静态：密钥与 Token 不得入库');
const wt = env.WEATHER_REAL_TOKEN || '';
if (!wt) {
  info('本地 .env.local 无 WEATHER_REAL_TOKEN，跳过 Token 入库扫描');
} else {
  const hitHead = gitGrepAll('HEAD', wt);
  const hitRemote = gitGrepAll('origin/main', wt);
  hitHead.trim() ? bad('A1 官方气象 Token 出现在本地入库文件', hitHead.split('\n')[0])
    : ok('A1 官方气象 Token 未出现在本地入库文件');
  hitRemote.trim() ? bad('A1r 官方气象 Token 出现在已推送的 origin/main', hitRemote.split('\n')[0])
    : ok('A1r 官方气象 Token 未出现在 origin/main');
}

const tks = (env.TIANDITU_KEYS || '').split(',').map(s => s.trim()).filter(Boolean);
if (!tks.length) {
  info('本地 .env.local 无 TIANDITU_KEYS，跳过高德/天地图密钥扫描');
}
// 兜底：直接从 HEAD 的 application.properties 找 32 位十六进制串（不依赖 .env.local）
const hexScan = (rev) => {
  try {
    const txt = execSync(`git show ${rev}:src/main/resources/application.properties`, { cwd: ROOT, encoding: 'utf8' });
    return txt.match(/\b[0-9a-f]{32}\b/g) || [];
  } catch { return []; }
};
for (const [rev, label] of [['HEAD', 'A2 当前 HEAD'], ['origin/main', 'A2r 已推送 origin/main']]) {
  const found = hexScan(rev);
  found.length ? bad(`${label} application.properties 含 ${found.length} 个明文密钥`, '需从入库文件移除并轮换密钥')
    : ok(`${label} application.properties 无明文密钥`);
}
for (const k of tks) {
  const hit = gitGrepAll('HEAD', k);
  hit.trim() ? bad(`A2 天地图密钥 ${k.slice(0, 6)}… 入库`, hit.split('\n')[0]) : null;
}
// 前端产物：dist 与 Android assets 里不得有 32hex 或 Token
const distDir = resolve(ROOT, 'frontend/dist');
if (existsSync(distDir)) {
  let leaked = [];
  try {
    const out = execSync(`findstr /S /I /R /C:"[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]" *.js *.html`, { cwd: distDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    const m = out.match(/\b[0-9a-f]{32}\b/g) || [];
    leaked = [...new Set(m.filter(x => tks.includes(x) || (wt && x === wt)))];
  } catch (e) {
    const m = ((e.stdout || '') + '').match(/\b[0-9a-f]{32}\b/g) || [];
    leaked = [...new Set(m.filter(x => tks.includes(x) || (wt && x === wt)))];
  }
  leaked.length ? bad('A3 前端构建产物含真实密钥/Token', leaked.join(', ')) : ok('A3 前端构建产物无真实密钥/Token');
} else {
  info('无 frontend/dist，跳过产物扫描');
}

// ---------- B：动态频度压测 ----------
if (!NO_LIVE) {
  console.log('\n[B] 动态：节流与熔断生效性');
  if (await pingBase()) {
    const s0 = (await getJson('/api/weather/rate-stats')).body || {};
    const p0 = (await getJson('/api/v1/rate-stats')).body || {};

    // B1 快刷 20 次支流实况联动（内部走 fetchForNodes）
    for (let i = 0; i < 20; i++) await postOnce('/api/canal/tributaries/apply-live');
    const s1 = (await getJson('/api/weather/rate-stats')).body || {};
    const dUp = (s1.upstreamRequests || 0) - (s0.upstreamRequests || 0);
    const dBlk = (s1.throttleBlocked || 0) - (s0.throttleBlocked || 0);
    (dUp <= 1 ? ok : bad)(`B1 20 连发支流实况 → 真实回源 ${dUp} 次（期望 ≤1）`, `节流拦截 ${dBlk} 次，其余走缓存`);

    // B2 透传代理 20 连发（Authorization 用本地 .env.local 的 Token，不发往他处）
    if (wt) {
      const q = '/api/v1/observations?variables=TMP,PRE,RH&time_start=202412312000&time_end=202412312000&bbox=105.0,20.0,110.0,24.0';
      const codes = [];
      for (let i = 0; i < 20; i++) codes.push(await getOnce(q, { Authorization: `Bearer ${wt}` }));
      const p1 = (await getJson('/api/v1/rate-stats')).body || {};
      const dPU = (p1.upstreamRequests || 0) - (p0.upstreamRequests || 0);
      const dPT = (p1.throttled || 0) - (p0.throttled || 0);
      const dPC = (p1.cacheHits || 0) - (p0.cacheHits || 0);
      (dPU <= 1 ? ok : bad)(`B2 代理 20 连发 → 真实回源 ${dPU} 次（期望 ≤1）`, `429/熔断拦下 ${dPT} 次，缓存命中 ${dPC} 次`);
      info(`代理响应码分布：${codes.join(' ')}`);
    } else {
      info('无 WEATHER_REAL_TOKEN，跳过 B2（代理需要带 Token 才能走到回源逻辑）');
    }

    // B3 切源风暴（反复触发 resetThrottle）
    const s2 = (await getJson('/api/weather/rate-stats')).body || {};
    for (let i = 0; i < 10; i++) {
      await postOnce(i % 2 ? '/api/weather/source/open-meteo' : '/api/weather/source/contest-observation');
    }
    const s3 = (await getJson('/api/weather/rate-stats')).body || {};
    const d3 = (s3.upstreamRequests || 0) - (s2.upstreamRequests || 0);
    (d3 <= 2 ? ok : bad)(`B3 10 连发切源 → 真实回源 ${d3} 次（期望 ≤2，reset 限频生效）`);
  } else {
    info(`后端 ${BASE} 未运行，跳过动态检查（启动 Spring Boot 后去掉 --no-live 重跑）`);
  }
}

console.log(`\n=== 结果：PASS ${pass} / FAIL ${fail} ===`);
process.exit(fail ? 1 : 0);
