$snap = Invoke-RestMethod -Uri 'http://localhost:8080/api/geocode/snap?lat=22.84&lng=108.30&label=NanningQingxiu' -TimeoutSec 20
Write-Host ("snap nodeId=" + $snap.nodeId + " name=" + $snap.nodeName + " dist=" + $snap.distanceKm + "km")
$nid = $snap.nodeId
$body = @{ originId = $nid; destinationId = 'HN'; period = 'CURRENT'; cargoType = 'cold' } | ConvertTo-Json
$r = Invoke-RestMethod -Uri 'http://localhost:8080/api/route/plan' -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 40
$keys = $r.PSObject.Properties.Name
Write-Host ("plan 返回字段: " + ($keys -join ','))
Write-Host ("originId=" + $r.originId + " distanceKm=" + $r.distanceKm + " totalHours=" + $r.totalHours)
if ($r.pathNodeIds) {
  Write-Host ("pathNodeIds count=" + $r.pathNodeIds.Count + " first=" + $r.pathNodeIds[0] + " last=" + $r.pathNodeIds[-1])
} elseif ($r.route) {
  Write-Host ("route.pathNodeIds count=" + $r.route.pathNodeIds.Count)
}
