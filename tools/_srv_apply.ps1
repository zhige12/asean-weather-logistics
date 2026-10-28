$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ssh = 'D:\Git\usr\bin\ssh.exe'
$key = 'D:\Temp\asean_key.pem'
$log = 'D:\Temp\srv_apply.txt'

$script = @'
set -e
cd /opt/asean
echo "== staging sizes =="
stat -c "%s  %n" app.jar.new /tmp/dist.tar.gz
echo "== live jar before =="
md5sum app.jar
ts=$(date +%Y%m%d_%H%M%S)
echo "== backup (ts=$ts) =="
cp -a app.jar app.jar.bak.$ts
cp -a frontend/dist frontend/dist.bak.$ts
echo "== swap jar + dist =="
mv -f app.jar.new app.jar
rm -rf frontend/dist
mkdir -p frontend/dist
tar -xzf /tmp/dist.tar.gz -C frontend/dist
echo "== live jar after =="
md5sum app.jar
ls -la frontend/dist/assets/driver-*.js
echo "== restart asean =="
systemctl restart asean
echo "restart issued, waiting for boot..."
'@
$script = $script -replace "`r", ""
$out = ($script | & $ssh -i $key -o BatchMode=yes -o ConnectTimeout=15 root@8.138.229.14 'bash -s' 2>&1)
$out | Out-File -FilePath $log -Encoding UTF8
Write-Output $out
Write-Output ('EXIT=' + $LASTEXITCODE + '  saved ' + $log)
