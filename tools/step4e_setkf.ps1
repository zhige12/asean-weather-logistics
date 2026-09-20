Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class KF2 {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern int SHSetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, string pszPath);
    [DllImport("shell32.dll")]
    public static extern int SHGetKnownFolderPath(ref Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr pszPath);
}
"@

$base = 'D:\Users\Administrator'
$folders = @{
    '374DE290-123F-4565-9164-39C4925E467B' = 'Downloads'
    'B4BFCC3A-DB2C-424C-B029-7FE99A87C641' = 'Desktop'
    'FDD39AD0-238F-46AF-ADB4-6C85480369C7' = 'Documents'
    '33E28130-4E1E-4676-835A-98395C3BC3BB' = 'Pictures'
    '18989B1D-99B5-455B-841C-AB7C74E4DDFC' = 'Videos'
    '4BD8D571-6D19-48D3-BE97-422220080E43' = 'Music'
}

foreach ($guid in $folders.Keys) {
    $name = $folders[$guid]
    $path = Join-Path $base $name
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    $g = [Guid]$guid
    $hr = [KF2]::SHSetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, $path)
    Write-Output ("Set {0,-10} -> {1}  (hr=0x{2:X8})" -f $name, $path, $hr)
}

Write-Output ""
Write-Output "=== Verify by SHGetKnownFolderPath ==="
foreach ($guid in $folders.Keys) {
    $name = $folders[$guid]
    $g = [Guid]$guid
    $ptr = [IntPtr]::Zero
    $hr = [KF2]::SHGetKnownFolderPath([ref]$g, 0, [IntPtr]::Zero, [ref]$ptr)
    $res = ''
    if ($hr -eq 0) {
        $res = [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)
        [System.Runtime.InteropServices.Marshal]::FreeCoTaskMem($ptr)
    } else { $res = "ERR 0x{0:X8}" -f $hr }
    Write-Output ("{0,-10} -> {1}" -f $name, $res)
}
