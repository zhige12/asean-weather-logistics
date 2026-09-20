$sh = New-Object -ComObject Shell.Application
foreach ($p in @('shell:Downloads','shell:MyComputerFolder\..','shell:Personal','shell:My Pictures','shell:My Video','shell:My Music','shell:Desktop')) {
    try {
        $item = $sh.Namespace($p)
        if ($item) { Write-Output ("{0} -> {1}" -f $p, $item.Self.Path) }
    } catch {}
}
Write-Output "---"
# Known Folder via API for Downloads & Documents
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class KF{[DllImport("shell32.dll")]public static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)]Guid id,int f,IntPtr t,out IntPtr p);}'
function Get-KnownPath($guid) {
    $ptr = [IntPtr]::Zero
    $g = [Guid]$guid
    [KF]::SHGetKnownFolderPath($g, 0x8000, [IntPtr]::Zero, [ref]$ptr) | Out-Null
    try { return [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr) }
    finally { [System.Runtime.InteropServices.Marshal]::FreeCoTaskMem($ptr) }
}
Write-Output ("Downloads  -> " + (Get-KnownPath '374DE290-123F-4565-9164-39C4925E467B'))
Write-Output ("Desktop    -> " + (Get-KnownPath 'B4BFCC3A-DB2C-424C-B029-7FE99A87C641'))
Write-Output ("Documents  -> " + (Get-KnownPath 'FDD39AD0-238F-46AF-ADB4-6C85480369C7'))
Write-Output ("Pictures   -> " + (Get-KnownPath '33E28130-4E1E-4676-835A-98395C3BC3BB'))
Write-Output ("Videos     -> " + (Get-KnownPath '18989B1D-99B5-455B-841C-AB7C74E4DDFC'))
Write-Output ("Music      -> " + (Get-KnownPath '4BD8D571-6D19-48D3-BE97-422220080E43'))
