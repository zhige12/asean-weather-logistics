$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== miniconda3 envs ==="
Get-ChildItem 'C:\Users\Administrator\miniconda3\envs' -Force | ForEach-Object { Write-Output ("  " + $_.Name) }
if (-not (Get-ChildItem 'C:\Users\Administrator\miniconda3\envs' -Force)) { Write-Output "  (empty)" }
Write-Output "=== miniconda3 total size ==="
$s = (Get-ChildItem 'C:\Users\Administrator\miniconda3' -Recurse -File -Force | Measure-Object Length -Sum).Sum
Write-Output ("  {0} MB" -f [math]::Round($s/1MB,0))
Write-Output "=== C: user folders remaining content ==="
foreach ($f in @('Downloads','Documents','Pictures','Videos','Music')) {
    $p = "C:\Users\Administrator\$f"
    $cnt = (Get-ChildItem $p -Recurse -File -Force | Measure-Object).Count
    Write-Output ("  {0}: {1} files" -f $f, $cnt)
}
Write-Output "=== Disk ==="
Get-PSDrive C,D | ForEach-Object {
    Write-Output ("  {0}: Used {1} GB, Free {2} GB" -f $_.Name, [math]::Round($_.Used/1GB,1), [math]::Round($_.Free/1GB,1))
}
