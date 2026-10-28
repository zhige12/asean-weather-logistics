// 校验「已构建的 dist」里真的含有对话面板底部状态行的文案，防止「改了源码但交付的是旧包」这类静默失效。
// 之所以写成文件：在 PowerShell 里用 node -e 传中文，编码/转义会带来假阴性，排查过一次就不再走命令行。
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const ASSETS = path.join(ROOT, 'frontend', 'dist', 'assets');

const files = fs.readdirSync(ASSETS).filter((f) => f.endsWith('.js'));

// 需要的文案/接口名。'在线 API·非本地' 里的中间点是 U+00B7，源码与产物必须一致。
// '知识库检索增强' 是出处行 title 里的措辞：RAG 的出处可见性也必须能进包，不能只停在源码里。

// 共用模块的文案只要求「在某个 chunk 里」；带 entry 前缀的项才按入口 chunk 校。
const ANY = ['本地大模型', '在线 API·非本地', '规则解析', '峰值并发', '/api/ai/metrics', '知识库检索增强'];
const PER_ENTRY = { main: 'cp-foot', driver: 'dcp-foot' };

const all = Object.create(null);
for (const f of files) all[f] = fs.readFileSync(path.join(ASSETS, f), 'utf8');

let fail = 0;
for (const t of ANY) {
  const where = files.filter((f) => all[f].includes(t));
  if (!where.length) fail++;
  console.log(`${where.length ? 'OK  ' : 'MISS'} [共用] ${JSON.stringify(t)}${where.length ? ' \u2190 ' + where.join(',') : ''}`);
}
for (const [entry, marker] of Object.entries(PER_ENTRY)) {
  const hit = files.some((f) => f.startsWith(entry + '-') && all[f].includes(marker));
  if (!hit) fail++;
  console.log(`${hit ? 'OK  ' : 'MISS'} [${entry}] ${JSON.stringify(marker)}`);
}
console.log(fail ? `RESULT: FAIL — dist 里缺 ${fail} 项，交付包可能是旧的` : 'RESULT: PASS — 底部状态行已进包');
process.exit(fail ? 1 : 0);
