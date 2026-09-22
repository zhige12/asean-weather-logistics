// 生成本地地名库 place-names.json：路网带名节点（保证吸附精度）+ 策展补充（港口/口岸/运河枢纽）
import fs from 'node:fs';

const net = JSON.parse(fs.readFileSync('D:/idea project/asean-weather-logistics/src/main/resources/data/vietnam-road-network.json', 'utf8'));
const named = net.nodes.filter(n => n.name);
console.log('named nodes:', named.length, 'by level:', named.reduce((m, n) => (m[n.level] = (m[n.level] || 0) + 1, m), {}));

// 策展补充/覆盖：坐标为天地图地理编码实测或运河枢纽公开位置
const curated = [
  { name: '南宁港六景作业区', lat: 22.86899, lng: 108.88669, tag: '港口' },
  { name: '钦州港', lat: 21.776, lng: 108.534, tag: '港口' },
  { name: '北海港', lat: 21.4813, lng: 109.1055, tag: '港口' },
  { name: '防城港', lat: 21.6855, lng: 108.3545, tag: '港口' },
  { name: '友谊关', lat: 21.977838, lng: 106.714466, tag: '口岸' },
  { name: '凭祥口岸', lat: 22.0945, lng: 106.7615, tag: '口岸' },
  { name: '东兴口岸', lat: 21.5355, lng: 107.9665, tag: '口岸' },
  { name: '芒街', lat: 21.5275, lng: 107.9675, tag: '口岸' },
  { name: '海防港', lat: 20.846041, lng: 106.691518, tag: '港口' },
  { name: '马道枢纽', lat: 22.39874, lng: 108.937196, tag: '运河' },
  { name: '企石枢纽', lat: 22.280464, lng: 108.947064, tag: '运河' },
  { name: '青年枢纽', lat: 22.1, lng: 108.86, tag: '运河' },
  { name: '平塘江口', lat: 22.56, lng: 108.96, tag: '运河' },
  { name: '茅尾海', lat: 21.838333, lng: 108.535, tag: '运河' }
];

const out = [];
const usedNames = new Set();
for (const c of curated) { out.push(c); usedNames.add(c.name); }
for (const n of named) {
  if (usedNames.has(n.name)) continue;
  usedNames.add(n.name);
  const tag = /口岸|关$/.test(n.name) ? '口岸' : (/港/.test(n.name) ? '港口' : '城市');
  out.push({ name: n.name, lat: n.latitude, lng: n.longitude, tag, nodeId: n.id });
}
console.log('total entries:', out.length);
fs.writeFileSync('D:/idea project/asean-weather-logistics/src/main/resources/data/place-names.json',
  JSON.stringify(out, null, 1), 'utf8');
console.log('written');
