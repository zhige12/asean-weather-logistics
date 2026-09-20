$ErrorActionPreference = 'Continue'
$pts = '108.3665,22.8170;107.3640,22.3765'
$urls = @(
  'http://router.project-osrm.org/route/v1/driving/' + $pts + '?overview=full&geometries=geojson',
  'https://routing.openstreetmap.de/routed-car/route/v1/driving/' + $pts + '?overview=full&geometries=geojson'
)
foreach ($u in $urls) {
  Write-Host ("TRY " + $u.Substring(0, [Math]::Min(60, $u.Length)))
  try {
    $o = Invoke-RestMethod -Uri $u -TimeoutSec 45
    Write-Host ("  OK code=" + $o.code + " 点数=" + $o.routes[0].geometry.coordinates.Count + " 距离=" + [math]::Round($o.routes[0].distance/1000,1) + "km")
  } catch {
    Write-Host ("  ERR: " + $_.Exception.Message)
  }
}
