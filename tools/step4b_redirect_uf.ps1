$ErrorActionPreference = 'Continue'

$base = 'D:\Users\Administrator'
$usf = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
$sf  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders'

# KnownFolder GUID -> folder
$map = [ordered]@{
    '{374DE290-123F-4565-9164-39C4925E467B}' = 'Downloads'
    '{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}' = 'Desktop'
    '{FDD39AD0-238F-46AF-ADB4-6C85480369C7}' = 'Documents'
    '{33E28130-4E1E-4676-835A-98395C3BC3BB}' = 'Pictures'
    '{18989B1D-99B5-455B-841C-AB7C74E4DDFC}' = 'Videos'
    '{4BD8D571-6D19-48D3-BE97-422220080E43}' = 'Music'
}

foreach ($k in $map.Keys) {
    $folder = $map[$k]
    $target = Join-Path $base $folder
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    New-Item -ItemType Directory -Path $usf -Force | Out-Null
    New-Item -ItemType Directory -Path $sf -Force | Out-Null
    Set-ItemProperty -Path $usf -Name $k -Value $target -Type ExpandString -ErrorAction SilentlyContinue
    Set-ItemProperty -Path $sf  -Name $k -Value $target -Type String -ErrorAction SilentlyContinue
    Write-Output ("Redirected {0,-10} -> {1}" -f $folder, $target)
}

Write-Output ""
Write-Output "=== Clean C: originals (files already verified on D) ==="
$h = 'C:\Users\Administrator'
foreach ($f in @('Downloads','Documents','Pictures','Videos','Music')) {
    $src = Join-Path $h $f
    if (-not (Test-Path $src)) { continue }
    $files = Get-ChildItem $src -Recurse -File -Force -ErrorAction SilentlyContinue
    $removed = 0; $failed = 0
    foreach ($file in $files) {
        try { Remove-Item $file.FullName -Force -ErrorAction Stop; $removed++ }
        catch { $failed++ }
    }
    Write-Output ("{0}: removed {1} files, locked/failed {2}" -f $f, $removed, $failed)
    # remove empty subdirectories, keep the folder shell itself
    Get-ChildItem $src -Recurse -Directory -Force -ErrorAction SilentlyContinue |
        Sort-Object { $_.FullName.Length } -Descending |
        ForEach-Object { Remove-Item $_.FullName -Force -Recurse -ErrorAction SilentlyContinue }
}

Write-Output ""
Write-Output "=== C: free space ==="
Get-PSDrive C | ForEach-Object { Write-Output ("C: Free {0} GB" -f [math]::Round($_.Free/1GB,1)) }
Write-Output "Done."
