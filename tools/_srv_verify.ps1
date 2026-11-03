$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ssh = 'D:\Git\usr\bin\ssh.exe'
$key = 'D:\Temp\asean_key.pem'
$log = 'D:\Temp\srv_verify.txt'

$script = @'
echo "== wait for backend on 8080 =="
ok=0
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1:8080/ 2>/dev/null)
  if [ "$code" = "200" ]; then ok=1; echo "  up after ${i} tries (http $code)"; break; fi
  sleep 3
done
[ "$ok" = "0" ] && echo "  !! backend not answering 200 within timeout"
echo "== service =="
systemctl is-active asean
echo "== last log lines =="
journalctl -u asean --no-pager -n 20 | tail -20
echo "== via nginx 8443 =="
curl -s -o /dev/null -w "  8443 /            = %{http_code}\n" --max-time 10 -k https://127.0.0.1:8443/
curl -s -o /dev/null -w "  8443 /driver.html = %{http_code}\n" --max-time 10 -k https://127.0.0.1:8443/driver.html
echo "== served driver.html references which chunk =="
curl -s --max-time 10 -k https://127.0.0.1:8443/driver.html | grep -oE "assets/driver-[A-Za-z0-9_-]+\.js" | head
echo "== regression: outreach/status still carries riskCleared =="
curl -s --max-time 10 http://127.0.0.1:8080/api/outreach/status | grep -oE "\"riskCleared\":(true|false)" | head
echo "== THIS DEPLOYMENT: three entries on the public origin =="
for p in / /dashboard.html /driver.html /favicon.svg; do
  curl -s -o /dev/null -w "  https://zhi-ning.cn$p = %{http_code}\n" --max-time 15 https://zhi-ning.cn$p
done
echo "== THIS DEPLOYMENT: /api/asean/situation (was 404 before) =="
curl -s -o /dev/null -w "  http_code=%{http_code}  bytes=%{size_download}\n" --max-time 40 http://127.0.0.1:8080/api/asean/situation
curl -s --max-time 40 http://127.0.0.1:8080/api/asean/situation | grep -oE "\"(countryCount|cityCount|cities|updatedAt)\":" | sort -u | head
echo "== served portal chunk =="
curl -s --max-time 10 https://zhi-ning.cn/ | grep -oE "assets/main-[A-Za-z0-9_-]+\.js" | head
echo "== driver.html declares favicon (the 404 fix) =="
curl -s --max-time 10 https://zhi-ning.cn/driver.html | grep -oE "rel=\"icon\"[^>]*" | head
echo "-- probes done --"
'@
$script = $script -replace "`r", ""
# Keep the LAST line of the heredoc quoted text (currently `echo "-- probes done --"`).
# PowerShell attaches a \r to the final line when piping a string into ssh.exe's stdin, even if
# the source file has zero CR bytes (verified: this file contains none). A CR landing inside a
# quoted argument is silent and harmless, but a CR landing on a bare command name is fatal:
#   bash: line 30: $'head\r': command not found
# which earlier made it look like a feature was unverified when only the last probe failed to run.
# Do NOT "fix" this by appending a bare newline - that just creates a pure-CR empty line and
# bash then reports `bash: line 31: $'\r': command not found`. Comments here stay ASCII on purpose:
# PS 5.1 decodes BOM-less UTF-8 .ps1 as GBK, and CJK in comments can break parsing.
$out = ($script | & $ssh -i $key -o BatchMode=yes -o ConnectTimeout=15 root@8.138.229.14 'bash -s' 2>&1)
$out | Out-File -FilePath $log -Encoding UTF8
Write-Output $out
Write-Output ('EXIT=' + $LASTEXITCODE + '  saved ' + $log)
