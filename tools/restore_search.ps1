$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== A. What is in C:\Users\Administrator\Desktop now ==="
Get-ChildItem 'C:\Users\Administrator\Desktop' -Force | ForEach-Object { Write-Output ("  {0}  [{1}]" -f $_.Name, $_.Attributes) }

Write-Output "=== B. D: root / common Desktop-ish dirs ==="
Get-ChildItem 'D:\' -Directory -Force | ForEach-Object { Write-Output ("  D:\" + $_.Name) }

Write-Output "=== C. OneDrive presence ==="
Get-ChildItem 'C:\Users\Administrator' -Directory -Force | Where-Object { $_.Name -like '*OneDrive*' -or $_.Name -like '*桌面*' -or $_.Name -like '*Desktop*' } | ForEach-Object { Write-Output ("  " + $_.FullName) }

Write-Output "=== D. Recycle Bin (recent) ==="
$shell = New-Object -ComObject Shell.Application
$rb = $shell.Namespace(10)
$rb.Items() | Select-Object -First 40 | ForEach-Object {
    $n = $_.Name
    $orig = $_.ExtendedProperty('System.Recycle.DeletedFrom')
    $date = $_.ExtendedProperty('System.DateDeleted')
    Write-Output ("  {0}  | deleted from: {1}  | at: {2}" -f $n, $orig, $date)
}

Write-Output "=== E. Volume Shadow Copies (could restore registry/history) ==="
vssadmin list shadows 2>&1 | Select-Object -First 25

Write-Output "=== F. Desktop original value in HKLM default / FolderDescriptions ==="
reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\FolderDescriptions\{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" 2>&1
