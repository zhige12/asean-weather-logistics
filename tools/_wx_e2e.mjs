// 气象参数扩展 端到端验证：进模拟模式 -> 拖全要素触发灾害 -> 查综合评分/货损/趋势 -> 跑智能体分析查司机个性化提示
const B = 'http://localhost:8080';
async function req(path, opt) {
  const r = await fetch(B + path, opt);
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch { /* keep raw */ }
  return { status: r.status, json: j, raw: t };
}
const post = (p, body) => req(p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });

function showSection(tag, st) {
  if (!st.json) { console.log(tag, 'NON-JSON', st.status, st.raw.slice(0, 160)); return; }
  const j = st.json;
  const secs = j.sections || [];
  console.log(`\n[${tag}] mode=${j.mode} roadBlocked=${j.roadBlocked} waterBlocked=${j.waterBlocked} direction=${j.direction}`);
  for (const s of secs) {
    const rr = s.roadRisk || {};
    const tr = s.trend || {};
    console.log(`  ${s.name}: mm=${s.forecastMm} roadRisk=${rr.score}(${rr.level}) factors=${JSON.stringify(rr.factors || [])} trend=${tr.triggered ? tr.text : '-'}`);
  }
  if (j.cargoRisk) {
    console.log(`  cargoRisk=${j.cargoRisk.score}(${j.cargoRisk.level}) ${j.cargoRisk.cargoName} cold=${j.cargoRisk.coldChain} loss=${j.cargoRisk.estimatedLossYuan} advice=${j.cargoRisk.advice}`);
  }
}

(async () => {
  // 0) 基础探测：state 是否已有新结构（判断服务是否加载了新代码）
  const base = await req('/api/sandbox/state');
  const hasNew = base.json && base.json.simParams && ('yggTempC' in base.json.simParams);
  console.log('state.simParams 含 yggTempC(新代码标志):', hasNew, ' keys=', base.json && base.json.simParams ? Object.keys(base.json.simParams).join(',') : '(none)');
  if (!base.json || !('sections' in base.json)) { console.log('服务响应异常，可能未加载新代码，请先重启后端'); return; }

  // 1) 进入模拟模式
  showSection('enter', await post('/api/sandbox/sim/enter'));

  // 2) 场景A：友谊关复合灾害（强降雨+低温结冰+浓雾+强侧风+高湿）——公路侧风险拉满
  await post('/api/sandbox/sim/params', {
    yggRainMm: 85, yggTempC: 2, yggVisibilityM: 300, yggWindKph: 65, yggHumidityPct: 90, yggCloudPct: 95, yggPressureHpa: 1002,
    mcRainMm: 10, mcTempC: 26, mcWindKph: 20, mcVisibilityM: 8000, mcHumidityPct: 70, mcCloudPct: 40,
  });
  showSection('A 复合灾害(雨85/温2/雾300/风65/湿90)', await req('/api/sandbox/state'));

  // 3) 场景B：冷链高温高湿（无熔断），验证冷链车高温/霉变个性化提示
  await post('/api/sandbox/sim/params', {
    yggRainMm: 35, yggTempC: 41, yggVisibilityM: 6000, yggWindKph: 48, yggHumidityPct: 92, yggCloudPct: 50, yggPressureHpa: 1010,
    mcRainMm: 5, mcTempC: 30, mcWindKph: 15, mcVisibilityM: 9000, mcHumidityPct: 60, mcCloudPct: 30,
  });
  const b = await req('/api/sandbox/state');
  showSection('B 冷链高温(温41/湿92/风48/雨35)', b);
  console.log('  water:', JSON.stringify(b.json.water && { blocked: b.json.water.blocked, wave: b.json.water.waveM, windMs: b.json.water.windMs, rainMm: b.json.water.rainMm } || null));

  // 4) 智能体分析（无 scenarioId 走真实推理），查司机个性化 riskTips
  const an = await req('/api/agents/analysis?originId=NN&destinationId=HN');
  if (!an.json) { console.log('\n[analysis] NON-JSON', an.status, an.raw.slice(0, 200)); return; }
  const targets = (an.json.outreachPreview && an.json.outreachPreview.targets) || [];
  console.log('\n[analysis] aiPowered=' + an.json.aiPowered + ' direction=' + an.json.direction + ' targets=' + targets.length);
  const comp = an.json.explanation && an.json.explanation.compositeSummary;
  if (comp) console.log('  compositeSummary:', comp);
  for (const t of targets) {
    if (t.riskTips) {
      console.log(`  ${t.name} riskTips(${t.riskTips.length}):`);
      for (const tip of t.riskTips) console.log(`    - [${tip.level}][${tip.vehicle}] ${tip.text}`);
    }
  }

  // 复位到常态，避免影响前端观感
  await post('/api/sandbox/reset');
  console.log('\n(done, sandbox reset)');
})();
