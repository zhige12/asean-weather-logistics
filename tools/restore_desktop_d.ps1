Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class KFD {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern int SHSetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, string pszPath);
    [DllImport("shell32.dll")]
    public static extern int SHGetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr pszPath);
}
"@

$deskName = "$([char]0x684C)$([char]0x9762)"
$target = 'D:\' + $deskName

# registry: User Shell Folders + Shell Folders
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_EXPAND_SZ /d $target /f | Out-Null
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v "Desktop" /t REG_EXPAND_SZ /d $target /f | Out-Null
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders" /v "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}" /t REG_SZ /d $target /f | Out-Null

# authoritative API
$g = [Guid]'B4BFCC3A-DB2C-424C-B029-7FE99A87C641'
$hr = [KFD]::SHSetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, $target)
Write-Output ("SHSetKnownFolderPath -> D:\桌面  hr=0x{0:X8}" -f $hr)

# verify resolution
$ptr = [IntPtr]::Zero
$hr2 = [KFD]::SHGetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, [ref]$ptr)
if ($hr2 -eq 0) {
    Write-Output ("Desktop resolves to: " + [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr))
    [System.Runtime.InteropServices.Marshal]::FreeCoTaskMem($ptr)
}

# refresh explorer
Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Start-Process explorer.exe
Write-Output "explorer restarted - desktop should show all D:\ desktop items now"
