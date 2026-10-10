/**
 * 把 CDP 截出来的真实界面图按交付命名搬进 交付材料/_ui/，
 * 顺带打印每张图的尺寸，供《11-界面原型图》排版时定 column 宽度。
 * usage: node tools/_ui_pack.mjs
 */
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const SRC = 'D:\\Temp\\ui_shots';
const DST = 'D:\\idea project\\asean-weather-logistics\\交付材料\\_ui';
mkdirSync(DST, { recursive: true });

// 交付名 ← 截图名（不带扩展名）
const MAP = {
  'big-01-总览': 'big-overview',
  'big-02-路径规划': 'A-路径规划',
  'big-03-公司派单': 'B-公司派单',
  'big-04-决策沙盘口岸': 'C-决策沙盘-1',
  'big-05-决策沙盘水运': 'C-决策沙盘-2',
  'big-06-AI三智能体': 'D-AI决策分析-1',
  'big-07-AI方案对比': 'D-AI决策分析-2',
  'big-08-分级叫应': 'E-触达叫应',
  'big-09-实时气象': 'F-实时气象',
  'big-10-开放平台': 'G-开放平台-1',
  'big-11-开放平台参数': 'G-开放平台-2',
  'big-12-日志与风险': 'H-日志与风险',
  'big-13-路线状态': 'I-路线状态',
  'big-14-简报小卡组': 'J-简报小卡组',
  'drv-01-首页': 'drv-首页',
  'drv-02-任务': 'drv-任务',
  'drv-03-预警': 'drv-预警',
  'drv-04-知识库': 'drv-知识库',
  'drv-05-我的': 'drv-我的',
  'drv-06-订单详情': 'drv-订单详情',
};

/** PNG/JPEG 头部读宽高，免引依赖 */
function sizeOf(buf) {
  if (buf.readUInt32BE(0) === 0x89504e47) return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
  let p = 2;
  while (p < buf.length) {
    if (buf[p] !== 0xff) { p++; continue; }
    const m = buf[p + 1];
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
      return { h: buf.readUInt16BE(p + 5), w: buf.readUInt16BE(p + 7) };
    }
    p += 2 + buf.readUInt16BE(p + 2);
  }
  return { w: 0, h: 0 };
}

const rows = [];
for (const [dst, src] of Object.entries(MAP)) {
  const from = `${SRC}\\${src}.jpg`;
  const to = `${DST}\\${dst}.jpg`;
  let buf;
  try { buf = readFileSync(from); } catch { console.log('MISS  ' + src); continue; }
  copyFileSync(from, to);
  const { w, h } = sizeOf(buf);
  rows.push({ dst, src, w, h, kb: Math.round(buf.length / 1024) });
}
console.table(rows);
writeFileSync(`${SRC}\\pack.json`, JSON.stringify(rows, null, 1), 'utf8');
