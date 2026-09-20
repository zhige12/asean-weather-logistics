$ErrorActionPreference = 'SilentlyContinue'

function FolderSize($p) {
    if (Test-Path $p) {
        $s = (Get-ChildItem $p -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        return [math]::Round($s/1MB,0)
    }
    return 0
}

Write-Output "=== ProgramData\NVIDIA Corporation ==="
Get-ChildItem 'C:\ProgramData\NVIDIA Corporation' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== AppData\Local\NVIDIA ==="
Get-ChildItem 'C:\Users\Administrator\AppData\Local\NVIDIA' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== AppData\Local\Programs ==="
Get-ChildItem 'C:\Users\Administrator\AppData\Local\Programs' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== miniconda3 ==="
Get-ChildItem 'C:\Users\Administrator\miniconda3' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}
Get-ChildItem 'C:\Users\Administrator\miniconda3\envs' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output ("  env: {0,-36} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== AppData\Local\JetBrains ==="
Get-ChildItem 'C:\Users\Administrator\AppData\Local\JetBrains' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== AppData\Roaming\JetBrains ==="
Get-ChildItem 'C:\Users\Administrator\AppData\Roaming\JetBrains' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== .qoder-cn ==="
Get-ChildItem 'C:\Users\Administrator\.qoder-cn' -Directory -Force | ForEach-Object {
    Write-Output ("{0,-40} {1} MB" -f $_.Name, (FolderSize $_.FullName))
}

Write-Output "`n=== WPS Cloud ==="
Write-Output ("WPS Cloud total: {0} MB" -f (FolderSize 'C:\Users\Administrator\WPS Cloud'))

Write-Output "`nDone."
