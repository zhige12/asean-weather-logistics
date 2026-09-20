$ErrorActionPreference = 'SilentlyContinue'
Write-Output "=== Proxy-related processes ==="
Get-Process | Where-Object { $_.ProcessName -match 'clash|verge|mihomo|steampp|watt|v2ray|singbox|nekoray|xray|proxy' } | Select-Object ProcessName, Id | Format-Table -AutoSize | Out-String -Width 100

Write-Output "=== Listening ports (789x / 1080x / 8888 etc) ==="
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in @(7890,7891,7897,7892,1080,1081,10808,10809,8888,8889,2080,20171,20172,2334,33210) } |
    Select-Object LocalAddress, LocalPort, OwningProcess | Sort-Object LocalPort -Unique | Format-Table -AutoSize | Out-String -Width 100

Write-Output "=== WinINET system proxy ==="
$reg = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
Write-Output ("  ProxyEnable: " + $reg.ProxyEnable + "  ProxyServer: " + $reg.ProxyServer)

Write-Output "=== Clash config location guess ==="
Get-ChildItem 'C:\Users\Administrator\AppData\Roaming\io.github.clash-verge-rev.clash-verge-rev','C:\Users\Administrator\AppData\Roaming\clash-verge','D:\Clash Verge' -Force -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Write-Output ("  " + $_.FullName) }
