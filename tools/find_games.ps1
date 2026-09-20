$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== .lnk files matching game keywords (common roots) ==="
$roots = @(
    'C:\ProgramData\Microsoft\Windows\Start Menu',
    'C:\Users\Administrator\AppData\Roaming\Microsoft\Windows\Start Menu',
    'C:\Users\Administrator\Desktop',
    'C:\Users\Public\Desktop',
    'D:\',
    'C:\Users\Administrator\AppData\Roaming\Microsoft\Windows\Recent'
)
$kw = @('minecraft','cs2','counter','csgo','perfect','steam','wangyi','launcher','mc','netease')
foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    Get-ChildItem $r -Recurse -File -Filter *.lnk -Force -ErrorAction SilentlyContinue |
        Where-Object { $n = $_.Name.ToLower(); $m = $kw | Where-Object { $n -like "*$_*" }; $m } |
        Select-Object -First 40 |
        ForEach-Object { Write-Output ("  {0}   [{1}]" -f $_.FullName, $_.LastWriteTime.ToString('yyyy-MM-dd')) }
}

Write-Output ""
Write-Output "=== Top-level D: dirs ==="
Get-ChildItem 'D:\' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Output ("  " + $_.Name) }

Write-Output ""
Write-Output "=== Steam libraryfolders.vdf ==="
foreach ($p in @('D:\steam\steamapps\libraryfolders.vdf','C:\Program Files (x86)\Steam\steamapps\libraryfolders.vdf')) {
    if (Test-Path $p) {
        Write-Output ("  FOUND: " + $p)
        Get-Content $p | Select-Object -First 40
    }
}

Write-Output ""
Write-Output "=== .lnk under D: top-level dirs (first 5 each) ==="
Get-ChildItem 'D:\' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $d = $_
    $lnks = Get-ChildItem $d.FullName -Filter *.lnk -File -Force -ErrorAction SilentlyContinue | Select-Object -First 5
    foreach ($l in $lnks) { Write-Output ("  " + $l.FullName) }
}

Write-Output ""
Write-Output "=== Any .lnk on user desktop referencing games (read target) ==="
Get-ChildItem 'C:\Users\Administrator\Desktop','C:\Users\Public\Desktop' -Filter *.lnk -File -Force -ErrorAction SilentlyContinue |
    ForEach-Object {
        $sh = New-Object -ComObject WScript.Shell
        $t = $sh.CreateShortcut($_.FullName).TargetPath
        Write-Output ("  {0}  ->  {1}" -f $_.Name, $t)
    }
