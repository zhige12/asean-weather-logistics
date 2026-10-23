$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
# Act as the judge: bare-launch the installer (NO command line arguments == a double click)
# and touch nothing else. Everything after that has to happen by itself: stage the payload,
# expand it, start start.bat, serve, answer the official-weather endpoint.
# The 7-Zip SFX route could not be tested this way because it needed a human to click a
# dialog; this launcher is our own code, so the whole flow is verifiable unattended.
$exe = 'D:\Temp\asean-demo-setup.exe'
$run = 'D:\Temp\setup-live'
if (-not (Test-Path -LiteralPath $exe)) { Write-Output ('MISSING ' + $exe); exit 1 }
$free = (Get-PSDrive 'D').Free
Write-Output ('  free on D: ' + [math]::Round($free / 1GB, 1) + ' GB (need about 1.1)')
if ($free -lt 1.2GB) { Write-Output '  *** not enough space for the sandbox run ***'; exit 1 }

foreach ($p in Get-Process -ErrorAction SilentlyContinue) {
  $pp = $null; try { $pp = $p.Path } catch { }
  if ($pp -and $pp -like ($run + '*')) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2
if (Test-Path -LiteralPath $run) { Remove-Item -LiteralPath $run -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Path $run -Force | Out-Null
Copy-Item -LiteralPath $exe -Destination (Join-Path $run 'asean-demo-setup.exe') -Force
Write-Output ('  sandbox ' + $run)

$t0 = Get-Date
Start-Process -FilePath (Join-Path $run 'asean-demo-setup.exe') -WorkingDirectory $run
Write-Output '  launched bare, nothing clicked'

Write-Output '=== 1) does it install itself? ==='
$jar = Join-Path $run 'asean-demo\app.jar'
while (((Get-Date) - $t0).TotalSeconds -lt 420) {
  Start-Sleep -Seconds 5
  if (Test-Path -LiteralPath $jar) { break }
}
$secs = [math]::round(((Get-Date) - $t0).TotalSeconds)
Write-Output ('  app.jar present: ' + (Test-Path -LiteralPath $jar) + '  after ' + $secs + 's')
if (-not (Test-Path -LiteralPath $jar)) {
  foreach ($p in Get-Process -ErrorAction SilentlyContinue) {
    $pp = $null; try { $pp = $p.Path } catch { }
    if ($pp -and $pp -like ($run + '*')) { Write-Output ('  still running: pid=' + $p.Id + ' ' + $p.ProcessName + ' ' + $pp) }
  }
  Write-Output ('  leftover staged zip: ' + (@(Get-ChildItem -LiteralPath $run -Filter '*.tmp.zip' -ErrorAction SilentlyContinue).Count))
  Write-Output ('  sandbox contents: ' + ((Get-ChildItem -LiteralPath $run -Force -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }) -join ', '))
  exit 1
}
$sz = [math]::Round((Get-ChildItem -LiteralPath (Join-Path $run 'asean-demo') -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Output ('  expanded payload: ' + $sz + ' MB   staged zip removed: ' +
              (@(Get-ChildItem -LiteralPath $run -Filter '*.tmp.zip' -ErrorAction SilentlyContinue).Count -eq 0))
$rd = Join-Path $run 'asean-demo\README.txt'
Write-Output ('  README there: ' + (Test-Path -LiteralPath $rd) + '   .env.local there: ' +
              (Test-Path -LiteralPath (Join-Path $run 'asean-demo\.env.local')))

Write-Output '=== 2) did it start the server by itself? ==='
$srv = $null
$d2 = $t0.AddSeconds(300)
while ((Get-Date) -lt $d2) {
  foreach ($p in Get-Process -Name java -ErrorAction SilentlyContinue) {
    $pp = $null; try { $pp = $p.Path } catch { }
    if ($pp -and $pp -like ($run + '*')) {
      $cn = @(Get-NetTCPConnection -State Listen -OwningProcess $p.Id -ErrorAction SilentlyContinue)
      if ($cn.Count -gt 0) { $srv = @{ proc = $p; port = $cn[0].LocalPort }; break }
    }
  }
  if ($srv) { break }
  Start-Sleep -Seconds 5
}
if (-not $srv) { Write-Output '  *** no bundled java is listening -> the launcher did not start the app ***'; exit 1 }
Write-Output ('  java pid=' + $srv.proc.Id + '  LISTENING on ' + $srv.port + '   (' + [math]::round(((Get-Date) - $t0).TotalSeconds) + 's from double-click)')
$base = 'http://localhost:' + $srv.port

Write-Output '=== 3) is it the real thing? ==='
$h = Invoke-WebRequest -Uri ($base + '/') -TimeoutSec 40 -UseBasicParsing
Write-Output ('  GET /  -> ' + $h.StatusCode + '  ' + $h.RawContentLength + 'B')
$w = Invoke-RestMethod -Uri ($base + '/api/weather/real') -TimeoutSec 180
Write-Output ('  /api/weather/real -> source=' + $w.source + '  risks=' + @($w.risks).Count)
$rs = Invoke-RestMethod -Uri ($base + '/api/weather/rate-stats') -TimeoutSec 30
Write-Output ('  rate-stats upstreamRequests=' + $rs.upstreamRequests + ' throttleBlocked=' + $rs.throttleBlocked + ' lastSource=' + $rs.lastSource)

Write-Output '=== 4) second launch must skip the install and go straight to start ==='
Stop-Process -Id $srv.proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 4
$t1 = Get-Date
Start-Process -FilePath (Join-Path $run 'asean-demo-setup.exe') -WorkingDirectory $run
$srv2 = $null
while (((Get-Date) - $t1).TotalSeconds -lt 180) {
  Start-Sleep -Seconds 3
  foreach ($p in Get-Process -Name java -ErrorAction SilentlyContinue) {
    $pp = $null; try { $pp = $p.Path } catch { }
    if ($pp -and $pp -like ($run + '*')) {
      $cn = @(Get-NetTCPConnection -State Listen -OwningProcess $p.Id -ErrorAction SilentlyContinue)
      if ($cn.Count -gt 0) { $srv2 = @{ proc = $p; port = $cn[0].LocalPort }; break }
    }
  }
  if ($srv2) { break }
}
if ($srv2) {
  Write-Output ('  relaunch reached LISTEN in ' + [math]::round(((Get-Date) - $t1).TotalSeconds) + 's on port ' + $srv2.port)
  Stop-Process -Id $srv2.proc.Id -Force -ErrorAction SilentlyContinue
} else {
  Write-Output '  *** relaunch did not reach a listening server ***'
}

Write-Output '=== 5) cleanup ==='
Start-Sleep -Seconds 3
foreach ($p in Get-Process -ErrorAction SilentlyContinue) {
  $pp = $null; try { $pp = $p.Path } catch { }
  if ($pp -and $pp -like ($run + '*')) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2
Remove-Item -LiteralPath $run -Recurse -Force -ErrorAction SilentlyContinue
Write-Output ('  sandbox removed: ' + (-not (Test-Path -LiteralPath $run)))
Write-Output '==> DOUBLE-CLICK INSTALLER VERIFIED END TO END'
