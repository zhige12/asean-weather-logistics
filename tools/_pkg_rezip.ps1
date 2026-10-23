$ErrorActionPreference = 'SilentlyContinue'
$root = 'D:\idea project\asean-weather-logistics'
$pkg  = Join-Path $root 'asean-demo'

# ---- 0) stop the packaged test instance ----
$c = Get-NetTCPConnection -State Listen -LocalPort 8081
if ($c) { Stop-Process -Id $c.OwningProcess -Force; Start-Sleep -Seconds 3; Write-Output '  test instance on 8081 stopped' }

# ---- 0.5) normalise the manual: UTF-8 WITH BOM + CRLF ----
# Notepad on older Windows shows mojibake for BOM-less UTF-8, and this manual is the
# only entry point for a judge who never opened the app. The IDE silently strips the BOM
# again whenever it re-saves the file, so it is re-applied here, at packing time, and
# verified from the finished archive (section 4).
$rp = Join-Path $pkg 'README.txt'
$rt = [IO.File]::ReadAllText($rp, [Text.Encoding]::UTF8)
$rt = (($rt -replace "`r`n", "`n" -replace "`r", "`n") -split "`n") -join "`r`n"
[IO.File]::WriteAllText($rp, $rt, (New-Object Text.UTF8Encoding($true)))
$rb = [IO.File]::ReadAllBytes($rp)
Write-Output ('  README.txt normalized: ' + $rb.Length + 'B  BOM=' + ($rb[0] -eq 239) + '  CRLF=' + (@($rb | Where-Object { $_ -eq 13 }).Count))

# start.bat is pure ASCII but MUST be CRLF: cmd.exe mis-parses the :findport / goto
# loop in a LF-only batch file, which would break the automatic port fallback.
$bp = Join-Path $pkg 'start.bat'
$bt = [IO.File]::ReadAllText($bp, [Text.Encoding]::ASCII)
$bt = (($bt -replace "`r`n", "`n" -replace "`r", "`n") -split "`n") -join "`r`n"
[IO.File]::WriteAllText($bp, $bt, [Text.Encoding]::ASCII)
$bb = [IO.File]::ReadAllBytes($bp)
Write-Output ('  start.bat normalized: ' + $bb.Length + 'B  CRLF=' + (@($bb | Where-Object { $_ -eq 13 }).Count) + '  nonAscii=' + (@($bb | Where-Object { $_ -gt 127 }).Count))

# ---- 1) pre-flight: what secrets are inside the package config ----
Write-Output '=== package .env.local secret audit (names only, values masked) ==='
$envTxt = Get-Content -LiteralPath (Join-Path $pkg '.env.local') -Encoding UTF8
foreach ($line in $envTxt) {
  if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
    $name = $Matches[1]; $val = $Matches[2].Trim()
    if ($name -match '^#') { continue }
    if ($val -eq '' -or $val -match '^#') { Write-Output ('  ' + $name.PadRight(26) + '(empty)') }
    else { Write-Output ('  ' + $name.PadRight(26) + 'SET  len=' + $val.Length) }
  }
}
$banned = @('DEEPSEEK_API_KEY', 'NEW_API_KEY', 'CONTEST_TOKEN')
$offenders = @()
foreach ($line in $envTxt) {
  foreach ($b in $banned) {
    if ($line -match ('^\s*' + $b + '\s*=\s*\S')) { $offenders += $b }
  }
}
if ($offenders.Count -gt 0) {
  Write-Output ('  *** FORBIDDEN SECRET PRESENT: ' + ($offenders -join ',') + ' -- DO NOT SHIP ***')
  exit 1
}
Write-Output '  OK: no gateway/API keys in the package config'

# The contest weather token IS shipped on purpose (the judge must be able to verify the
# official CRA40 feed personally). What the rules actually forbid is the token reaching the
# repository or the frontend bundle, so those two are the hard gates here.
$tok = $null
foreach ($line in $envTxt) {
  if ($line -match '^\s*WEATHER_REAL_TOKEN\s*=\s*(\S+)') { $tok = $Matches[1]; break }
}
if ($tok) {
  Write-Output ('  *** CONTEST TOKEN IS INSIDE THIS PACKAGE (len=' + $tok.Length + ') ***')
  Write-Output '      by design, so a judge can verify official data - but this zip goes to judges'
  Write-Output '      ONLY: no public netdisk link, no repo, no chat, no shared drive folder.'
  $distHits = 0
  foreach ($f in Get-ChildItem -LiteralPath (Join-Path $pkg 'frontend\dist') -Recurse -File) {
    $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
    if ($t -and $t.Contains($tok)) { $distHits++; Write-Output ('      token found in ' + $f.Name) }
  }
  Write-Output ('      occurrences in frontend\dist: ' + $distHits + '  (must be 0)')
  if ($distHits -gt 0) { Write-Output '  *** TOKEN LEAKED INTO THE FRONTEND BUNDLE ***'; exit 1 }
  $gitHits = @(& git -C $root --no-pager grep -l -F $tok HEAD 2>$null)
  Write-Output ('      files in git HEAD containing the token: ' + $gitHits.Count + '  (must be 0)')
  if ($gitHits.Count -gt 0) { Write-Output '  *** TOKEN IS COMMITTED TO GIT ***'; exit 1 }
} else {
  Write-Output '  contest token NOT in package -> judges cannot verify the official feed'
}

# A spendable online-LLM key is allowed on purpose (the author may want the judge to see a
# real model call), but it must never go out unnoticed, so it is announced on every pack.
$llmKey = @($envTxt | Where-Object { $_ -match '^\s*DEEPSEEK_FALLBACK_KEY\s*=\s*\S' })
$llmUrl = @($envTxt | Where-Object { $_ -match '^\s*deepseek\.fallback\.url\s*=\s*\S' })
if ($llmKey.Count -gt 0 -and $llmUrl.Count -gt 0) {
  Write-Output '  *** ONLINE LLM CHANNEL IS LIVE IN THIS PACKAGE ***'
  Write-Output '      the zip carries a spendable key: delete it in the DeepSeek console when done'
} elseif ($llmKey.Count -gt 0 -or $llmUrl.Count -gt 0) {
  Write-Output '  *** HALF-ENABLED online channel (key without url, or url without key).' 
  Write-Output '      either way the model is NOT called; fix both lines or clear both.'
} else {
  Write-Output '  online LLM channel: OFF (package cannot call any model, 0 outbound AI requests)'
}

# ---- 2) also confirm the jar itself carries no key ----
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipIn = [IO.Compression.ZipFile]::OpenRead((Join-Path $pkg 'app.jar'))
$entry = $zipIn.Entries | Where-Object { $_.FullName -like '*application.properties' } | Select-Object -First 1
$sr = New-Object IO.StreamReader($entry.Open())
$txt = $sr.ReadToEnd(); $sr.Close(); $zipIn.Dispose()
$bad = $false
foreach ($line in ($txt -split "`n")) {
  if ($line -match '^\s*(tianditu\.keys|tianditu\.tk|weather\.real\.token|deepseek\.)\s*=\s*\$\{[A-Z_]*:\s*[^\}]+\}') {
    Write-Output ('  *** jar has a hardcoded default: ' + $line.Trim()); $bad = $true
  }
}
if (-not $bad) { Write-Output '  OK: jar defaults are all empty placeholders (no key inside the jar)' }

# ---- 3) rebuild the deliverable zip ----
# Build into D:\Temp first. The Desktop copy is routinely held open by Explorer's
# preview pane / Windows Search indexer; when Remove-Item + tar both fail silently the
# script used to report "REBUILT" while the OLD archive was still sitting there.
$pkgName = 'asean-demo-offline.zip'
$fresh = Join-Path 'D:\Temp' $pkgName
if (Test-Path -LiteralPath $fresh) { Remove-Item -LiteralPath $fresh -Force }
$sw = [Diagnostics.Stopwatch]::StartNew()
& tar.exe -a --format zip --options 'zip:compression=deflate,compression-level=1' -cf $fresh -C $root 'asean-demo' 2>&1 | Select-Object -First 3
$sw.Stop()
if (-not (Test-Path -LiteralPath $fresh)) { Write-Output '  *** tar produced no archive ***'; exit 1 }
$target = $fresh
$i = Get-Item -LiteralPath $fresh
Write-Output ('  BUILT ' + [math]::Round($i.Length/1MB,1) + ' MB in ' + [math]::Round($sw.Elapsed.TotalSeconds,1) + 's  ' + $i.LastWriteTime + '  (tar exit=' + $LASTEXITCODE + ')  at ' + $fresh)

# ---- 4) verify the zip really carries the key-bearing config, and nothing else sensitive ----
$tmp = 'D:\Temp\zipaudit'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory $tmp -Force | Out-Null
& tar.exe -xf $target -C $tmp 'asean-demo/.env.local' 'asean-demo/README.txt' 'asean-demo/start.bat'
$bb2 = [IO.File]::ReadAllBytes((Join-Path $tmp 'asean-demo\start.bat'))
Write-Output ('  zip start.bat: ' + $bb2.Length + 'B  CRLF=' + (@($bb2 | Where-Object { $_ -eq 13 }).Count) + '  nonAscii=' + (@($bb2 | Where-Object { $_ -gt 127 }).Count))
$rb2 = [IO.File]::ReadAllBytes((Join-Path $tmp 'asean-demo\README.txt'))
Write-Output ('  zip README.txt: ' + $rb2.Length + 'B  BOM=' + ($rb2[0] -eq 239) + '  CRLF=' + (@($rb2 | Where-Object { $_ -eq 13 }).Count))
$rt2 = [IO.File]::ReadAllText((Join-Path $tmp 'asean-demo\README.txt'), [Text.Encoding]::UTF8)
Write-Output ('  zip README lines: ' + (($rt2 -split "`r`n").Count) + '   mentions start.bat: ' + $rt2.Contains('start.bat'))
$inZip = Get-Content -LiteralPath (Join-Path $tmp 'asean-demo\.env.local') -Encoding UTF8
$hasKey = ($inZip | Where-Object { $_ -match '^\s*TIANDITU_KEYS\s*=\s*\S' }).Count -gt 0
Write-Output ('  zip .env.local TIANDITU_KEYS set: ' + $hasKey)
$leakToken = ($inZip | Where-Object { $_ -match '^\s*WEATHER_REAL_TOKEN\s*=\s*\S' }).Count -gt 0
Write-Output ('  zip .env.local contest token set : ' + $leakToken + '   (True = judges can verify official data)')
$srcZip = ($inZip | Where-Object { $_ -match '^\s*WEATHER_SOURCE\s*=\s*(\S+)' })
Write-Output ('  zip .env.local weather source   : ' + (($srcZip | ForEach-Object { ($_ -split '=', 2)[1].Trim() }) -join ','))

$n = (& tar.exe -tf $target).Count
Write-Output ('  zip entries: ' + $n)

# ---- 4.5) publish to the Desktop, retrying while a lock is held ----
$desk = [Environment]::GetFolderPath('Desktop')
$deskZip = Join-Path $desk $pkgName
$old = Get-Item -LiteralPath $deskZip
if ($old) {
  Write-Output ('  desktop copy before: ' + [math]::Round($old.Length/1MB,1) + ' MB  ' + $old.LastWriteTime)
}
$pub = $false
for ($k = 1; $k -le 12; $k++) {
  try {
    Copy-Item -LiteralPath $fresh -Destination $deskZip -Force -ErrorAction Stop
    $pub = $true; break
  } catch {
    Write-Output ('  copy attempt ' + $k + ' failed: ' + $_.Exception.Message.Split([char]10)[0])
    Start-Sleep -Seconds 5
  }
}
if ($pub) {
  $d = Get-Item -LiteralPath $deskZip
  Write-Output ('  PUBLISHED ' + $deskZip + '  ' + [math]::Round($d.Length/1MB,1) + ' MB  ' + $d.LastWriteTime)
} else {
  Write-Output '  *** COULD NOT REPLACE the Desktop copy (another process holds it open) ***'
  Write-Output ('      send THIS freshly built one instead: ' + $fresh)
}

# ---- 5) make sure git will never swallow this ----
Write-Output '=== git safety ==='
& git -C $root status --porcelain | Select-Object -First 20
Write-Output ('  asean-demo ignored: ' + ((& git -C $root check-ignore -v asean-demo) -ne $null))
