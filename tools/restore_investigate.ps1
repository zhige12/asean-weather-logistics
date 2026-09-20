$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== 1. Current Desktop resolution ==="
$sh = New-Object -ComObject Shell.Application
foreach ($p in @('shell:Desktop')) {
    try { $it = $sh.Namespace($p); Write-Output ("  shell:Desktop -> " + $it.Self.Path) } catch {}
}

Write-Output "=== 2. Public Desktop (C:\Users\Public\Desktop) ==="
Get-ChildItem 'C:\Users\Public\Desktop' -Force | ForEach-Object { Write-Output ("  " + $_.Name) }

Write-Output "=== 3. Current user desktop content ==="
$cur = $sh.Namespace('shell:Desktop').Self.Path
$items = Get-ChildItem $cur -Force -ErrorAction SilentlyContinue
if ($items) { $items | Select-Object -First 30 | ForEach-Object { Write-Output ("  " + $_.Name) } } else { Write-Output "  (empty)" }

Write-Output "=== 4. All user profiles in C:\Users ==="
Get-ChildItem 'C:\Users' -Directory -Force | ForEach-Object { Write-Output ("  " + $_.Name) }

Write-Output "=== 5. Desktop registry current value ==="
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"

Write-Output "=== 6. Look for user Desktop dirs anywhere with content (C and D) ==="
$roots = @('C:\Users','D:\Users','D:\')
foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    Get-ChildItem $r -Directory -Recurse -Depth 2 -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'Desktop' } |
        ForEach-Object {
            $cnt = (Get-ChildItem $_.FullName -Force -ErrorAction SilentlyContinue | Measure-Object).Count
            Write-Output ("  {0}  ({1} items)" -f $_.FullName, $cnt)
        }
}

Write-Output "=== 7. Registry backup check: Desktop original location from HKCU... \SessionInfo? ==="
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" 2>nul
