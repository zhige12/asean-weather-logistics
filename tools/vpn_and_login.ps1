$ErrorActionPreference = 'Continue'

# 1. cleanup lingering
Get-Process openvpn -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2. start openvpn from config dir
Push-Location 'C:\Program Files\OpenVPN\config'
$proc = Start-Process 'C:\Program Files\OpenVPN\bin\openvpn.exe' `
  -ArgumentList @('--config', 'gdmain.ovpn', '--verb', '3') `
  -PassThru -WindowStyle Hidden
Pop-Location
Write-Output ("PID=" + $proc.Id)
Start-Sleep -Seconds 15

$alive = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
Write-Output ("vpn_alive=" + [bool]$alive)

if ($alive) {
  Write-Output '--- running agent login ---'
  python 'D:\idea project\asean-weather-logistics\tools\vpn_login_agent.py'
}

# 3. keep vpn alive (do NOT kill here - user may need it)
Write-Output ('KEEPING VPN ALIVE PID=' + $proc.Id)
Write-Output 'ALL-DONE'
