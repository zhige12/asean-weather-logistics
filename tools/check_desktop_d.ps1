$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== D:\桌面 content ==="
$items = Get-ChildItem 'D:\桌面' -Force
$items | Select-Object -First 60 | ForEach-Object {
    $t = if ($_.PSIsContainer) { 'DIR ' } else { '{0,8} KB' -f [math]::Round($_.Length/1KB,0) }
    Write-Output ("  [{0}] {1}  ({2})" -f $t, $_.Name, $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))
}
Write-Output ("Total items: " + ($items | Measure-Object).Count)
$all = Get-ChildItem 'D:\桌面' -Recurse -File -Force
Write-Output ("Total files: {0}, size: {1} MB" -f ($all | Measure-Object).Count, [math]::Round(($all | Measure-Object Length -Sum).Sum/1MB,0))

Write-Output "=== desktop.ini in D:\桌面 (check redirection hint) ==="
if (Test-Path 'D:\桌面\desktop.ini') { Get-Content 'D:\桌面\desktop.ini' } else { Write-Output '  (none)' }

Write-Output "=== D:\Users tree ==="
Get-ChildItem 'D:\Users' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output ("  D:\Users\" + $_.Name)
    Get-ChildItem $_.FullName -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ("    " + $_.Name) }
}

Write-Output "=== D:\Documents content (top 20) ==="
Get-ChildItem 'D:\Documents' -Force -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Write-Output ("  " + $_.Name) }
