$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== C:\Users profiles ==="
Get-ChildItem 'C:\Users' -Force | ForEach-Object { Write-Output ("  {0}  [{1}]" -f $_.Name, $_.Attributes) }

Write-Output "=== Public Desktop full ==="
Get-ChildItem 'C:\Users\Public\Desktop' -Force | ForEach-Object { Write-Output ("  " + $_.Name) }

Write-Output "=== HKCU User Shell Folders - Desktop & others ==="
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v Desktop

Write-Output "=== Does shell:Desktop resolve ==="
$sh = New-Object -ComObject Shell.Application
$d = $sh.Namespace('shell:Desktop')
Write-Output ("  resolved: " + $d.Self.Path)

Write-Output "=== Search drives C:/D: for folders named Desktop or containing user files (depth 3) ==="
foreach ($r in @('C:\','D:\')) {
    Get-ChildItem $r -Directory -Depth 2 -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @('Desktop','桌面') -and $_.FullName -notlike '*Windows*' -and $_.FullName -notlike '*ProgramData*' -and $_.FullName -notlike '*System Volume*' -and $_.FullName -notlike '*$Recycle*' } |
        ForEach-Object {
            $n = (Get-ChildItem $_.FullName -Force | Measure-Object).Count
            Write-Output ("  {0}  ({1} top items)" -f $_.FullName, $n)
        }
}

Write-Output "=== Desktop items count via Explorer actual (Public+user merged) ==="
$ui = $d.Items()
Write-Output ("  visible items now: " + $ui.Count)
