$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
# Verify the FRESHLY BUILT archive, not whatever is sitting on the Desktop: the Desktop
# copy is often locked by 7-Zip/Explorer, and packing then silently leaves the old file.
$tmpZip = 'D:\Temp\asean-demo-offline.zip'
if (Test-Path -LiteralPath $tmpZip) {
  $zip = $tmpZip
} else {
  $zip = Join-Path [Environment]::GetFolderPath('Desktop') 'asean-demo-offline.zip'
}
$ext  = 'D:\Temp\pkg-extract'
$log  = 'D:\Temp\pkg_extract_run.log'
$port = 8081
$base = "http://localhost:$port"

Write-Output '=== 1. clean extract to a brand new location ==='
if (Test-Path -LiteralPath $ext) { Remove-Item -LiteralPath $ext -Recurse -Force }
New-Item -ItemType Directory -Path $ext -Force | Out-Null
$sw = [Diagnostics.Stopwatch]::StartNew()
& tar.exe -xf $zip -C $ext 2>&1 | Select-Object -First 3
$sw.Stop()
Write-Output ('  extracted in ' + [math]::Round($sw.Elapsed.TotalSeconds, 1) + 's')
$app = Join-Path $ext 'asean-demo'
$s = Get-ChildItem -LiteralPath $app -Recurse -File | Measure-Object -Property Length -Sum
Write-Output ('  on disk: ' + [math]::Round($s.Sum / 1MB, 1) + ' MB in ' + $s.Count + ' files')
$dc = Get-ChildItem -LiteralPath (Join-Path $app 'decision-cache') -Filter *.json
Write-Output ('  decision-cache entries: ' + (($dc | ForEach-Object { $_.Name -replace 'scenario_','' -replace '\.json','' }) -join ', '))

Write-Output '=== 2. run it from the extracted folder, packaged jre only ==='
$c = Get-NetTCPConnection -State Listen -LocalPort $port
if ($c) { Stop-Process -Id $c.OwningProcess -Force; Start-Sleep -Seconds 3 }
if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }
$pi = Start-Process -FilePath (Join-Path $app 'jre\bin\java.exe') `
  -ArgumentList ('-jar "' + (Join-Path $app 'app.jar') + '" --server.port=' + $port + ' --ollama.enabled=false') `
  -WorkingDirectory $app -RedirectStandardOutput $log -RedirectStandardError 'D:\Temp\pkg_extract_err.log' `
  -PassThru -WindowStyle Hidden
Write-Output ('  launched pid=' + $pi.Id)

$up = $false
for ($i = 0; $i -lt 45; $i++) {
  Start-Sleep -Seconds 2
  try { $h = Invoke-WebRequest -Uri "$base/api/platform/config" -UseBasicParsing -TimeoutSec 3; if ($h.StatusCode -eq 200) { $up = $true; break } } catch { }
}
if (-not $up) {
  Write-Output '  !! FAILED to start. log tail:'
  if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 30 }
  Stop-Process -Id $pi.Id -Force
  exit 1
}
Write-Output ('  listening after ' + (($i + 1) * 2) + 's')

Write-Output '=== 3. provenance labels as the judge would see them ==='
function Show($label, $obj) {
  Write-Output ('  ' + $label)
  Write-Output ('    aiChannel=' + $obj.aiChannel + '  cacheHit=' + $obj.cacheHit)
  Write-Output ('    label     =' + $obj.aiChannelLabel)
}
Invoke-WebRequest -Uri "$base/api/sandbox/push?yggMm=200&mcMm=200" -Method POST -UseBasicParsing -TimeoutSec 10 | Out-Null
$a = Invoke-RestMethod -Uri "$base/api/agents/analysis" -TimeoutSec 300
Show 'realtime after fuse, with the local model forced off (online is armed -> expect online:deepseek-*)' $a
$c2 = Invoke-RestMethod -Uri "$base/api/agents/analysis?scenarioId=heavy_rain" -TimeoutSec 60
Show 'cache mode preset (must be unrecorded, never "local")' $c2

Write-Output '=== 3b. official contest weather feed, exactly what the judge will check ==='
try {
  $w = Invoke-RestMethod -Uri "$base/api/weather/real" -TimeoutSec 120
  Write-Output ('  /api/weather/real -> source=' + $w.source + '  label=' + $w.sourceLabel)
  Write-Output ('  cities in payload : ' + (@($w.cities).Count) + '   risks: ' + (@($w.risks).Count))
} catch { Write-Output ('  /api/weather/real FAILED: ' + $_.Exception.Message.Split([char]10)[0]) }
# second call right away: must be served from cache, proving the 60s throttle holds
try {
  $w2 = Invoke-RestMethod -Uri "$base/api/weather/real" -TimeoutSec 60
  $rs = Invoke-RestMethod -Uri "$base/api/weather/rate-stats" -TimeoutSec 20
  Write-Output ('  after 2 pulls, rate-stats: ' + (($rs.PSObject.Properties | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
  Write-Output ('  source still           : ' + $w2.source)
} catch { Write-Output ('  rate-stats FAILED: ' + $_.Exception.Message.Split([char]10)[0]) }

Write-Output '=== 4. the shipped dist must carry the new label logic ==='
$found = $false
foreach ($f in Get-ChildItem (Join-Path $app 'frontend\dist\assets') -Filter *.js) {
  $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
  if ($t -and $t.Contains('aiChannelLabel')) { $found = $true; Write-Output ('  ' + $f.Name + ' carries aiChannelLabel') }
}
Write-Output ('  dist has provenance code: ' + $found)

Write-Output '=== 4b. the extracted page actually serves the stopped-truck build ==='
$drv = Invoke-WebRequest -Uri "$base/driver.html" -UseBasicParsing -TimeoutSec 10
$ref = ([regex]::Matches($drv.Content, 'driver-[A-Za-z0-9_-]+\.js') | ForEach-Object { $_.Value } | Sort-Object -Unique) -join ','
Write-Output ('  driver.html references: ' + $ref)
$chunk = Invoke-WebRequest -Uri "$base/assets/$ref" -UseBasicParsing -TimeoutSec 20
Write-Output ('  served chunk has waitHold: ' + $chunk.Content.Contains('waitHold') +
              '   resumeFromHold: ' + $chunk.Content.Contains('resumeFromHold'))

Write-Output '=== 5. the online call must not have been rejected ==='
# DeepseekService logs only on failure (the URL appears in the warn line), so silence here
# plus aiChannel=online:* in section 3 is what proves a real, accepted model call.
$bad = @(Get-Content $log | Select-String -Pattern 'api\.deepseek\.com', 'HTTP 401', 'HTTP 403', 'HTTP 429').Count
Write-Output ('  failure lines: ' + $bad + '  (must be 0, otherwise the shipped key is dead)')
Write-Output ('  authoritative proof is section 3: aiChannel should read online:*')

Invoke-WebRequest -Uri "$base/api/sandbox/reset" -Method POST -UseBasicParsing -TimeoutSec 10 | Out-Null
Stop-Process -Id $pi.Id -Force
Write-Output '  stopped + sandbox reset'
Write-Output ('  extract kept at ' + $ext + ' for manual clicking; delete when done')
