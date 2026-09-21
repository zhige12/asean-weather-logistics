const fs = require('fs');
const p = 'node_modules/leaflet/dist/leaflet-src.esm.js';
let src = fs.readFileSync(p, 'utf8');
// remove block comments
src = src.replace(/\/\*[\s\S]*?\*\//g, '');
let lines = src.split('\n').filter(l => {
  const t = l.trim();
  return !(t === '' || t.startsWith('//'));
});
console.log('stripped line count:', lines.length);
for (let i = 5130; i <= 5160; i++) {
  console.log(i + ': ' + (lines[i - 1] || ''));
}
// also list all stripped lines containing ".project(" with their stripped line numbers
console.log('--- .project( occurrences ---');
lines.forEach((l, idx) => {
  if (l.includes('.project(')) console.log((idx + 1) + ': ' + l.trim());
});
