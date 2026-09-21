# Set Ethernet (ifIndex 8) to static 192.168.200.140 (URL baked into driver APK)
# Revert to DHCP:
#   netsh interface ip set address name=8 source=dhcp
#   netsh interface ip set dns name=8 source=dhcp
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'ERROR: needs admin'
    exit 1
}
# make sure .140 is really free before taking it
if (Test-Connection -ComputerName 192.168.200.140 -Count 1 -Quiet) {
    Write-Host 'ABORT: .140 already online, taken by another device'
    exit 2
}
netsh interface ip set address name=8 static 192.168.200.140 255.255.255.0 192.168.200.1
netsh interface ip set dns name=8 static 114.114.114.114
netsh interface ip add dns name=8 8.8.4.4 index=2
ipconfig | Select-String '192.168.200'
Start-Sleep -Seconds 2
try {
    $code = (Invoke-WebRequest -Uri 'http://192.168.200.140:8080/driver.html' -UseBasicParsing -TimeoutSec 10).StatusCode
    Write-Host "driver.html HTTP $code"
} catch {
    Write-Host "driver.html FAILED: $($_.Exception.Message)"
}
