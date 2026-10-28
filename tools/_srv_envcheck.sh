#!/usr/bin/env bash
echo "== unit =="
systemctl cat asean 2>/dev/null | grep -E "ExecStart|EnvironmentFile|WorkingDirectory|User"
echo "== /opt/asean env files =="
ls -la /opt/asean/.env* 2>/dev/null
echo "== keys present in .env.local (names only, no values) =="
if [ -f /opt/asean/.env.local ]; then
  grep -oE "^[A-Z_]+=" /opt/asean/.env.local
else
  echo "no /opt/asean/.env.local"
fi
