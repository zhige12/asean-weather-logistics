$ErrorActionPreference = 'SilentlyContinue'
# remove stray subkey created by earlier PS misuse
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders\Software" /f | Out-Null
# restart explorer to pick up new redirection
Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Start-Process explorer.exe
Write-Output "explorer restarted"
# verify shell folder resolution for known folders
$shell = New-Object -ComObject Shell.Application
foreach ($f in @('Downloads','Documents','Pictures','Videos','Music','Desktop')) {
    $kf = $shell.Namespace(0).ParseName($f)
    Write-Output ("{0}: {1}" -f $f, $kf.Path)
}
Write-Output ""
Get-PSDrive C,D | ForEach-Object {
    $name = $_.Name
    $free = [math]::Round($_.Free/1GB,1)
    $used = [math]::Round($_.Used/1GB,1)
    Write-Output ("{0}: Used {1} GB, Free {2} GB" -f $name, $used, $free)
}
