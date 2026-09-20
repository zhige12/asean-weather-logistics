$ErrorActionPreference = 'Continue'

Write-Output "=== 1. Create .condarc with D: paths ==="
$condarc = 'C:\Users\Administrator\.condarc'
$content = @"
channels:
  - defaults
envs_dirs:
  - D:\conda\envs
  - C:\Users\Administrator\miniconda3\envs
pkgs_dirs:
  - D:\conda\pkgs
  - C:\Users\Administrator\miniconda3\pkgs
"@
New-Item -ItemType Directory -Path 'D:\conda\pkgs' -Force | Out-Null
Set-Content -Path $condarc -Value $content -Encoding UTF8
Write-Output "  .condarc written:"
Get-Content $condarc

Write-Output ""
Write-Output "=== 2. Move C pkgs cache to D (robocopy then delete C) ==="
$srcPkgs = 'C:\Users\Administrator\miniconda3\pkgs'
$dstPkgs = 'D:\conda\pkgs'
if (Test-Path $srcPkgs) {
    robocopy $srcPkgs $dstPkgs /E /XJ /COPY:DAT /R:1 /W:1 /NFL /NDL /NP /MT:16 | Out-Null
    $ec = $LASTEXITCODE
    Write-Output ("  robocopy exit: " + $ec)
    $s1 = (Get-ChildItem $srcPkgs -Recurse -File -Force | Measure-Object).Count
    $s2 = (Get-ChildItem $dstPkgs -Recurse -File -Force | Measure-Object).Count
    Write-Output ("  source files={0}  dest files={1}" -f $s1, $s2)
    if ($s1 -eq $s2 -and $ec -lt 8) {
        # verify then remove source cache contents
        Get-ChildItem $srcPkgs -Recurse -Force -ErrorAction SilentlyContinue |
            Sort-Object { $_.FullName.Length } -Descending |
            ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
        Write-Output "  C pkgs cache emptied"
    } else {
        Write-Output "  MISMATCH - source pkgs kept"
    }
}

Write-Output ""
Write-Output "=== 3. Verify conda now sees D envs ==="
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' config --show envs_dirs 2>&1
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' config --show pkgs_dirs 2>&1

Write-Output ""
Write-Output "=== 4. Test: can conda resolve env name 'drl' now? ==="
& 'C:\Users\Administrator\miniconda3\Scripts\conda.exe' env list 2>&1

Write-Output ""
Write-Output "Done."
