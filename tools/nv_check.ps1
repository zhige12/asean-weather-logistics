$ErrorActionPreference = 'SilentlyContinue'
Get-ChildItem 'C:\ProgramData\NVIDIA Corporation\NVIDIA App' -Directory -Force | ForEach-Object {
    $s = (Get-ChildItem $_.FullName -Recurse -File -Force | Measure-Object Length -Sum).Sum
    Write-Output ('{0,-35} {1} MB' -f $_.Name, [math]::Round($s/1MB,0))
}
