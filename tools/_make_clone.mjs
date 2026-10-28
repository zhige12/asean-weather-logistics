// 建一份「评委 clone 视角」的目录：只含 git 跟踪的文件，不含任何未入库产物。
// 用途：验证 npm run build 在缺少本机残留文件时是否还能跑通（缺文件类 bug 只有这样才能暴露）。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const DST = process.argv[2] || 'D:\\Temp\\clone_view';

const list = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT, encoding: 'utf8' })
  .split('\0').filter(Boolean);

if (fs.existsSync(DST)) fs.rmSync(DST, { recursive: true, force: true });
let n = 0, bytes = 0;
for (const rel of list) {
  const from = path.join(ROOT, rel.replace(/\//g, path.sep));
  const to = path.join(DST, rel.replace(/\//g, path.sep));
  if (!fs.existsSync(from)) { console.log('!! git 清单里有、磁盘上没有：' + rel); continue; }
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  bytes += fs.statSync(to).size; n++;
}
console.log(`已按 git 清单复制 ${n} 个文件（${(bytes / 1048576).toFixed(1)} MB）到 ${DST}`);
console.log('注意：故意不含 node_modules / dist / data 路网 / .env.local，与评委 clone 后一致');
