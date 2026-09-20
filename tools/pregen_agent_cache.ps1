# 多智能体决策缓存预生成脚本（升级方案 §六）
# 用法：先启动后端（gradlew bootRun，Ollama 已运行），再执行本脚本。
# 会按三组演示场景推送气象数据 → 触发三智能体真实推理 → 结果落盘 decision-cache/scenario_*.json
# 演示时 GET /api/agents/analysis?scenarioId=xxx 优先读缓存，2 秒出结果（拔网线+模型冷启动双保险）。
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$base = "http://localhost:8080"

function Wait-Backend {
    foreach ($i in 1..30) {
        try { Invoke-RestMethod -Uri "$base/api/sandbox/state" -TimeoutSec 3 | Out-Null; return $true }
        catch { Start-Sleep -Seconds 2 }
    }
    return $false
}

function Pregen($scenarioId, $yggMm, $mcMm, $note) {
    Write-Host "== $scenarioId ($note) ==" -ForegroundColor Cyan
    Invoke-RestMethod -Uri "$base/api/sandbox/push?yggMm=$yggMm&mcMm=$mcMm" -Method Post -TimeoutSec 10 | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod -Uri "$base/api/agents/analysis?originId=NN&destinationId=HN&scenarioId=$scenarioId" -TimeoutSec 300
    $sw.Stop()
    $cache = if ($r.cacheHit) { "cache" } else { "fresh" }
    $ai = if ($r.aiPowered) { "AI" } else { "template" }
    Write-Host ("  {0}s [{1}/{2}] rec={3} agents={4}" -f [math]::Round($sw.Elapsed.TotalSeconds,1), $cache, $ai, $r.recommendation, ($r.agents.name -join '->'))
}

if (-not (Wait-Backend)) { Write-Host "backend not up" -ForegroundColor Red; exit 1 }

# 三组演示场景（覆盖：主场景暴雨熔断 / 低降雨正常 / 双口岸临界）
Pregen "heavy_rain" 85 62 "主场景：友谊关暴雨熔断，芒街备用"
Pregen "light_rain" 45 30 "低降雨：公路走廊正常通行"
Pregen "dual_risk"  78 68 "双临界：友谊关临近熔断线，芒街临近熔断线"

# 恢复主场景状态并收尾
Invoke-RestMethod -Uri "$base/api/sandbox/push?yggMm=85&mcMm=62" -Method Post -TimeoutSec 10 | Out-Null
Write-Host "`ncache files:" -ForegroundColor Cyan
Get-ChildItem "$PSScriptRoot\..\decision-cache\scenario_*.json" -ErrorAction SilentlyContinue | ForEach-Object { "  $($_.Name)  $([math]::Round($_.Length/1KB,1))KB" }
Write-Host "done. 演示时调用: GET $base/api/agents/analysis?scenarioId=heavy_rain" -ForegroundColor Green
