$ErrorActionPreference = 'SilentlyContinue'

Write-Output "=== C:\Users\Administrator subfolders ==="
Get-ChildItem C:\Users\Administrator -Directory -Force | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $gb = [math]::Round($size/1GB,2)
    Write-Output ("{0,-60} {1} GB" -f $_.Name, $gb)
}

Write-Output "`n=== AppData\Local subfolders ==="
Get-ChildItem C:\Users\Administrator\AppData\Local -Directory -Force | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $gb = [math]::Round($size/1GB,2)
    if ($gb -ge 0.15) { Write-Output ("{0,-50} {1} GB" -f $_.Name, $gb) }
}

Write-Output "`n=== AppData\Roaming subfolders ==="
Get-ChildItem C:\Users\Administrator\AppData\Roaming -Directory -Force | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $gb = [math]::Round($size/1GB,2)
    if ($gb -ge 0.15) { Write-Output ("{0,-50} {1} GB" -f $_.Name, $gb) }
}

Write-Output "`n=== C:\ProgramData subfolders ==="
Get-ChildItem C:\ProgramData -Directory -Force | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $gb = [math]::Round($size/1GB,2)
    if ($gb -ge 0.2) { Write-Output ("{0,-50} {1} GB" -f $_.Name, $gb) }
}

Write-Output "`n=== Big files on C:\ root and cleanable ==="
$clean = @(
    "C:\Windows\Temp",
    "C:\Windows\SoftwareDistribution\Download",
    "C:\Windows\Prefetch",
    "$env:LOCALAPPDATA\Temp"
)
foreach ($t in $clean) {
    if (Test-Path $t) {
        $size = (Get-ChildItem $t -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        Write-Output ("{0,-60} {1} GB" -f $t, [math]::Round($size/1GB,2))
    }
}
Write-Output "`nDone."
