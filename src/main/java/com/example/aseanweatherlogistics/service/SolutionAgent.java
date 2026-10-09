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
 * 计算：公路绕行（真实路网 Dijkstra 重算）、陆水联运（平陆运河模型）、原地等待（货损模型）
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
    /**
     * 进度闸门：行程进度超过此值后，装货港南宁港六景（在全程约 28% 处）已肯定在车背后，
     * 陆水联运方案意味着掉头回港口，标记为不可用，不再参与推荐。
     */
    public static final double BACKWARD_GATE = 0.5;
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
     * aiChannel：推荐语那一段文字的真实生成通道；走规则模板时为 null。
     *           方案卡里的数字永远是 {@link #buildPlans} 算出来的，与模型无关，所以不随通道变。
     */
    public record SolutionResult(List<Map<String, Object>> plans, String recommendedId,
                                 String recommendation, boolean aiPowered, String mode,
                                 String aiChannel) {
    }

    /**
     * @param progressRatio 司机当前行程进度 0~1；-1 = 不在途/无进度数据（按还没出发处理）。
     *                      用于"进度闸门"：车已开过装货港时禁用掉头回港口的陆水联运方案。
     */
    public SolutionResult generate(RiskAgent.RiskResult risk, String originId, String destinationId,
                                   double progressRatio) {
        List<Map<String, Object>> plans = buildPlans(originId, destinationId, progressRatio);
        String recommendedId = recommend(plans);
        String mode = resolveMode(plans);

        // 大模型推荐理由（升级方案 §3.2）：数字喂给它，只让它说"为什么推荐"。
        // 常态（刚出发、走廊正常）不调模型：buildPrompt 是为熔断对比场景写的，
        // 常态下调用会输出"推荐陆水联运"这类与事实矛盾的论述，直接用常态模板。
        String recommendation = null;
        boolean aiPowered;
        String aiChannel = null;
        if ("normal".equals(mode)) {
            recommendation = "去河内不必只有公路：本走廊常态下即可走平陆运河陆水联运（方案B），车辆滚装上船、随船经运河转海运到海防，"
                    + "不赶时间甚至不带货也能全程坐船出境；追求时效则直走全程公路（方案A）最快。两条都是正常可选项，不是备选兜底。";
            aiPowered = false;
        } else {
            String replyTag = null;
            try {
                DeepseekService.AiReply reply = deepseekService.chatWithChannel(
                        "你是多式联运方案生成专家。只输出 JSON，不要输出任何其他文字。",
                        buildPrompt(risk, plans), 500);
                replyTag = reply.tag();
                JsonNode parsed = AgentJson.extractObject(reply.text());
                recommendation = AgentJson.text(parsed, "recommendation");
                // 模型给出的方案名/推荐若与计算不一致，以计算为准（只取其文字表达）
                if (recommendation == null) {
                    recommendation = AgentJson.text(parsed, "reason");
                }
            } catch (Exception e) {
                log.info("方案生成智能体大模型调用失败，使用规则模板：{}", e.toString());
            }
            aiPowered = recommendation != null;
            if (aiPowered) {
                // 文字确实来自模型：记下它是哪一级通道写的
                aiChannel = replyTag;
            } else {
                recommendation = templateRecommendation(recommendedId, plans);
            }
        }
        // 在途分析：推荐语里带一句进度上下文，让调度员看得出"这是按车当前位置算的"
        if (!"normal".equals(mode) && progressRatio >= 0 && recommendation != null) {
            recommendation = "【行程已走 " + Math.round(progressRatio * 100) + "%】" + recommendation;
        }
        return new SolutionResult(plans, recommendedId, recommendation, aiPowered, mode, aiChannel);
    }

    /**
     * 判定本次分析模式：公路已熔断绕行 / 水运禁航 / 双线风险 → reroute（展示增量）；
     * 否则为 normal（车刚出发、走廊正常，展示整趟运输的绝对值，避免出现全 0 的对比）。
     */
    private String resolveMode(List<Map<String, Object>> plans) {
        // 任一公路口岸熔断（含台风致芒街熔断）都要触发绕路决策：
        // 只看 roadBlocked() 会漏掉台风场景，使方案对比退回常态、六维用绝对值展示。
        if (sandbox.anyPortFused() || sandbox.waterBlocked()) {
            return "reroute";
        }
        boolean rerouted = plans.stream()
                .filter(p -> "A".equals(p.get("id")))
                .anyMatch(p -> p.get("extraHours") instanceof Number n && n.doubleValue() > 0);
        return rerouted ? "reroute" : "normal";
    }

    // ==================== 确定性方案计算 ====================

    private List<Map<String, Object>> buildPlans(String origin, String dest, double progressRatio) {
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

        // ---- 方案A：公路绕行（真实重算结果） ----
        // 名称按实际绕行口岸动态生成：台风熔断芒街时，绕行目标应是友谊关；
        // 若写死"公路绕行芒街"，就会出现"标题说绕行芒街、说明说无需绕行"的自相矛盾。
        Map<String, Object> planA = new LinkedHashMap<>();
        planA.put("id", "A");
        planA.put("type", "road-detour");
        planA.put("name", planAName());
        if (route != null && route.isRerouted()) {
            double extraKm = route.getExtraHours() * DETOUR_AVG_SPEED_KMH;
            planA.put("extraHours", round1(route.getExtraHours()));
            planA.put("totalHours", round1(route.getCurrentHours()));
            planA.put("costDeltaYuan", round0(
                    route.getExtraHours() * DETOUR_COST_PER_HOUR + extraKm * DETOUR_COST_PER_KM));
            planA.put("note", detourNote());
        } else if (route != null) {
            planA.put("extraHours", 0.0);
            planA.put("totalHours", round1(route.getCurrentHours()));
            planA.put("costDeltaYuan", 0);
            // 路网没绕行 ≠ 没有口岸熔断：台风打芒街时主通道友谊关不受影响，
            // 路线确实不用绕，但方案A叫"公路绕行芒街"，此时若只说"无需绕行"
            // 会和沙盘"芒街已熔断"矛盾，必须点明断的是哪个、主通道为何仍通。
            planA.put("note", sandbox.anyPortFused() ? blockedButPassableNote() : "当前公路走廊仍可用，无需绕行");
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

        // ---- 方案B：陆水联运（平陆运河模型） ----
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
            planB.put("name", "陆水联运（禁航·锚泊等待）");
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
        // ---- 进度闸门：车已开过（或接近）装货港南宁港六景，联运首程变成掉头回港口 ----
        // 典型案例：司机快到友谊关了，公路熔断时无条件推荐"陆水联运"=让车跑五百公里回南宁坐船，
        // 纯废话。进度过半时把方案B 标记为不可用，不进六维表、不参与推荐。
        if (progressRatio > BACKWARD_GATE) {
            planB.put("notApplicable", true);
            planB.put("extraHours", null);
            planB.put("totalHours", null);
            planB.put("costDeltaYuan", null);
            planB.put("metrics", null);
            planB.put("note", "车辆已行驶全程 " + Math.round(progressRatio * 100)
                    + "%，装货港南宁港六景已在身后，回港口换乘不经济——本方案对当前行程不适用");
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

    /** 双向推荐：公路断了推荐水运，水运禁航推荐公路，双线风险推荐等待；被进度闸门禁用的方案不参与推荐。 */
    private String recommend(List<Map<String, Object>> plans) {
        boolean roadBlocked = sandbox.roadBlocked();
        boolean waterBlocked = sandbox.waterBlocked();
        Map<String, Object> planA = planById(plans, "A");
        Map<String, Object> planB = planById(plans, "B");
        // A 不可用的唯一情形：公路走廊全部熔断（buildPlans 里 route==null → extraHours=null）
        boolean aUsable = planA != null && planA.get("extraHours") != null;
        boolean bUsable = planB != null && !Boolean.TRUE.equals(planB.get("notApplicable")) && !waterBlocked;
        if (roadBlocked && waterBlocked) {
            return "C"; // 双线风险 → 原地等待
        }
        if (roadBlocked) {
            // 原逻辑无条件回 B；车已过港口时 B 不可用，退而求其次：公路绕行 A → 等待 C
            if (bUsable) return "B";
            return aUsable ? "A" : "C";
        }
        if (waterBlocked) {
            return aUsable ? "A" : "C"; // 水运禁航 → 切公路；公路也断了则等待
        }
        // 双线正常：默认推荐公路（时效最优）；公路不可用时退回水运/等待
        if (aUsable) return "A";
        return bUsable ? "B" : "C";
    }

    private Map<String, Object> planById(List<Map<String, Object>> plans, String id) {
        return plans.stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null);
    }

    private String templateRecommendation(String recommendedId, List<Map<String, Object>> plans) {
        Map<String, Object> rec = plans.stream()
                .filter(p -> recommendedId.equals(p.get("id"))).findFirst().orElse(plans.get(0));
        boolean waterBlocked = sandbox.waterBlocked();
        // 推荐理由里的口岸名与成因一律按实际生成，避免"台风熔断芒街"却写成
        // "友谊关熔断背景下…改走芒街口岸"这种把事实说反的模板。
        String blocked = sandbox.blockedPortNames();
        String blockedText = blocked == null ? "口岸受阻" : blocked + "熔断";
        String cause = sandbox.fuseCause();
        String causeText = cause == null ? ""
                : "typhoon".equals(cause) ? "（台风致中断）"
                : "rain+typhoon".equals(cause) ? "（暴雨叠加台风）" : "";
        if ("B".equals(recommendedId)) {
            return String.format(
                    "推荐方案B（陆水联运）：%s%s背景下，水运通道不受公路边坡滑坡风险影响；"
                            + "时效仅 %s 小时（优于公路绕行），成本 %s 元（平陆运河过闸费免征），"
                            + "冷链恒温舱货损风险低，综合最优。",
                    blockedText, causeText,
                    rec.get("extraHours"), rec.get("costDeltaYuan"));
        }
        if ("A".equals(recommendedId) && waterBlocked) {
            return String.format(
                    "推荐方案A（%s）：平陆运河禁航（%s），水运不可用；改走公路绕行，时效与成本最优。",
                    rec.get("name"),
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
        // 熔断口岸与可用绕行口岸必须按实际情况给：台风打芒街时若仍写
        // "熔断路段：友谊关 / 可用绕行：芒街"，等于把事实说反，模型会给出错误推荐。
        String blocked = sandbox.blockedPortNames();
        if (blocked == null) {
            blocked = risk.llmJson() != null
                    ? String.valueOf(risk.llmJson().getOrDefault("blockedSegment", "友谊关")) : "友谊关";
        }
        String detour = detourPorts(blocked);

        StringBuilder p = new StringBuilder();
        p.append("你是多式联运方案生成专家。已知：\n");
        p.append("- 熔断路段：").append(blocked).append('\n');
        p.append("- 可用绕行：").append(detour == null ? "无（两个口岸均不可用）" : detour).append('\n');
        String cause = sandbox.fuseCause();
        if (cause != null) {
            p.append("- 熔断成因：").append("typhoon".equals(cause) ? "台风（强风，非降雨）"
                            : "rain+typhoon".equals(cause) ? "暴雨叠加台风" : "暴雨")
                    .append('\n');
            if (cause.startsWith("typhoon") || cause.contains("typhoon")) {
                DecisionSandboxService.Section mc = sandbox.mc();
                if (mc.typhoonActive) {
                    p.append("- 台风参数：气压").append(String.format("%.0f", mc.typhoonPressureHpa))
                            .append("hPa、风速").append(String.format("%.0f", mc.typhoonWindKph))
                            .append("km/h\n");
                }
            }
        }
        p.append("- 公路绕行数据：时间").append(fmtDelta(planA.get("extraHours")))
                .append("h，成本").append(fmtDelta(planA.get("costDeltaYuan"))).append("元\n");
        p.append("- 陆水联运数据：时间").append(fmtDelta(planB.get("extraHours")))
                .append("h，成本").append(fmtDelta(planB.get("costDeltaYuan"))).append("元\n");
        p.append("- 原地等待数据：时间+").append(String.format("%.0f", WAIT_HOURS))
                .append("h，货损率").append(planC.get("damageRatePct")).append("%\n");
        p.append("- 货物：").append(cargo.name())
                .append(cargo.coldChain() ? "，冷链" : "").append('\n');
        p.append("\n请输出三个方案对比与推荐，JSON格式：\n")
                .append("{\n")
                .append("  \"solutions\": [\n")
                // 名称用实际方案名，避免模型沿用"公路绕行芒街"这个在台风场景下错误的示例
                .append("    {\"name\":\"").append(planA.get("name"))
                .append("\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"陆水联运平陆运河\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"原地等待\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"}\n")
                .append("  ],\n")
                .append("  \"recommendation\": \"推荐方案及理由（不超过80字）\"\n")
                .append("}");
        return p.toString();
    }

    /**
     * 方案A 的绕行说明：按实际熔断口岸与成因生成。
     * 原实现硬编码"友谊关已熔断，改走芒街"，台风熔断芒街时会说成
     * "改走芒街口岸入境"——而芒街正是断掉的那一个，自相矛盾。
     */
    private String detourNote() {
        String blocked = sandbox.blockedPortNames();
        String detour = detourPorts(blocked);
        String cause = sandbox.fuseCause();
        String causeText = cause == null ? ""
                : "typhoon".equals(cause) ? "（台风致中断，非降雨）"
                : "rain+typhoon".equals(cause) ? "（暴雨叠加台风）" : "";
        if (detour == null) {
            return "两个口岸均不可用" + causeText + "，公路走廊无可用通道";
        }
        // detour 由 section.name 得来，本身已含"口岸"二字，不要再拼一次
        return (blocked == null ? "口岸受阻" : blocked + "已熔断") + causeText
                + "，实时路网重算：改走" + detour + "入境";
    }

    /**
     * 方案A 名称：按实际要绕往的口岸命名。
     * <ul>
     *   <li>友谊关被熔断 → 绕行芒街（原剧本场景）</li>
     *   <li>芒街被熔断 → 绕行友谊关（台风场景；此时主通道本就走友谊关，实际无需改道）</li>
     *   <li>两个都断 → 无可用公路绕行</li>
     *   <li>都没有断 → 默认主通道友谊关</li>
     * </ul>
     */
    private String planAName() {
        String blocked = sandbox.blockedPortNames();
        if (blocked == null) {
            return "公路直达友谊关";
        }
        String detour = detourPorts(blocked);
        if (detour == null) {
            return "公路绕行（无可用口岸）";
        }
        return "公路绕行" + detour;
    }

    /**
     * 有口岸熔断但主通道未受影响时的说明：点明断的是哪个、为什么当前路线仍可走。
     * 典型场景：台风熔断芒街，而默认主路线走友谊关，路网无需重算——
     * 此时不能简单说"无需绕行"，否则与沙盘的"芒街已熔断"看起来互相矛盾。
     */
    private String blockedButPassableNote() {
        String blocked = sandbox.blockedPortNames();
        String detour = detourPorts(blocked);
        String cause = sandbox.fuseCause();
        String causeText = cause == null ? ""
                : "typhoon".equals(cause) ? "台风" : "rain+typhoon".equals(cause) ? "暴雨叠加台风" : "暴雨";
        String head = (blocked == null ? "有口岸受限" : blocked + "因" + causeText + "熔断");
        if (detour == null) {
            return head + "，另一侧口岸同样不可用，公路走廊无可用通道";
        }
        return head + "，但主通道" + detour + "不受影响——当前路线本就不经该口岸，无需绕行";
    }

    /**
     * 仍可绕行的口岸：两个口岸中未被熔断的那个。
     * 熔断的是芒街时，可绕行的是友谊关——原逻辑无条件写"可用绕行：芒街"，
     * 台风场景下会把绕行方向说反。
     */
    private String detourPorts(String blocked) {
        if (blocked == null) {
            return sandbox.mc().name;
        }
        List<String> all = List.of(sandbox.ygg().name, sandbox.mc().name);
        List<String> free = all.stream().filter(n -> !blocked.contains(n)).toList();
        return free.isEmpty() ? null : String.join("、", free);
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
