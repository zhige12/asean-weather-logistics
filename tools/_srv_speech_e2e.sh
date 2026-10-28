#!/usr/bin/env bash
ok=0
for i in $(seq 1 20); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1:8080/ping 2>/dev/null)
  if [ "$c" = "200" ]; then ok=1; break; fi
  sleep 3
done
[ "$ok" = "0" ] && { echo "backend not up"; exit 1; }
echo "== speech status =="
curl -s --max-time 10 http://127.0.0.1:8080/api/ai/speech/status
echo ""
echo "== recognize test wav (expect 从南宁到河内走高速大概要多久) =="
curl -s --max-time 60 -F audio=@/tmp/speech_test.wav http://127.0.0.1:8080/api/ai/speech
echo ""
