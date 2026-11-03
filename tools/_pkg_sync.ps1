$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = 'D:\idea project\asean-weather-logistics'
$pkg  = Join-Path $root 'asean-demo'
$jar  = Join-Path $root 'build\libs\asean-weather-logistics-0.0.1-SNAPSHOT.jar'

Write-Output '=== 1) sync jar into package ==='
$old = Get-Item (Join-Path $pkg 'app.jar')
Write-Output ('  old app.jar  ' + $old.Length + '  ' + $old.LastWriteTime)
Copy-Item $jar (Join-Path $pkg 'app.jar') -Force
$new = Get-Item (Join-Path $pkg 'app.jar')
Write-Output ('  new app.jar  ' + $new.Length + '  ' + $new.LastWriteTime)

Write-Output '=== 2) mirror frontend dist into package ==='
& robocopy (Join-Path $root 'frontend\dist') (Join-Path $pkg 'frontend\dist') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
Write-Output ('  robocopy exit code = ' + $LASTEXITCODE + '  (0-7 = ok)')

Write-Output '=== 3) prove the new provenance labels are inside the shipped dist ==='
$hit = 0
foreach ($f in Get-ChildItem (Join-Path $pkg 'frontend\dist\assets') -Filter *.js) {
  $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
  if ($t -and $t.Contains('aiChannelLabel')) { $hit++; Write-Output ('  ' + $f.Name + '  has aiChannelLabel') }
}
Write-Output ('  files carrying the new field: ' + $hit)

Write-Output '=== 4) prove the shipped jar carries the new field too ==='
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead((Join-Path $pkg 'app.jar'))
foreach ($name in @('DecisionOrchestrator', 'DeepseekService')) {
  $e = $z.Entries | Where-Object { $_.FullName -like ("*" + $name + ".class") } | Select-Object -First 1
  if (-not $e) { Write-Output ('  MISSING ' + $name); continue }
  $s = $e.Open(); $ms = New-Object IO.MemoryStream; $s.CopyTo($ms); $s.Close()
  $bytes = $ms.ToArray(); $ms.Dispose()
  $txt = [Text.Encoding]::GetEncoding('ISO-8859-1').GetString($bytes)
  Write-Output ('  ' + $name + '.class contains aiChannel: ' + $txt.Contains('aiChannel') + '   (size ' + $bytes.Length + ')')
}
$z.Dispose()

Write-Output '=== 5) jar timestamp chain ==='
foreach ($p in @('src\main\java\com\example\aseanweatherlogistics\service\DeepseekService.java',
                 'src\main\java\com\example\aseanweatherlogistics\service\DecisionOrchestrator.java',
                 'frontend\src\components\AgentPanel.vue',
                 'build\libs\asean-weather-logistics-0.0.1-SNAPSHOT.jar',
                 'frontend\dist\assets')) {
  $i = Get-Item (Join-Path $root $p)
  if ($i) { Write-Output ('  ' + $p.PadRight(70) + ' ' + $i.LastWriteTime) }
}

# ---- 6) gate for THIS delivery, not the last one ----
# Sections 3-4 only prove older features survived. The shipped app.jar was silently a 10/21
# build (no portal, no ASEAN view, no dashboard.html in its dist) while every check above still
# reported "ok" - because nothing was asserting what we actually meant to deliver this time.
Write-Output '=== 6) prove THIS delivery is inside the package (portal + ASEAN situation) ==='
$distDir = Join-Path $pkg 'frontend\dist'
foreach ($p in @('index.html', 'dashboard.html', 'driver.html')) {
  $f = Join-Path $distDir $p
  if (Test-Path $f) {
    $t = [IO.File]::ReadAllText($f, [Text.Encoding]::UTF8)
    Write-Output ('  dist/' + $p + ' present, declares rel="icon": ' + $t.Contains('rel="icon"'))
  } else { Write-Output ('  *** MISSING dist/' + $p + ' - portal/dashboard/driver entry not in the shipped build ***') }
}
# the portal bundle must really call the new endpoint (a stale dist would not)
$aseanHit = 0
foreach ($f in Get-ChildItem (Join-Path $distDir 'assets') -Filter *.js) {
  $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
  if ($t -and $t.Contains('/api/asean/situation')) { $aseanHit++; Write-Output ('  ' + $f.Name + '  calls /api/asean/situation') }
}
Write-Output ('  bundles calling the ASEAN endpoint: ' + $aseanHit + '  (need >= 1)')
# and the jar must carry the controller plus the 23-city table it reads from the classpath
$z2 = [IO.Compression.ZipFile]::OpenRead((Join-Path $pkg 'app.jar'))
foreach ($n in @('*AseanSituationController.class', '*AseanSituationService.class', '*data/asean-cities.json')) {
  $e = $z2.Entries | Where-Object { $_.FullName -like $n } | Select-Object -First 1
  if ($e) { Write-Output ('  jar ' + $n + ' : OK ' + $e.Length + 'B') }
  else { Write-Output ('  jar ' + $n + ' : *** MISSING ***') }
}
# no dead weight this time: the .bak road-network copies must stay out of the shipped jar
$baks = @($z2.Entries | Where-Object { $_.FullName -like '*.bak' })
Write-Output ('  jar entries ending in .bak: ' + $baks.Count + '  (must be 0; processResources excludes them)')
$z2.Dispose()
