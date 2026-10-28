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
echo "== outreach/status carries riskCleared =="
curl -s --max-time 10 http://127.0.0.1:8080/api/outreach/status | grep -oE "\"riskCleared\":(true|false)" | head
'@
$script = $script -replace "`r", ""
$out = ($script | & $ssh -i $key -o BatchMode=yes -o ConnectTimeout=15 root@8.138.229.14 'bash -s' 2>&1)
$out | Out-File -FilePath $log -Encoding UTF8
Write-Output $out
Write-Output ('EXIT=' + $LASTEXITCODE + '  saved ' + $log)
