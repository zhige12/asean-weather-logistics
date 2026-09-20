$ErrorActionPreference = 'SilentlyContinue'
function Sz($p) { if (Test-Path $p) { $s=(Get-ChildItem $p -Recurse -File -Force | Measure-Object Length -Sum).Sum; return [math]::Round($s/1MB,0) } return 0 }

Write-Output "=== Disk now ==="
Get-PSDrive C,D | ForEach-Object { Write-Output ("  {0}: Used {1} GB / Free {2} GB" -f $_.Name, [math]::Round($_.Used/1GB,1), [math]::Round($_.Free/1GB,1)) }

Write-Output ""
Write-Output "=== C: current big user-app data (MB) ==="
$paths = @{
  'AppData\Local\Google\Chrome\User Data' = 'C:\Users\Administrator\AppData\Local\Google\Chrome\User Data'
  'AppData\Local\Microsoft\Edge\User Data' = 'C:\Users\Administrator\AppData\Local\Microsoft\Edge\User Data'
  'AppData\Local\JetBrains' = 'C:\Users\Administrator\AppData\Local\JetBrains'
  'AppData\Roaming\JetBrains' = 'C:\Users\Administrator\AppData\Roaming\JetBrains'
  'miniconda3 pkgs cache' = 'C:\Users\Administrator\miniconda3\pkgs'
  'AppData\Roaming\npm' = 'C:\Users\Administrator\AppData\Roaming\npm'
  'AppData\Local\NVIDIA(DXCache regrows)' = 'C:\Users\Administrator\AppData\Local\NVIDIA'
  'AppData\Local\Temp' = 'C:\Users\Administrator\AppData\Local\Temp'
  'Windows\SoftwareDistribution' = 'C:\Windows\SoftwareDistribution'
  'ProgramData\NVIDIA Corporation' = 'C:\ProgramData\NVIDIA Corporation'
  'Documents(D should be target)' = 'C:\Users\Administrator\Documents'
  '.ollama models' = 'C:\Users\Administrator\.ollama'
}
foreach ($k in $paths.Keys) {
  $mb = Sz $paths[$k]
  if ($mb -gt 0) { Write-Output ("  {0,-45} {1} MB" -f $k, $mb) }
}

Write-Output ""
Write-Output "=== Env vars redirecting caches to D? ==="
foreach ($v in @('GRADLE_USER_HOME','MAVEN_OPTS','PIP_CACHE_DIR','npm_config_cache','GRADLE_OPTS','ANDROID_HOME','OLLAMA_MODELS','JAVA_HOME')) {
  $val = [Environment]::GetEnvironmentVariable($v,'User')
  if (-not $val) { $val = [Environment]::GetEnvironmentVariable($v,'Machine') }
  Write-Output ("  {0} = {1}" -f $v, $(if ($val) { $val } else { '(not set)' }))
}

Write-Output ""
Write-Output "=== User env vars (all) ==="
[Environment]::GetEnvironmentVariables('User').GetEnumerator() | Sort-Object Key | ForEach-Object { Write-Output ("  {0} = {1}" -f $_.Key, $_.Value) } | Select-Object -First 40
