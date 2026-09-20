package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 🟢 方案生成智能体（多智能体链路第二环）。
 * <p>
 * 输入：风险研判结果 + 货物画像；
 * 计算：公路绕行（真实路网 Dijkstra 重算）、公水联运（平陆运河模型）、原地等待（货损模型）
 *       ——方案数据全部由确定性引擎产出，数字可信、可复现；
 * 输出：三方案对比 + 推荐语。本地大模型可用时生成推荐理由（决策沟通），
 *       不可用时规则模板兜底；方案的时效/成本/货损数字不交给模型生成。
 */
@Service
public class SolutionAgent {

    private static final Logger log = LoggerFactory.getLogger(SolutionAgent.class);

    public static final String ID = "plan-generation";
    public static final String NAME = "方案生成智能体";
    public static final String ICON = "🟢";

    /** 方案C：原地等待小时数（等待熔断解除） */
    public static final double WAIT_HOURS = 6.0;
    /** 方案A成本模型：绕行每小时综合成本（车辆+司机+冷链能耗，元/h） */
    public static final double DETOUR_COST_PER_HOUR = 400.0;
    /** 方案A成本模型：绕行每公里成本（元/km），按平均 45km/h 折算绕行里程 */
    public static final double DETOUR_COST_PER_KM = 5.0;
    public static final double DETOUR_AVG_SPEED_KMH = 45.0;

    private final RouteService routeService;
    private final DemoConfigService config;
    private final DecisionSandboxService sandbox;
    private final IntermodalService intermodal;
    private final DeepseekService deepseekService;

    private final CostCalculator costCalculator;

    public SolutionAgent(RouteService routeService, DemoConfigService config,
                         DecisionSandboxService sandbox, IntermodalService intermodal,
                         DeepseekService deepseekService, CostCalculator costCalculator) {
        this.routeService = routeService;
        this.config = config;
        this.sandbox = sandbox;
        this.intermodal = intermodal;
        this.deepseekService = deepseekService;
        this.costCalculator = costCalculator;
    }

    /**
     * 方案生成结果：确定性方案卡 + 推荐语（LLM 或模板）。
     * mode：normal=常态方案对比（六维展示整趟运输的绝对值）；reroute=已触发熔断/禁航（六维展示相对原计划的增量）。
     */
    public record SolutionResult(List<Map<String, Object>> plans, String recommendedId,
                                 String recommendation, boolean aiPowered, String mode) {
    }

    public SolutionResult generate(RiskAgent.RiskResult risk, String originId, String destinationId) {
        List<Map<String, Object>> plans = buildPlans(originId, destinationId);
        String recommendedId = recommend(plans);
        String mode = resolveMode(plans);

        // 大模型推荐理由（升级方案 §3.2）：数字喂给它，只让它说"为什么推荐"
        String recommendation = null;
        try {
            String output = deepseekService.chat(
                    "你是多式联运方案生成专家。只输出 JSON，不要输出任何其他文字。",
                    buildPrompt(risk, plans), 500);
            JsonNode parsed = AgentJson.extractObject(output);
            recommendation = AgentJson.text(parsed, "recommendation");
            // 模型给出的方案名/推荐若与计算不一致，以计算为准（只取其文字表达）
            if (recommendation == null) {
                recommendation = AgentJson.text(parsed, "reason");
            }
        } catch (Exception e) {
            log.info("方案生成智能体大模型调用失败，使用规则模板：{}", e.toString());
        }
        boolean aiPowered = recommendation != null;
        if (!aiPowered) {
            recommendation = templateRecommendation(recommendedId, plans);
        }
        return new SolutionResult(plans, recommendedId, recommendation, aiPowered, mode);
    }

    /**
     * 判定本次分析模式：公路已熔断绕行 / 水运禁航 / 双线风险 → reroute（展示增量）；
     * 否则为 normal（车刚出发、走廊正常，展示整趟运输的绝对值，避免出现全 0 的对比）。
     */
    private String resolveMode(List<Map<String, Object>> plans) {
        if (sandbox.roadBlocked() || sandbox.waterBlocked()) {
            return "reroute";
        }
        boolean rerouted = plans.stream()
                .filter(p -> "A".equals(p.get("id")))
                .anyMatch(p -> p.get("extraHours") instanceof Number n && n.doubleValue() > 0);
        return rerouted ? "reroute" : "normal";
    }

    // ==================== 确定性方案计算 ====================

    private List<Map<String, Object>> buildPlans(String origin, String dest) {
        List<Map<String, Object>> plans = new ArrayList<>();
        DemoConfigService.CargoType cargo = config.cargo();

        RouteResponse route = null;
        try {
            route = routeService.planRouteFast(origin, dest);
        } catch (Exception e) {
            log.warn("方案生成：路网重算失败 {}", e.toString());
        }
        double baselineHours = route != null ? route.getBaselineHours() : 8.5;
        double baselineKm = route != null ? route.getTotalDistanceKm() : 390;

        // ---- 方案A：公路绕行芒街（真实重算结果） ----
        Map<String, Object> planA = new LinkedHashMap<>();
        planA.put("id", "A");
        planA.put("type", "road-detour");
        planA.put("name", "公路绕行芒街");
        if (route != null && route.isRerouted()) {
            double extraKm = route.getExtraHours() * DETOUR_AVG_SPEED_KMH;
            planA.put("extraHours", round1(route.getExtraHours()));
            planA.put("totalHours", round1(route.getCurrentHours()));
            planA.put("costDeltaYuan", round0(
                    route.getExtraHours() * DETOUR_COST_PER_HOUR + extraKm * DETOUR_COST_PER_KM));
            planA.put("note", "友谊关已熔断，实时路网重算：改走芒街口岸入境");
        } else if (route != null) {
            planA.put("extraHours", 0.0);
            planA.put("totalHours", round1(route.getCurrentHours()));
            planA.put("costDeltaYuan", 0);
            planA.put("note", "当前公路走廊仍可用，无需绕行");
        } else {
            planA.put("extraHours", null);
            planA.put("costDeltaYuan", null);
            planA.put("note", "公路走廊全部熔断，无可用公路方案");
        }
        planA.put("damageRisk", "中");
        planA.put("damageNote", "绕行增加在途时间，冷链机组持续耗能，货损风险中");
        // 六维指标：绕行增量里程按 额外耗时×均速 折算
        double extraKmA = route != null && route.isRerouted() ? route.getExtraHours() * DETOUR_AVG_SPEED_KMH : 0;
        double extraHoursA = route != null && route.isRerouted() ? route.getExtraHours() : 0;
        double totalHoursA = route != null ? route.getCurrentHours() : baselineHours;
        double fuelA = costCalculator.fuelLiters(extraKmA);
        double costA = route != null && route.isRerouted()
                ? route.getExtraHours() * DETOUR_COST_PER_HOUR + extraKmA * DETOUR_COST_PER_KM : 0;
        double carbonA = costCalculator.carbonKgForRoad(extraKmA);
        // 全程绝对值：方案A 走完整趟公路（含绕行增量里程）
        double absKmA = baselineKm + extraKmA;
        double absFuelA = costCalculator.fuelLiters(absKmA);
        double absCostA = intermodal.roadCostYuan(absKmA);
        double absCarbonA = costCalculator.carbonKgForRoad(absKmA);
        planA.put("metrics", costCalculator.build(extraHoursA, totalHoursA, "中", cargo,
                extraHoursA, fuelA, costA, carbonA, absFuelA, absCostA, absCarbonA).toMap());
        plans.add(planA);

        // ---- 方案B：公水联运（平陆运河模型） ----
        // 双向切换：水运禁航时方案B变为"锚泊等待"（水运侧不可用），否则为正常联运
        boolean waterBlocked = sandbox.waterBlocked();
        Map<String, Object> planB = intermodal.plan(baselineKm, baselineHours);
        double roadKmB = 110 + 100; // 南宁→六景 + 海防→河内 两段公路短驳
        double waterKmB = 134 + 330; // 平陆运河 + 海运
        // 油耗/碳排放按"相对全程公路基准"的增量：公路段少了 (baselineKm - roadKmB)，新增水运段
        double fuelB = costCalculator.fuelLiters(roadKmB - baselineKm); // 负=省油
        double carbonB = costCalculator.carbonKgForRoad(roadKmB - baselineKm)
                + costCalculator.carbonKgForWater(waterKmB);
        if (waterBlocked) {
            // 水运禁航 → 方案B降级为"锚泊等待"：额外 +8h 等待 + 锚泊费，不可用为优选
            planB.put("name", "公水联运（禁航·锚泊等待）");
            planB.put("extraHours", 8.0);
            planB.put("totalHours", round1(baselineHours + 8.0));
            planB.put("costDeltaYuan", 2000); // 锚泊费
            planB.put("damageRisk", "低");
            planB.put("note", "平陆运河禁航（" + (sandbox.waterBlockedReason() == null ? "通航条件超限" : sandbox.waterBlockedReason())
                    + "），船舶锚泊等待，预计等待8小时");
            planB.put("waterBlocked", true);
            planB.put("metrics", costCalculator.build(8.0, baselineHours + 8.0, "低", cargo,
                    8.0, fuelB, 2000, carbonB,
                    costCalculator.fuelLiters(roadKmB), intermodal.totalCostYuan() + 2000,
                    costCalculator.carbonKgForRoad(roadKmB) + costCalculator.carbonKgForWater(waterKmB)).toMap());
        } else {
            double extraHoursB = planB.get("extraHours") instanceof Number n ? n.doubleValue() : 0;
            double totalHoursB = planB.get("totalHours") instanceof Number n2 ? n2.doubleValue() : baselineHours;
            double costB = planB.get("costDeltaYuan") instanceof Number n3 ? n3.doubleValue() : 0;
            planB.put("waterBlocked", false);
            planB.put("metrics", costCalculator.build(extraHoursB, totalHoursB, "低", cargo,
                    extraHoursB, fuelB, costB, carbonB,
                    costCalculator.fuelLiters(roadKmB), intermodal.totalCostYuan(),
                    costCalculator.carbonKgForRoad(roadKmB) + costCalculator.carbonKgForWater(waterKmB)).toMap());
        }
        plans.add(planB);

        // ---- 方案C：原地等待（货物延误敏感度模型） ----
        Map<String, Object> planC = new LinkedHashMap<>();
        planC.put("id", "C");
        planC.put("type", "wait");
        planC.put("name", "原地等待");
        planC.put("extraHours", WAIT_HOURS);
        planC.put("costDeltaYuan", 0);
        double rate = cargo.damageRate(WAIT_HOURS);
        planC.put("damageRatePct", round1(rate * 100));
        planC.put("damageLossYuan", round0(cargo.damageLossYuan(WAIT_HOURS)));
        planC.put("damageRisk", rate > 0.03 ? "高" : (rate > 0 ? "中" : "低"));
        planC.put("note", String.format("%s延误 %.0f 小时，货损率预计 %.1f%%（货值 %.0f 万元，损失约 %.0f 元）",
                cargo.name(), WAIT_HOURS, rate * 100, cargo.cargoValueYuan() / 10_000.0,
                cargo.damageLossYuan(WAIT_HOURS)));
        // 六维指标：等待期间无位移，但等待结束后仍需走完全程公路，故全程油耗/费用/碳排放按全程计
        planC.put("metrics", costCalculator.build(WAIT_HOURS, baselineHours + WAIT_HOURS,
                rate > 0.03 ? "高" : (rate > 0 ? "中" : "低"), cargo, WAIT_HOURS, 0, 0, 0,
                costCalculator.fuelLiters(baselineKm), intermodal.roadCostYuan(baselineKm),
                costCalculator.carbonKgForRoad(baselineKm)).toMap());
        plans.add(planC);

        return plans;
    }

    /** 双向推荐：公路断了推荐水运，水运禁航推荐公路，双线风险推荐等待。 */
    private String recommend(List<Map<String, Object>> plans) {
        boolean roadBlocked = sandbox.roadBlocked();
        boolean waterBlocked = sandbox.waterBlocked();
        if (roadBlocked && waterBlocked) {
            return "C"; // 双线风险 → 原地等待
        }
        if (roadBlocked) {
            return "B"; // 公路断了 → 切水运
        }
        if (waterBlocked) {
            return "A"; // 水运禁航 → 切公路
        }
        // 双线正常：默认推荐公路（时效最优）
        return "A";
    }

    private String templateRecommendation(String recommendedId, List<Map<String, Object>> plans) {
        Map<String, Object> rec = plans.stream()
                .filter(p -> recommendedId.equals(p.get("id"))).findFirst().orElse(plans.get(0));
        boolean waterBlocked = sandbox.waterBlocked();
        if ("B".equals(recommendedId)) {
            return String.format(
                    "推荐方案B（公水联运）：友谊关熔断背景下，水运通道不受公路边坡滑坡风险影响；"
                            + "时效仅 %s 小时（优于公路绕行），成本 %s 元（平陆运河过闸费免征），"
                            + "冷链恒温舱货损风险低，综合最优。",
                    rec.get("extraHours"), rec.get("costDeltaYuan"));
        }
        if ("A".equals(recommendedId) && waterBlocked) {
            return String.format(
                    "推荐方案A（公路绕行芒街）：平陆运河禁航（%s），水运不可用；"
                            + "改走公路绕行芒街口岸，时效与成本最优。",
                    sandbox.waterBlockedReason() == null ? "通航条件超限" : sandbox.waterBlockedReason());
        }
        if ("C".equals(recommendedId)) {
            return "推荐方案C（原地等待）：公路与水运双线风险叠加，强行通行货损与安全风险均不可控；"
                    + "建议原地等待，待任一通道解除红线后再发车。";
        }
        return String.format("推荐方案%s（%s）：当前公路走廊可用，时效与成本最优。",
                recommendedId, rec.get("name"));
    }

    /** 升级方案 §3.2 Prompt：真实计算数据输入，模型只产出推荐论述。 */
    private String buildPrompt(RiskAgent.RiskResult risk, List<Map<String, Object>> plans) {
        Map<String, Object> planA = plans.get(0);
        Map<String, Object> planB = plans.get(1);
        Map<String, Object> planC = plans.get(2);
        DemoConfigService.CargoType cargo = config.cargo();
        Object blockedSegment = risk.llmJson() != null
                ? risk.llmJson().getOrDefault("blockedSegment", "友谊关") : "友谊关";

        StringBuilder p = new StringBuilder();
        p.append("你是多式联运方案生成专家。已知：\n");
        p.append("- 熔断路段：").append(blockedSegment).append('\n');
        p.append("- 可用绕行：芒街\n");
        p.append("- 公路绕行数据：时间").append(fmtDelta(planA.get("extraHours")))
                .append("h，成本").append(fmtDelta(planA.get("costDeltaYuan"))).append("元\n");
        p.append("- 公水联运数据：时间").append(fmtDelta(planB.get("extraHours")))
                .append("h，成本").append(fmtDelta(planB.get("costDeltaYuan"))).append("元\n");
        p.append("- 原地等待数据：时间+").append(String.format("%.0f", WAIT_HOURS))
                .append("h，货损率").append(planC.get("damageRatePct")).append("%\n");
        p.append("- 货物：").append(cargo.name())
                .append(cargo.coldChain() ? "，冷链" : "").append('\n');
        p.append("\n请输出三个方案对比与推荐，JSON格式：\n")
                .append("{\n")
                .append("  \"solutions\": [\n")
                .append("    {\"name\":\"公路绕行芒街\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"公水联运平陆运河\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"原地等待\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"}\n")
                .append("  ],\n")
                .append("  \"recommendation\": \"推荐方案及理由（不超过80字）\"\n")
                .append("}");
        return p.toString();
    }

    private static String fmtDelta(Object v) {
        if (v == null) {
            return "不可用";
        }
        double d = ((Number) v).doubleValue();
        return (d >= 0 ? "+" : "") + (d == Math.floor(d) ? String.format("%.0f", d) : String.format("%.1f", d));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round0(double v) {
        return Math.round(v);
    }
}
