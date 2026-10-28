param([string]$Target = 'build')
$ErrorActionPreference = 'Continue'
$root = 'D:\idea project\asean-weather-logistics\frontend'
Set-Location -LiteralPath $root
Write-Output ('cwd: ' + (Get-Location).Path)
if ($Target -eq 'build') {
  & npm run build 2>&1 | Select-Object -Last 20
  Write-Output ('npm exit: ' + $LASTEXITCODE)
}
$dist = Join-Path $root 'dist'
$idx = Join-Path $dist 'index.html'
if (Test-Path -LiteralPath $idx) {
  $f = Get-Item -LiteralPath $idx
  Write-Output ('index.html: ' + $f.Length + 'B  ' + $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
# entry chunk name + whether the new lite switch got bundled in
$assets = Join-Path $dist 'assets'
if (Test-Path -LiteralPath $assets) {
  $hit = Select-String -Path (Join-Path $assets '*.js') -Pattern 'data-perf-lite' -List | Select-Object -First 5
  Write-Output ('perfMode in bundle: ' + [bool]$hit)
  if ($hit) { $hit | ForEach-Object { Write-Output ('  ' + $_.Path) } }
  $main = Get-ChildItem -LiteralPath $assets -Filter 'main-*.js' | Select-Object -First 3
  $main | ForEach-Object { Write-Output ('  chunk ' + $_.Name + ' ' + $_.Length + 'B') }
}
