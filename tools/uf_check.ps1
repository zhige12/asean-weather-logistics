$ErrorActionPreference = 'SilentlyContinue'
$h = 'C:\Users\Administrator'
$folders = @('Desktop','Downloads','Documents','Pictures','Videos','Music','3D Objects','Favorites','Contacts')
foreach ($f in $folders) {
    $p = Join-Path $h $f
    if (Test-Path $p) {
        $files = Get-ChildItem $p -Recurse -File -Force
        $s = ($files | Measure-Object Length -Sum).Sum
        $cnt = ($files | Measure-Object).Count
        Write-Output ('{0,-15} {1,6} files, {2} MB' -f $f, $cnt, [math]::Round($s/1MB,0))
    } else {
        Write-Output ('{0,-15} (missing)' -f $f)
    }
}
Write-Output ''
Write-Output 'Sample of Documents/Desktop/Downloads top-level items:'
foreach ($f in @('Documents','Desktop','Downloads')) {
    $p = Join-Path $h $f
    Write-Output ("--- " + $f + " ---")
    Get-ChildItem $p -Force | Select-Object -First 8 | ForEach-Object { Write-Output ("   " + $_.Name) }
}
