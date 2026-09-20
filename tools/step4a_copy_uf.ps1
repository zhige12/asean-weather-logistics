$ErrorActionPreference = 'Continue'
$h = 'C:\Users\Administrator'
$dstRoot = 'D:\Users\Administrator'
$folders = @('Downloads','Documents','Pictures','Videos','Music','Desktop')

foreach ($f in $folders) {
    $src = Join-Path $h $f
    $dst = Join-Path $dstRoot $f
    if (-not (Test-Path $src)) {
        Write-Output ("SKIP (no source): " + $src)
        continue
    }
    New-Item -ItemType Directory -Path $dst -Force | Out-Null
    Write-Output ("Copying: " + $src)
    robocopy $src $dst /E /XJ /COPY:DAT /R:1 /W:1 /NFL /NDL /NP /MT:16 | Out-Null
    Write-Output ("  robocopy exit: " + $LASTEXITCODE)

    $srcCnt = (Get-ChildItem $src -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count
    $dstCnt = (Get-ChildItem $dst -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count
    $srcBytes = (Get-ChildItem $src -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $dstBytes = (Get-ChildItem $dst -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    Write-Output ("  source: {0} files / {1} MB" -f $srcCnt, [math]::Round($srcBytes/1MB,0))
    Write-Output ("  dest  : {0} files / {1} MB" -f $dstCnt, [math]::Round($dstBytes/1MB,0))
    if ($srcCnt -eq $dstCnt) { Write-Output "  MATCH" } else { Write-Output "  MISMATCH !!" }
}
Write-Output "Done."
