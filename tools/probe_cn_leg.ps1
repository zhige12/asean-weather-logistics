$ErrorActionPreference = 'Continue'
$body = '{"originId":"NN","destinationId":"HN","cargoType":"vegetables"}'
try {
  $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/routes/plan' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 90
} catch {
  Write-Host ("API ERR: " + $_.Exception.Message); exit 1
}
$rt = if ($r.route) { $r.route } else { $r }
Write-Host ("pathCoords=" + $rt.pathCoords.Count + " edges=" + $rt.pathEdgeIds.Count)
Write-Host ("前8条边: " + ($rt.pathEdgeIds[0..7] -join ', '))
Write-Host "--- 前12个坐标点 (lat,lon) ---"
for ($i = 0; $i -lt [Math]::Min(12, $rt.pathCoords.Count); $i++) {
  $p = $rt.pathCoords[$i]
  Write-Host ("  [{0}] {1:N5}, {2:N5}  span={3}" -f $i, $p[0], $p[1], ($rt.pathEdgeSpans[$i] -join '-'))
}
Write-Host "--- OSRM 测试: 南宁->崇左 ---"
try {
  $u = 'http://router.project-osrm.org/route/v1/driving/108.3665,22.8170;107.3640,22.3765?overview=full&geometries=geojson'
  $o = Invoke-RestMethod -Uri $u -TimeoutSec 45
  Write-Host ("OSRM OK code=" + $o.code + " 点数=" + $o.routes[0].geometry.coordinates.Count + " 距离=" + [math]::Round($o.routes[0].distance/1000,1) + "km")
} catch {
  Write-Host ("OSRM ERR: " + $_.Exception.Message)
}
