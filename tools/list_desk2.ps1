$ErrorActionPreference = 'SilentlyContinue'
$deskName = "$([char]0x684C)$([char]0x9762)"   # desktop
$desk = 'D:\' + $deskName
Write-Output ("Path: " + $desk + "  exists=" + (Test-Path $desk))
$items = Get-ChildItem $desk -Force
Write-Output ("Total top-level items: " + ($items | Measure-Object).Count)
$items | ForEach-Object {
    $t = if ($_.PSIsContainer) { '[DIR ]' } else { '[FILE]' }
    Write-Output ("  {0} {1}" -f $t, $_.Name)
}
Write-Output ""
Write-Output "=== desktop.ini ==="
$ini = Join-Path $desk 'desktop.ini'
if (Test-Path $ini) { Get-Content $ini }
