#!/usr/bin/env bash
echo "== wait backend 8080 =="
ok=0
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1:8080/ping 2>/dev/null)
  if [ "$code" = "200" ]; then ok=1; echo "  up after ${i} tries"; break; fi
  sleep 3
done
[ "$ok" = "0" ] && echo "  !! backend not up in time"
echo "== service =="
systemctl is-active asean
echo "== speech status endpoint =="
curl -s --max-time 10 http://127.0.0.1:8080/api/ai/speech/status
echo ""
echo "== served index references which main chunk =="
curl -s --max-time 10 http://127.0.0.1:8080/ | grep -oE "assets/main-[A-Za-z0-9_-]+\.js" | head
echo "== ffmpeg on server =="
ffmpeg -version 2>/dev/null | head -1
