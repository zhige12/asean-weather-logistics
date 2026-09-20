$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== D:\  full listing ==="
$items = Get-ChildItem 'D:\桌面' -Force
Write-Output ("Total top-level items: " + ($items | Measure-Object).Count)
$items | ForEach-Object {
    $t = if ($_.PSIsContainer) { '[DIR ]' } else { '[FILE]' }
    Write-Output ("  {0} {1}" -f $t, $_.Name)
}
Write-Output ""
Write-Output "=== desktop.ini content (if any) ==="
if (Test-Path 'D:\桌面\desktop.ini') { Get-Content 'D:\桌面\desktop.ini' }

Write-Output ""
Write-Output "=== Does it contain Minecraft / CS2 related? ==="
$all = Get-ChildItem 'D:\桌面' -Recurse -Force -ErrorAction SilentlyContinue
$all | Where-Object { $_.Name -match 'minecraft|cs2|counter|我的世界|start|steam' } | ForEach-Object { Write-Output ("  " + $_.FullName) }
Write-Output "  (done)"
