$ErrorActionPreference = 'Continue'

# 1) Stop NVIDIA container processes that may lock UpdateFramework files
Stop-Process -Name nvcontainer -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2) Delete UpdateFramework leftovers
$uf = 'C:\ProgramData\NVIDIA Corporation\NVIDIA App\UpdateFramework'
if (Test-Path $uf) {
    Remove-Item $uf -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path $uf) {
        Write-Output "UpdateFramework: still has locked items:"
        Get-ChildItem $uf -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ("  " + $_.FullName) }
    } else {
        Write-Output "UpdateFramework: fully removed"
    }
} else {
    Write-Output "UpdateFramework: already removed"
}

# 3) DXCache: delete file by file, skipping locked ones
$dx = 'C:\Users\Administrator\AppData\Local\NVIDIA\DXCache'
$removed = 0
$skipped = 0
if (Test-Path $dx) {
    Get-ChildItem $dx -Recurse -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            Remove-Item $_.FullName -Force -ErrorAction Stop
            $removed++
        } catch {
            $skipped++
        }
    }
    Write-Output ("DXCache: removed files={0}, skipped(locked)={1}" -f $removed, $skipped)
    $left = (Get-ChildItem $dx -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    Write-Output ("DXCache remaining: {0} MB" -f [math]::Round($left/1MB,1))
} else {
    Write-Output "DXCache: already removed"
}

Write-Output ""
Write-Output "=== C: free space ==="
Get-PSDrive C | ForEach-Object { Write-Output ("C: Free {0} GB" -f [math]::Round($_.Free/1GB,1)) }
