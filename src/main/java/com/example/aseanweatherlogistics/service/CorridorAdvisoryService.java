package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 全域受阻建议：区域性灾害（台风、大范围暴雨）同时覆盖多条走廊时，给出「别往前塞」的处置建议。
 * <p>
 * 判据是<b>路网级别</b>的，不看沙盘那两个断面：
 * {@code 公路按当前风险图已搜不出任何路径（全部走廊硬熔断）} 且 {@code 平陆运河禁航}
 * ——此时继续算路只会得到「把熔断边降级成软惩罚」的兜底路线，等于从一个风险区换进另一个风险区。
 * 方案智能体早就有「双线风险 → 推荐原地等待」，但算路层仍照常产出一条向前的路线，
 * 大屏与司机端照旧派单/自动换线，两层口径打架。本服务把结论统一成一份 advisory 下发两端。
 * <p>
 * 动作口径（调度口径由用户确认）：
 * <ul>
 *   <li>{@link #ACTION_DEFER} 不在途（大屏规划态 / 还没发车）→ 暂缓发车，留在物流公司/起点待命；</li>
 *   <li>{@link #ACTION_RETURN} 在途且<b>未过口岸</b> → 沿当前路线折返起点（按真实路网给折返里程）；</li>
 *   <li>{@link #ACTION_HOLD} 在途且<b>已出境</b> → 折返要重新通关、不经济，建议就地/就近安全停靠等待。</li>
 * </ul>
 * 依赖里<b>不能</b>出现 {@link DecisionSandboxService}：沙盘 → {@link RouteAgentService} → 本服务，
 * 反向注入会在启动期构成循环依赖。熔断成因与受阻走廊一律从风险图（{@link WeatherSimulator}）如实读出，
 * 水运禁航直接问 {@link WaterRiskEngine}——与沙盘判定的是同一份状态。
 */
@Service
public class CorridorAdvisoryService {

    /** 等待复判时长：与方案智能体「原地等待」同一口径，避免两处各说一套 */
    public static final double WAIT_HOURS = SolutionAgent.WAIT_HOURS;

    public static final String ACTION_DEFER = "DEFER";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_HOLD = "HOLD";
    /** 建议类型码：前端按它匹配卡片样式与播报文案，不按 title 字符串判等 */
    public static final String CODE = "CORRIDOR_ALL_BLOCKED";

    private final RouteService routeService;
    private final WaterRiskEngine waterRiskEngine;
    private final WeatherSimulator weatherSimulator;

    public CorridorAdvisoryService(RouteService routeService, WaterRiskEngine waterRiskEngine,
                                   WeatherSimulator weatherSimulator) {
        this.routeService = routeService;
        this.waterRiskEngine = waterRiskEngine;
        this.weatherSimulator = weatherSimulator;
    }

    /**
     * 计算某条行程的全域受阻建议。
     *
     * @param nodeIds       当前行程线的节点序列（可空，空则无法定位车在哪，按不在途处理）
     * @param edgeIds       当前行程线的边序列（用于求出境点位置与折返里程）
     * @param progressRatio 车已走完全程的比例；负数 = 不在途（大屏规划态/未发车）
     * @return {@code null} 表示还能向前（仍有可用走廊，或水运还能走），不需要返回/等待建议
     */
    public Map<String, Object> forRoute(String originId, String destinationId,
                                        List<String> nodeIds, List<String> edgeIds,
                                        double progressRatio) {
        return forRoute(originId, destinationId, nodeIds, edgeIds, progressRatio,
                routeService.roadCorridorsAllFused(originId, destinationId));
    }

    /**
     * 同上，但公路侧判据由调用方带上来：路线本身就带着这个结论
     * （{@code RouteResponse.corridorAllFused} / 候选的 {@code softened}），
     * 再跑一次同样注定失败的搜索只是白烧 CPU。
     */
    public Map<String, Object> forRoute(String originId, String destinationId,
                                        List<String> nodeIds, List<String> edgeIds,
                                        double progressRatio, boolean roadAllFused) {
        if (originId == null || destinationId == null) {
            return null;
        }
        // 1) 公路侧：按当前风险图是否还搜得出路。搜得出就说明存在不穿灾区的走法，交给既有绕行
        if (!roadAllFused) {
            return null;
        }
        // 2) 水运侧：运河还能走就不算"无路可走"，由陆水联运方案（方案B）接管
        if (!waterRiskEngine.isWaterBlocked()) {
            return null;
        }

        double crossRatio = routeService.customsCrossRatio(edgeIds);
        boolean inTransit = progressRatio >= 0 && edgeIds != null && !edgeIds.isEmpty();
        // 线路本身不含口岸边（如国内段 NN→LJ）：视为未出境，可以折返
        boolean crossed = inTransit && crossRatio >= 0 && progressRatio > crossRatio;
        String action = !inTransit ? ACTION_DEFER : (crossed ? ACTION_HOLD : ACTION_RETURN);

        List<String> corridors = blockedCorridorLabels();
        String cause = causeOf(corridors);
        String waterReason = nz(waterRiskEngine.blockedReason(), "通航条件超限");
        String originName = nodeName(originId, originId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", CODE);
        m.put("action", action);
        m.put("roadAllFused", true);
        m.put("waterBlocked", true);
        m.put("waterReason", waterReason);
        m.put("blockedCorridors", corridors);
        m.put("cause", cause);
        m.put("waitHours", WAIT_HOURS);
        // 出境点在全程里程中的位置：各端可用本地进度自行复算动作
        //（司机端手上有实时 GPS 进度，比后端在推送时才知道的要准）
        m.put("customsCrossRatio", crossRatio);
        m.put("progressRatio", inTransit ? progressRatio : -1);
        m.put("crossedCustoms", crossed);
        m.put("originId", originId);
        m.put("originName", originName);
        m.put("destinationId", destinationId);
        m.put("destinationName", nodeName(destinationId, destinationId));

        String head = (corridors.isEmpty() ? "全部出境走廊" : String.join("、", corridors))
                + " 已" + fuseWord(cause) + "，平陆运河禁航（" + waterReason + "）";
        m.put("detail", head);

        if (ACTION_DEFER.equals(action)) {
            m.put("title", "全线受阻：建议暂缓发车");
            m.put("advice", head + "。现在发车等于从一个大风险区换进另一个，"
                    + "建议车辆留在物流公司/起点待命，约 " + hoursText() + " 后复判。");
        } else if (ACTION_RETURN.equals(action)) {
            m.put("title", "全线受阻：建议返回" + originName);
            String legText = appendReturnLeg(m, nodeIds, edgeIds, progressRatio, originId);
            m.put("advice", head + "。" + legText + "货物回库待命，约 " + hoursText() + " 后复判。");
        } else {
            m.put("title", "已出境且全线受阻：建议就近安全停靠等待");
            m.put("advice", head + "。车辆已越过口岸进入越方一侧，"
                    + "折返需重新通关、不经济；建议在口岸或服务区安全停靠、开启双闪，约 "
                    + hoursText() + " 后复判，请勿强行穿越已封闭路段。");
        }
        // 如实说明等待时长的来历：趋势引擎只给"转雨前兆"，没有可依据的解除时刻预报
        m.put("waitBasis", "等待时长按方案智能体既有口径取 " + hoursText()
                + "（当前无预计解除时刻的预报数据，到点复判，不作为承诺时效）");
        return m;
    }

    /** 折返里程：按真实路网从当前位置回到起点；不可达时如实说"无法算出折返路线" */
    private String appendReturnLeg(Map<String, Object> m, List<String> nodeIds, List<String> edgeIds,
                                   double progressRatio, String originId) {
        String cur = routeService.nodeAtProgress(nodeIds, edgeIds, progressRatio);
        Map<String, Object> leg = cur == null ? null : routeService.legSummary(cur, originId);
        m.put("currentNodeId", cur);
        m.put("currentNodeName", cur == null ? null : nodeName(cur, cur));
        if (leg == null) {
            m.put("returnDistanceKm", null);
            m.put("returnHours", null);
            m.put("returnLegBlocked", true);
            return "折返路线在当前风险图下无法算出，";
        }
        double km = ((Number) leg.get("distanceKm")).doubleValue();
        double h = ((Number) leg.get("hours")).doubleValue();
        boolean legBlocked = Boolean.TRUE.equals(leg.get("blocked"));
        m.put("returnDistanceKm", km);
        m.put("returnHours", Math.round(h * 10) / 10.0);
        m.put("returnLegBlocked", legBlocked);
        if (km <= 0) {
            return "车辆仍在" + nodeName(originId, originId) + "，";
        }
        return "建议按现路折返" + nodeName(cur, cur) + "方向回" + nodeName(originId, originId)
                + "（约 " + Math.round(km) + "km / " + (Math.round(h * 10) / 10.0) + "h"
                + (legBlocked ? "，折返沿线仍含熔断段，请减速慢行" : "") + "），";
    }

    /** 当前被硬熔断（penalty≥100）的边对应的走廊名，去重后按出现顺序给出 */
    private List<String> blockedCorridorLabels() {
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        if (riskMap == null || riskMap.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, RiskSegment> en : riskMap.entrySet()) {
            RiskSegment r = en.getValue();
            Double p = r == null ? null : r.getPenaltyMultiplier();
            if (p == null || p < 100.0) {
                continue;
            }
            String label = routeService.edgeLabel(en.getKey());
            if (label != null && !label.isBlank()) {
                names.add(label.trim());
            }
        }
        List<String> out = new ArrayList<>(names);
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    /**
     * 成因文案：从熔断边的 reason 文本里如实提取（沙盘注入时会写"沙盘熔断:…台风…"），
     * 不依赖沙盘状态，因此不会与 {@link DecisionSandboxService} 形成循环依赖。
     */
    private String causeOf(List<String> corridors) {
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        StringBuilder all = new StringBuilder();
        for (RiskSegment r : riskMap.values()) {
            if (r != null && r.getPenaltyMultiplier() != null && r.getPenaltyMultiplier() >= 100.0
                    && r.getReason() != null) {
                all.append(r.getReason());
            }
        }
        // 中文「风险」也含「风」字，不先剔除会把暴雨造成的封闭误判成台风（与 AIService 同一陷阱）
        String t = all.toString().replace("风险", "").toLowerCase();
        boolean typhoon = t.contains("台风") || t.contains("typhoon") || t.contains("强风")
                || t.contains("wind");
        boolean rain = t.contains("降雨") || t.contains("暴雨") || t.contains("rain");
        if (typhoon && rain) {
            return "rain+typhoon";
        }
        if (typhoon) {
            return "typhoon";
        }
        return rain ? "rain" : "fuse";
    }

    private static String fuseWord(String cause) {
        return switch (cause == null ? "" : cause) {
            case "typhoon" -> "被台风熔断";
            case "rain+typhoon" -> "被暴雨叠加台风熔断";
            case "rain" -> "因暴雨熔断";
            default -> "已熔断";
        };
    }

    private static String hoursText() {
        double h = WAIT_HOURS;
        return h == Math.floor(h) ? String.format("%.0f小时", h) : String.format("%.1f小时", h);
    }

    private String nodeName(String id, String fallback) {
        com.example.aseanweatherlogistics.model.entity.RoadNode n = id == null ? null : routeService.getNode(id);
        return n != null && n.getName() != null && !n.getName().isBlank() ? n.getName() : fallback;
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
