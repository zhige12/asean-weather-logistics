$ErrorActionPreference = 'Stop'

# Locate OpenVPN install dir
$ovpnDir = $null
foreach ($p in @('C:\Program Files\OpenVPN', 'C:\Program Files (x86)\OpenVPN')) {
  if (Test-Path (Join-Path $p 'bin\openvpn.exe')) { $ovpnDir = $p; break }
}
if (-not $ovpnDir) {
  Write-Output 'ERROR: openvpn.exe not found under Program Files'
  exit 1
}
Write-Output "OpenVPN dir: $ovpnDir"

$cfgDir = Join-Path $ovpnDir 'config'
if (-not (Test-Path $cfgDir)) { New-Item -ItemType Directory -Path $cfgDir | Out-Null }

$src = 'D:\Temp\gdmain-vpn'
$files = @('gdmain.ovpn','gdmain_auth.conf','gdmain_ca.crt','gdmain_client.crt','gdmain_client.key','gdmain_ta.key')
foreach ($f in $files) {
  $s = Join-Path $src $f
  $d = Join-Path $cfgDir $f
  Copy-Item -LiteralPath $s -Destination $d -Force
  Write-Output "COPIED $f"
}

# Ensure auth-user-pass uses the auth file (no interactive prompt)
$ovpnPath = Join-Path $cfgDir 'gdmain.ovpn'
$content = Get-Content -LiteralPath $ovpnPath -Raw
$content = $content -replace '(?m)^auth-user-pass\s*$', 'auth-user-pass gdmain_auth.conf'
Set-Content -LiteralPath $ovpnPath -Value $content -Encoding ASCII
Write-Output 'PATCHED auth-user-pass -> gdmain_auth.conf'

Write-Output 'SETUP-DONE'
