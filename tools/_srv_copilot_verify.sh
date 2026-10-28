#!/usr/bin/env bash
# 演示 riskSummary 修复：注入暴雨剧本 -> 问「河内天气」应回各断面实际降雨读数 -> 复位
# 天气查询的中文 payload 走已上传的 /tmp/copilot_q.json（避免 shell 传中文乱码）
B=http://127.0.0.1:8080/api/copilot
curl -s -X POST $B/execute -H 'Content-Type: application/json' \
  -d '{"tool":"inject_scenario","args":{"scenario":"rain"},"originId":"NN","destinationId":"HN"}' \
  -o /dev/null -w 'inject=%{http_code}\n'
echo '--- weather query after rain injected ---'
curl -s -X POST $B/chat -H 'Content-Type: application/json' --data @/tmp/copilot_q.json \
  | sed -n 's/.*"say":"\([^"]*\)".*"summary":"\([^"]*\)".*/say=\1\nsummary=\2/p'
curl -s -X POST $B/execute -H 'Content-Type: application/json' \
  -d '{"tool":"reset_sandbox","args":{},"originId":"NN","destinationId":"HN"}' \
  -o /dev/null -w 'reset=%{http_code}\n'
