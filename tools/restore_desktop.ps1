Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class KFR {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern int SHSetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, string pszPath);
    [DllImport("shell32.dll")]
    public static extern int SHGetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr pszPath);
}
"@

$g = [Guid]'B4BFCC3A-DB2C-424C-B029-7FE99A87C641'
$target = 'C:\Users\Administrator\Desktop'
New-Item -ItemType Directory -Path $target -Force | Out-Null

# restore registry values as well
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_EXPAND_SZ /d $target /f | Out-Null
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "Desktop" /t REG_EXPAND_SZ /d $target /f | Out-Null
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_SZ /d $target /f | Out-Null

$hr = [KFR]::SHSetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, $target)
Write-Output ("SHSetKnownFolderPath hr=0x{0:X8}" -f $hr)

# verify
$ptr = [IntPtr]::Zero
$hr2 = [KFR]::SHGetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, [ref]$ptr)
if ($hr2 -eq 0) {
    Write-Output ("Now resolves to: " + [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr))
    [System.Runtime.InteropServices.Marshal]::FreeCoTaskMem($ptr)
}

# content of the C desktop now
Write-Output "=== C:\Users\Administrator\Desktop content ==="
Get-ChildItem $target -Force | ForEach-Object { Write-Output ("  " + $_.Name) }

# restart explorer to refresh desktop
Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Start-Process explorer.exe
Write-Output "explorer restarted"
