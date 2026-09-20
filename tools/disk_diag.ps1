$ErrorActionPreference = 'SilentlyContinue'

Write-Output "=== Disk space ==="
Get-PSDrive C,D | ForEach-Object {
    $used = [math]::Round($_.Used/1GB,1)
    $free = [math]::Round($_.Free/1GB,1)
    Write-Output ("{0}: Used {1} GB, Free {2} GB" -f $_.Name, $used, $free)
}

$home = $env:USERPROFILE
$targets = @(
    "$home\Downloads",
    "$home\Desktop",
    "$home\Documents",
    "$home\Pictures",
    "$home\Videos",
    "$home\Music",
    "$home\.gradle",
    "$home\.m2",
    "$home\.npm",
    "$home\.cache",
    "$home\.conda",
    "$home\.julia",
    "$home\AppData\Local\Temp",
    "$home\AppData\Local\pip",
    "$home\AppData\Local\npm-cache",
    "$home\AppData\Local\Programs",
    "$home\AppData\Local\JetBrains",
    "$home\AppData\Roaming\JetBrains",
    "$home\AppData\Local\Google\Chrome\User Data",
    "$home\AppData\Local\Microsoft\Edge\User Data",
    "$home\AppData\Local\Docker",
    "$home\AppData\Local\Yarn",
    "$home\AppData\Local\pnpm",
    "$home\AppData\Local\Microsoft\Windows\INetCache"
)

Write-Output "`n=== Top-level C: folders ==="
Get-ChildItem C:\ -Directory | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $gb = [math]::Round($size/1GB,2)
    if ($gb -ge 0.5) { Write-Output ("{0,-40} {1} GB" -f $_.FullName, $gb) }
}

Write-Output "`n=== User profile folder sizes ==="
foreach ($t in $targets) {
    if (Test-Path $t) {
        $size = (Get-ChildItem $t -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        $gb = [math]::Round($size/1GB,2)
        if ($gb -ge 0.2) { Write-Output ("{0,-75} {1} GB" -f $t, $gb) }
    }
}

Write-Output "`n=== Pagefile / hibernate ==="
Get-CimInstance Win32_PageFileUsage | ForEach-Object { Write-Output ("Pagefile: {0} MB" -f $_.AllocatedBaseSize) }
$hiber = "C:\hiberfil.sys"
if (Test-Path $hiber) {
    $s = (Get-Item $hiber -Force).Length
    Write-Output ("hiberfil.sys: {0} GB" -f [math]::Round($s/1GB,1))
}
$pf = "C:\pagefile.sys"
if (Test-Path $pf) {
    $s = (Get-Item $pf -Force).Length
    Write-Output ("pagefile.sys: {0} GB" -f [math]::Round($s/1GB,1))
}
Write-Output "`nDone."
