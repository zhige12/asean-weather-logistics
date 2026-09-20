$ErrorActionPreference = 'SilentlyContinue'

Write-Output "=== .condarc (user) ==="
if (Test-Path 'C:\Users\Administrator\.condarc') { Get-Content 'C:\Users\Administrator\.condarc' } else { Write-Output "  (no user .condarc)" }
Write-Output "=== .condarc (system in miniconda3) ==="
if (Test-Path 'C:\Users\Administrator\miniconda3\.condarc') { Get-Content 'C:\Users\Administrator\miniconda3\.condarc' } else { Write-Output "  (none)" }

Write-Output ""
Write-Output "=== D:\conda-envs\drl content (top) ==="
Get-ChildItem 'D:\conda-envs\drl' -Force | Select-Object -First 25 | ForEach-Object { Write-Output ("  " + $_.Name) }

Write-Output ""
Write-Output "=== python in D:\conda-envs\drl ? ==="
$py = 'D:\conda-envs\drl\python.exe'
if (Test-Path $py) {
    Write-Output "  python.exe exists"
    & $py --version 2>&1
    & $py -c "import importlib.util as u; print('torch:', bool(u.find_spec('torch')), 'numpy:', bool(u.find_spec('numpy')), 'gymnasium:', bool(u.find_spec('gymnasium')))" 2>&1
} else {
    Write-Output "  NO python.exe -> likely leftover/junk"
}
Write-Output "  size: " + ((Get-ChildItem 'D:\conda-envs\drl' -Recurse -File -Force | Measure-Object Length -Sum).Sum /1MB)

Write-Output ""
Write-Output "=== conda info envs_dirs / pkgs_dirs ==="
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' config --show envs_dirs 2>&1
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' config --show pkgs_dirs 2>&1

Write-Output ""
Write-Output "=== D:\dev-cache subdirs (what is it) ==="
Get-ChildItem 'D:\dev-cache' -Directory -Force | ForEach-Object { Write-Output ("  " + $_.Name) }
Get-ChildItem 'D:\dev-cache' -Force | Where-Object { -not $_.PSIsContainer } | Select-Object -First 10 | ForEach-Object { Write-Output ("  [F] " + $_.Name) }
