package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class WeatherSimulator {
    private final Map<String, RiskSegment> riskByEdge = new ConcurrentHashMap<>();
    /** 手动注入的边，实时气象同步不会覆盖 */
    private final Set<String> manualEdges = ConcurrentHashMap.newKeySet();
    /** 路径解析器（由 RouteService 注册）：给定起止节点返回路径上的边 ID 列表 */
    private volatile PathResolver pathResolver;
    /** 风险变化回调（由 RouteAgentService 注册）：灾害注入/清除/实时气象同步时立即触发 agent 重算 */
    private volatile Runnable changeListener;

    @FunctionalInterface
    public interface PathResolver {
        List<String> edgesOnPath(String fromNodeId, String toNodeId);
    }

    public void setPathResolver(PathResolver resolver) {
        this.pathResolver = resolver;
    }

    /** 注册风险变化监听（agent 实时守护用） */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** 任何风险增删改后调用，通知 agent 立即重算路线 */
    private void fireChange() {
        Runnable listener = changeListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // 重算失败不影响风险注入本身
            }
        }
    }

    /**
     * 预置灾害场景（计划书 3.2 的 5 组典型场景）。
     * penaltyMultiplier 语义：>80mm/h 暴雨 -> 100（等效中断）；泥石流 >0.7 -> 1000（切断）。
     * 说明：真实 OSM 路网中"谅山—北江"等路段由多条边组成，
     * 因此 LANDSLIDE/HEAT/TYPHOON 使用 fromNodeId/toNodeId 做整段路径注入，
     * 口岸场景 RAIN_YGG/FOG_MC 保留单边（口岸通道边 ID 固定）。
     * <p>
     * simParams：注入该灾害时同步拉到判定/熔断值的沙盘气象滑杆参数（扁平键与
     * DecisionSandboxService.setSimParams 一致），让"点卡片"与"手动拖滑杆过阈值"
     * 在界面上完全等价；文案里的阈值与 RoadRiskIndex / TyphoonRiskRule /
     * CargoSafetyIndex 的真实判据统一，不再各说各话。
     */
    public record Scenario(String id, String name, String description,
                           String edgeId, String fromNodeId, String toNodeId,
                           double penaltyMultiplier, String reason,
                           Map<String, Double> simParams) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("description", description);
            map.put("edgeId", edgeId);
            if (fromNodeId != null) map.put("fromNodeId", fromNodeId);
            if (toNodeId != null) map.put("toNodeId", toNodeId);
            map.put("penaltyMultiplier", penaltyMultiplier);
            map.put("reason", reason);
            map.put("simParams", simParams);
            return map;
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("RAIN_YGG", "暴雨·友谊关",
                    "12h 降雨量 ≥80mm 即熔断（友谊关阈值，本次注入 85mm），等效道路中断",
                    "E4", null, null, 100, "暴雨（12h降雨量≥80mm熔断）",
                    Map.of("yggRainMm", 85.0)),
            new Scenario("FOG_MC", "大雾·芒街",
                    "能见度 <500m 判定浓雾（与路面风险指数同一判据，本次注入 400m），芒街口岸通关受阻",
                    "E9", null, null, 50, "大雾（能见度<500m）",
                    Map.of("mcVisibilityM", 400.0)),
            new Scenario("LANDSLIDE", "泥石流·越北山区",
                    "谅山—北江段山地次生灾害，泥石流风险>0.7，直接切断（地质指标，不由气象滑杆驱动）",
                    null, "LS", "BG", 1000, "山地次生灾害（泥石流风险>0.7）",
                    Map.of()),
            new Scenario("HEAT", "高温·冷链",
                    "北江—河内段 ≥38°C 记高温风险（本次注入 38°C），冷链货损自 ≥30°C 起累积",
                    null, "BG", "HN", 10, "高温（≥38°C，冷链货损风险）",
                    Map.of("yggTempC", 38.0, "mcTempC", 38.0)),
            new Scenario("TYPHOON", "台风·广宁沿海",
                    "台风熔断判据：气压≤990hPa 且 风速≥60km/h（本次注入 985hPa / 65km/h，芒街侧）",
                    null, "HL", "HD", 80, "台风（气压≤990hPa且风速≥60km/h）",
                    Map.of("mcPressureHpa", 985.0, "mcWindKph", 65.0))
    );

    public RiskSegment injectRisk(RiskSegment riskSegment) {
        if (riskSegment == null || riskSegment.getEdgeId() == null || riskSegment.getEdgeId().isBlank()) {
            throw new IllegalArgumentException("edgeId is required for risk injection");
        }
        if (riskSegment.getSeverity() == null || riskSegment.getSeverity().isBlank()) {
            riskSegment.setSeverity("MEDIUM");
        }
        riskByEdge.put(riskSegment.getEdgeId(), riskSegment);
        manualEdges.add(riskSegment.getEdgeId());
        fireChange();
        return riskSegment;
    }

    /** 实时气象同步写入：手动注入的边优先级更高，不被覆盖。 */
    public RiskSegment applyRealRisk(RiskSegment riskSegment) {
        if (riskSegment == null || riskSegment.getEdgeId() == null || riskSegment.getEdgeId().isBlank()) {
            return null;
        }
        if (manualEdges.contains(riskSegment.getEdgeId())) {
            return riskSegment;
        }
        if (riskSegment.getSeverity() == null || riskSegment.getSeverity().isBlank()) {
            riskSegment.setSeverity("MEDIUM");
        }
        riskByEdge.put(riskSegment.getEdgeId(), riskSegment);
        fireChange();
        return riskSegment;
    }

    /** 清除全部实时风险（保留手动注入）。 */
    public void clearRealRisks() {
        riskByEdge.keySet().removeIf(k -> !manualEdges.contains(k));
        fireChange();
    }

    /**
     * 实时气象同步专用入口：一次性写入本轮全部实时风险，**只有风险集合真的变了才通知 agent**。
     *
     * <p>为什么要它：原来的同步路径是 {@code clearRealRisks()} + 逐条 {@code applyRealRisk()}，
     * 二者各自无条件 {@code fireChange()}。后台气象守护每 60 秒跑一轮，于是即便上游返回的数据
     * 与上一轮逐字相同（实况时次固定时就是这样），系统仍会每分钟被唤醒一次：
     * 全量重算所有关注路线（每条一轮 Dijkstra）＋ 向所有 SSE 订阅者广播完整路线载荷。
     * 演示机是 2 核小机器时，这记空转正好落在车标动画的帧间隙上，表现为周期性顿挫。</p>
     *
     * <p>语义与旧路径逐条等价：手动注入的边优先级更高、实时同步既不覆盖也不删除；
     * 差别只在于「写了相同内容」不再算一次变化。判定按 reason/severity/penalty 三字段比对，
     * 内容不同（如降雨从 20mm 涨到 50mm）照旧触发重算与广播。</p>
     *
     * @param next 本轮实时风险全集（不含手动注入的边）
     * @return 是否真的发生了变化（true = 已通知 agent 重算）
     */
    public synchronized boolean syncRealRisks(List<RiskSegment> next) {
        Map<String, RiskSegment> wanted = new LinkedHashMap<>();
        if (next != null) {
            for (RiskSegment r : next) {
                if (r == null || r.getEdgeId() == null || r.getEdgeId().isBlank()) {
                    continue;
                }
                if (r.getSeverity() == null || r.getSeverity().isBlank()) {
                    r.setSeverity("MEDIUM");
                }
                wanted.put(r.getEdgeId(), r);
            }
        }
        boolean changed = false;
        // 1) 本轮不再成立的实时风险先摘掉（手动注入的边不动，与 clearRealRisks 同一判据）
        for (Iterator<String> it = riskByEdge.keySet().iterator(); it.hasNext(); ) {
            String eid = it.next();
            if (manualEdges.contains(eid) || wanted.containsKey(eid)) {
                continue;
            }
            it.remove();
            changed = true;
        }
        // 2) 新增或内容变化的才写入；内容完全相同的跳过，不记为变化
        for (Map.Entry<String, RiskSegment> e : wanted.entrySet()) {
            if (manualEdges.contains(e.getKey())) {
                continue;
            }
            RiskSegment cur = riskByEdge.get(e.getKey());
            if (cur != null && sameRisk(cur, e.getValue())) {
                continue;
            }
            riskByEdge.put(e.getKey(), e.getValue());
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return changed;
    }

    /** 两条实时风险是否内容一致（agent 是否值得为这次同步重算一次的判据） */
    private static boolean sameRisk(RiskSegment a, RiskSegment b) {
        return Objects.equals(a.getReason(), b.getReason())
                && Objects.equals(a.getSeverity(), b.getSeverity())
                && Objects.equals(a.getPenaltyMultiplier(), b.getPenaltyMultiplier());
    }

    public void clearRisk(String edgeId) {
        if (edgeId == null || edgeId.isBlank()) {
            throw new IllegalArgumentException("edgeId cannot be blank");
        }
        riskByEdge.remove(edgeId);
        manualEdges.remove(edgeId);
        fireChange();
    }

    public void clearAll() {
        riskByEdge.clear();
        manualEdges.clear();
        fireChange();
    }

    public Map<String, RiskSegment> currentRiskMap() {
        return Map.copyOf(riskByEdge);
    }

    public List<RiskSegment> currentRisks() {
        return new ArrayList<>(riskByEdge.values());
    }

    /** 返回全部预置场景，供前端渲染"模拟灾害注入"按钮组 */
    public List<Map<String, Object>> listScenarios() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            result.add(s.toMap());
        }
        return result;
    }

    /** 按场景 ID 注入对应气象风险，返回注入后的风险段（路径场景返回首段，见 currentRisks 全量） */
    public RiskSegment applyScenario(String scenarioId) {
        Scenario scenario = findScenario(scenarioId);
        RiskSegment risk = new RiskSegment(
                scenario.edgeId(),
                scenario.reason(),
                severityFor(scenario.penaltyMultiplier()),
                scenario.penaltyMultiplier()
        );
        if (scenario.fromNodeId() != null && scenario.toNodeId() != null) {
            // 路径注入：整段（如谅山—北江）的每条边都注入相同风险，实现真实路网的"路段切断"
            if (pathResolver == null) {
                throw new IllegalStateException("pathResolver not registered; 请先初始化 RouteService");
            }
            List<String> edgeIds = pathResolver.edgesOnPath(scenario.fromNodeId(), scenario.toNodeId());
            if (edgeIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "no path found between " + scenario.fromNodeId() + " and " + scenario.toNodeId());
            }
            List<RiskSegment> injected = new ArrayList<>();
            for (String eid : edgeIds) {
                RiskSegment r = new RiskSegment(eid, scenario.reason(),
                        severityFor(scenario.penaltyMultiplier()), scenario.penaltyMultiplier());
                injected.add(injectRisk(r));
            }
            scenarioEdges.put(scenarioId, new java.util.HashSet<>(edgeIds));
            fireChange();
            return injected.isEmpty() ? risk : injected.get(0);
        }
        scenarioEdges.put(scenarioId, java.util.Set.of(scenario.edgeId()));
        RiskSegment injected = injectRisk(risk);
        fireChange();
        return injected;
    }

    /** 清除某场景注入的风险（路径场景清理整段边） */
    public void clearScenario(String scenarioId) {
        Scenario scenario = findScenario(scenarioId);
        Set<String> edges = scenarioEdges.remove(scenarioId);
        if (edges != null && !edges.isEmpty()) {
            for (String eid : edges) {
                RiskSegment current = riskByEdge.get(eid);
                if (current != null && scenario.reason().equals(current.getReason())) {
                    riskByEdge.remove(eid);
                    manualEdges.remove(eid);
                }
            }
            // 清除同样必须立刻通知 agent 重算广播：这里曾直接 return 跳过 fireChange，
            // 导致快速注入卡片撤销/场景清除后，司机端要等 15s 轮询才恢复路线
            fireChange();
            return;
        }
        RiskSegment current = riskByEdge.get(scenario.edgeId());
        if (current != null && scenario.reason().equals(current.getReason())) {
            riskByEdge.remove(scenario.edgeId());
        }
        fireChange();
    }

    /** 场景 ID -> 注入的边集合（用于路径场景清理） */
    private final Map<String, Set<String>> scenarioEdges = new ConcurrentHashMap<>();

    /** 某场景对应的沙盘气象滑杆快照（注入时把滑杆拉到该灾害的判定/熔断值）；无滑杆场景返回空 */
    public Map<String, Double> scenarioSimParams(String scenarioId) {
        return findScenario(scenarioId).simParams();
    }

    /** 撤销场景时：把本场景拉过的滑杆键回落到"安全中性值"（与 DecisionSandboxService.neutralSection 同口径） */
    public Map<String, Double> neutralizedScenarioSimParams(String scenarioId) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : scenarioSimParams(scenarioId).keySet()) {
            out.put(key, neutralValueOf(key));
        }
        return out;
    }

    /** 清除全部风险时用：所有场景拉过的滑杆键一并回落中性值 */
    public Map<String, Double> neutralAllScenarioSimParams() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Scenario s : SCENARIOS) {
            for (String key : s.simParams().keySet()) {
                out.put(key, neutralValueOf(key));
            }
        }
        return out;
    }

    private static double neutralValueOf(String flatKey) {
        if (flatKey.endsWith("RainMm")) return 0;
        if (flatKey.endsWith("PressureHpa")) return 1013;
        if (flatKey.endsWith("WindKph")) return 20;
        if (flatKey.endsWith("TempC")) return 28;
        if (flatKey.endsWith("HumidityPct")) return 60;
        if (flatKey.endsWith("CloudPct")) return 30;
        if (flatKey.endsWith("VisibilityM")) return 10000;
        return 0;
    }

    private Scenario findScenario(String scenarioId) {
        for (Scenario s : SCENARIOS) {
            if (s.id().equals(scenarioId)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
    }

    private String severityFor(double penalty) {
        if (penalty >= 100) {
            return "CRITICAL";
        }
        if (penalty >= 30) {
            return "HIGH";
        }
        return "MEDIUM";
    }
}
