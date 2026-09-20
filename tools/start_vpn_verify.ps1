$ErrorActionPreference = 'Continue'
$ovpnExe = 'C:\Program Files\OpenVPN\bin\openvpn.exe'
$winConf = 'D:\Temp\gdmain-vpn\gdmain-win.ovpn'
$logFile = 'D:\Temp\gdmain-vpn\ovpn_run.log'
Remove-Item $logFile -Force -ErrorAction SilentlyContinue

Get-Process openvpn -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

$proc = Start-Process -FilePath $ovpnExe `
  -ArgumentList @('--config', "`"$winConf`"", '--verb', '3', '--log', "`"$logFile`"") `
  -PassThru
Write-Output "PID: $($proc.Id)"
Start-Sleep -Seconds 15

$alive = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($alive) { Write-Output 'openvpn ALIVE' } else { Write-Output 'openvpn DEAD'; $proc.Refresh() }

Write-Output '--- log tail ---'
if (Test-Path $logFile) { Get-Content $logFile -Tail 15 }

Write-Output '--- external net check ---'
$ext = Test-NetConnection -ComputerName 223.5.5.5 -Port 53 -WarningAction SilentlyContinue -InformationLevel Quiet
Write-Output ("  ext dns 223.5.5.5:53 -> " + $(if ($ext) {'OPEN'} else {'closed'}))

Write-Output '--- intranet probes ---'
foreach ($t in @('192.168.0.36:53','192.168.0.36:80','172.20.10.1:80','10.8.0.1:80')) {
  $ip,$port = $t.Split(':')
  $r = Test-NetConnection -ComputerName $ip -Port $port -WarningAction SilentlyContinue -InformationLevel Quiet
  Write-Output ("  $t -> " + $(if ($r) {'OPEN'} else {'closed/timeout'}))
}
Write-Output 'VERIFY-DONE'
