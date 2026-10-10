// 端到端：地名 -> geocode -> snap -> plan，验证随机输入真的能跨境导航
async function j(url, opt) { const r = await fetch(url, opt); const t = await r.text(); return { status: r.status, body: t }; }
async function flow(name, dest) {
  const g = await j('http://localhost:8080/api/geocode?name=' + encodeURIComponent(name));
  if (g.status !== 200) { console.log(`[${name}] geocode FAIL ${g.status} ${g.body.slice(0,120)}`); return; }
  const geo = JSON.parse(g.body);
  const s = await j(`http://localhost:8080/api/geocode/snap?lat=${geo.lat}&lng=${geo.lng}&label=${encodeURIComponent(name)}`);
  if (s.status !== 200) { console.log(`[${name}] snap FAIL ${s.status} ${s.body.slice(0,140)}`); return; }
  const snap = JSON.parse(s.body);
  const p = await j('http://localhost:8080/api/route/plan', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ originId: snap.nodeId, destinationId: dest, period: 'CURRENT', cargoType: 'cold' })
  });
  if (p.status !== 200) { console.log(`[${name}] plan FAIL ${p.status} ${p.body.slice(0,140)}`); return; }
  const pr = JSON.parse(p.body);
  console.log(`[${name}] geo(${geo.source}) -> snap(${snap.nodeId} ${snap.distanceKm}km) -> plan ${pr.totalDistanceKm}km/${pr.estimatedHours}h 到 ${dest}`);
}
(async () => {
  await flow('石埠', 'HN');
  await flow('西乡塘', 'HN');
  await flow('胡志明市', 'NN');
})();
