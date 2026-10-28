// 现场核验 AI 计量链路：发一句话 → 看回包的 channel / cost → 再拉 /api/ai/metrics 看累计。
// 用途：演示前 30 秒确认「本地大模型」这条路真的在跑，以及 token/耗时是不是实测值而非估算。
const BASE = process.env.BASE || 'http://127.0.0.1:8080';

async function chat(message) {
  const t0 = Date.now();
  const r = await fetch(BASE + '/api/copilot/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, originId: 'NN', destinationId: 'HN' }),
  });
  const d = await r.json();
  console.log('Q:', message);
  console.log('  http        :', r.status, ' wall:', ((Date.now() - t0) / 1000).toFixed(1) + 's');
  console.log('  tool        :', d.tool, ' channel:', d.channel);
  console.log('  cost        :', JSON.stringify(d.cost || null));
  console.log('  say         :', (d.say || '').slice(0, 60));
  // RAG 可核验的两件事：命中了哪几条（sources）、答完花多少 token（answerCost）
  if (d.data && Array.isArray(d.data.sources)) {
    console.log('  sources     :', d.data.sources.map((s) => s.id + '/' + s.score).join(', ') || '(none)');
    console.log('  answerChan  :', d.data.answerChannel, ' answerCost:', JSON.stringify(d.data.answerCost || null));
    console.log('  summary     :', String(d.data.summary || '').slice(0, 90).replace(/\n/g, ' '));
  }
}

await chat('友谊关现在什么风险');
await chat('查一下天气');
await chat('今天天气不错啊');
// 知识问答：大屏与司机端的快捷芯片都挂了这两句，现场必须能复现
await chat('冷链货物通关要什么单证');
await chat('越南北部什么时候雨最多');

const m = await (await fetch(BASE + '/api/ai/metrics')).json();
console.log('\n=== /api/ai/metrics ===');
console.log(JSON.stringify(m, null, 2));
