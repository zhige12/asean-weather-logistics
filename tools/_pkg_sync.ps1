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
