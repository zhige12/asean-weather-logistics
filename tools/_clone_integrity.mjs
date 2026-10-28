// 评委视角的构建完整性审计：把「被 import 但没进 git」的文件抓出来。
// 这类缺陷不报错、本地能跑，只有 clone 到别人机器上才炸，所以必须静态扫引用图。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');

// git 跟踪的文件集合 = 评委 clone 后真实存在的文件
const tracked = new Set(
  execFileSync('git', ['ls-files'], { cwd: ROOT, encoding: 'utf8' })
    .split('\n').filter(Boolean).map(p => p.replace(/\\/g, '/')),
);

const FE = 'frontend/src';
const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    const rel = dir + '/' + e.name;
    if (e.isDirectory()) walk(rel);
    else if (/\.(vue|js|mjs|ts)$/.test(e.name)) files.push(rel);
  }
})(FE);

/** 解析相对 import 到仓库相对路径（补 .vue/.js/index.* 扩展名）。 */
function resolveImport(fromFile, spec) {
  if (!spec.startsWith('.')) return null; // 裸包名走 node_modules，不在仓库里
  const base = path.posix.join(path.posix.dirname(fromFile), spec);
  const cands = [base, base + '.vue', base + '.js', base + '.mjs',
    base + '/index.vue', base + '/index.js'];
  for (const c of cands) {
    if (fs.existsSync(path.join(ROOT, c.replace(/\//g, path.sep)))) return c;
  }
  return base; // 解析不出来也要报出来，别静默放过
}

const RE_STATIC = /(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g;
const problems = [];
const checked = [];

for (const f of files) {
  const src = fs.readFileSync(path.join(ROOT, f.replace(/\//g, path.sep)), 'utf8');
  for (const m of src.matchAll(RE_STATIC)) {
    const target = resolveImport(f, m[1]);
    if (!target) continue;
    checked.push(target);
    if (!tracked.has(target)) {
      problems.push(`${f}  →  ${target}  （文件在磁盘上但未入 git）`);
    }
  }
}

console.log(`扫描前端源文件 ${files.length} 个，检查相对 import ${checked.length} 处`);
console.log(`git 跟踪文件总数 ${tracked.size}`);
if (problems.length === 0) {
  console.log('RESULT: PASS — 所有被引用的前端源文件都在版本库里，clone 后不会缺文件');
} else {
  console.log('RESULT: FAIL — 评委 clone 后 Vite 会报 Failed to resolve import：');
  for (const p of [...new Set(problems)]) console.log('  ✗ ' + p);
  process.exitCode = 1;
}

// 顺带报后端：未跟踪的 .java 会让 gradlew compileJava 在评委侧失败
const looseJava = execFileSync('git', ['ls-files', '--others', '--exclude-standard'],
  { cwd: ROOT, encoding: 'utf8' }).split('\n').filter(p => /\.(java|vue|js|mjs)$/.test(p) && p.trim());
console.log(looseJava.length ? '仍未入库的源码：\n  ' + looseJava.join('\n  ') : '仍未入库的源码：无');
