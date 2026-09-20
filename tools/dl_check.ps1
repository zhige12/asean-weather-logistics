$ErrorActionPreference = 'SilentlyContinue'
function Sz($p) { if (Test-Path $p) { $s=(Get-ChildItem $p -Recurse -File -Force | Measure-Object Length -Sum).Sum; return [math]::Round($s/1MB,0) } return 0 }

Write-Output "=== conda env list ==="
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' env list 2>&1

Write-Output ""
Write-Output "=== C:\Users\Administrator\miniconda3\envs ==="
Get-ChildItem 'C:\Users\Administrator\miniconda3\envs' -Force | ForEach-Object { Write-Output ("  {0}  ({1} MB)" -f $_.Name, (Sz $_.FullName)) }

Write-Output ""
Write-Output "=== D:\conda ==="
Get-ChildItem 'D:\conda' -Recurse -Directory -Depth 2 -Force -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Write-Output ("  " + $_.FullName) }
Write-Output ("  total: {0} MB" -f (Sz 'D:\conda'))

Write-Output ""
Write-Output "=== D:\conda-envs ==="
Get-ChildItem 'D:\conda-envs' -Force | ForEach-Object { Write-Output ("  {0}  ({1} MB)" -f $_.Name, (Sz $_.FullName)) }

Write-Output ""
Write-Output "=== Deep-learning related caches ==="
$caches = @{
  '.cache total (C)' = 'C:\Users\Administrator\.cache'
  '.cache\huggingface' = 'C:\Users\Administrator\.cache\huggingface'
  '.cache\torch' = 'C:\Users\Administrator\.cache\torch'
  '.matplotlib' = 'C:\Users\Administrator\.matplotlib'
  '.ollama' = 'C:\Users\Administrator\.ollama'
  'D:\dev-cache' = 'D:\dev-cache'
  'D:\Ollama' = 'D:\Ollama'
  'D:\Temp' = 'D:\Temp'
}
foreach ($k in $caches.Keys) {
  $mb = Sz $caches[$k]
  if ($mb -gt 0) { Write-Output ("  {0,-30} {1} MB" -f $k, $mb) }
}

Write-Output ""
Write-Output "=== .cache subdirs ==="
Get-ChildItem 'C:\Users\Administrator\.cache' -Directory -Force | ForEach-Object { Write-Output ("  {0}  ({1} MB)" -f $_.Name, (Sz $_.FullName)) }

Write-Output ""
Write-Output "=== DL-related env vars ==="
foreach ($v in @('CONDA_ENVS_PATH','CONDA_PKGS_DIRS','HF_HOME','HF_HUB_CACHE','TORCH_HOME','TRANSFORMERS_CACHE','OLLAMA_MODELS','XDG_CACHE_HOME','NVIDIA_CUDA_CACHE_PATH')) {
  $val = [Environment]::GetEnvironmentVariable($v,'User'); if (-not $val) { $val = [Environment]::GetEnvironmentVariable($v,'Machine') }
  Write-Output ("  {0} = {1}" -f $v, $(if ($val) { $val } else { '(not set)' }))
}

Write-Output ""
Write-Output "=== miniconda3 root size (MB) ==="
Write-Output ("  total: {0} MB" -f (Sz 'C:\Users\Administrator\miniconda3'))
Write-Output ("  pkgs:  {0} MB" -f (Sz 'C:\Users\Administrator\miniconda3\pkgs'))
