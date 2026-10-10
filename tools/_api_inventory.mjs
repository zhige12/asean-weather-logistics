/**
 * 接口清单取证：扫描 controller 包，输出「类级前缀 + 方法级路径 + HTTP 动词」，
 * 供《01-技术文档》与图 4/6 核对，避免文档里的接口清单与代码脱节。
 * 路径由脚本自身位置推导，不写死开发机绝对路径；输出落系统临时目录。
 * usage: node tools/_api_inventory.mjs
 */
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DIR = join(ROOT, 'src/main/java/com/example/aseanweatherlogistics/controller');
const OUT = join(process.env.TEMP || process.env.TMP || ROOT, 'api_inventory.json');
const VERB = { GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT', DeleteMapping: 'DELETE', PatchMapping: 'PATCH' };
const rows = [];

for (const f of readdirSync(DIR).filter(n => n.endsWith('.java')).sort()) {
  const src = readFileSync(join(DIR, f), 'utf8');
  const baseM = src.match(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/);
  const base = baseM ? baseM[1] : '';
  // 括号可省略：@GetMapping（无路径根映射）也是真实端点，早期正则要求必须有 ( ，
  // 把这类映射全部漏掉，端点总数被低估。
  const re = /@(Get|Post|Put|Delete|Patch)Mapping\b\s*(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?/g;
  let m;
  while ((m = re.exec(src))) {
    const verb = VERB[m[1] + 'Mapping'];
    const sub = m[2] || '';
    const path = (base + sub).replace(/\/+$/, '') || base;
    rows.push({ ctrl: f.replace('.java', ''), verb, path });
  }
}

const byCtrl = {};
for (const r of rows) (byCtrl[r.ctrl] ||= []).push(r.verb + ' ' + r.path);
for (const [c, list] of Object.entries(byCtrl)) {
  console.log('== ' + c + ' (' + list.length + ')');
  for (const l of list) console.log('   ' + l);
}
console.log('TOTAL endpoints=' + rows.length + '  controllers=' + Object.keys(byCtrl).length);
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(byCtrl, null, 1), 'utf8');
console.log('written -> ' + OUT);
