// 预热本地模型 + 记录冷启动耗时：OllamaService 的读超时是 60s，
// 9GB 模型第一次加载若超过它，演示时第一发会直接降级到在线通道（评委看到的就不是「本地」）。
const t0 = Date.now();
const r = await fetch('http://127.0.0.1:11434/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    model: 'qwen2.5:14b',
    messages: [{ role: 'user', content: '说一个字：好' }],
    stream: false,
    keep_alive: '30m',
    options: { num_predict: 16 },
  }),
});
const d = await r.json();
const ms = Date.now() - t0;
console.log('http      :', r.status);
console.log('cold ms   :', ms, '=', (ms / 1000).toFixed(1) + 's  (OllamaService 读超时 60000ms)');
console.log('load ms   :', d.load_duration_ms, ' eval count:', d.eval_count);
console.log('usage     :', JSON.stringify(d.usage));
console.log('content   :', JSON.stringify((d.choices?.[0]?.message?.content || '').slice(0, 40)));
console.log(ms > 60000 ? 'RESULT: 冷启动会击穿 60s 超时，演示前必须先预热' : 'RESULT: 冷启动在超时窗口内');
