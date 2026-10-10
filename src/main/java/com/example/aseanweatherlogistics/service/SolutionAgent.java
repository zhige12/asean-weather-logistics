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
 * 输出：三方案对比 + 推荐语。推荐语每次都走大模型生成（常态与改道一视同仁），
 *       本地不可用 ≤1s 自动切在线，两通道都断时从历史 AI 文案池轮换取用；
 *       池中也无对应情境时才落规则模板。方案的时效/成本/货损数字不交给模型生成。
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
    private final RecommendationPool recommendationPool;
    /** 全域受阻建议：路网级“全部走廊熔断 + 运河禁航”判据，与大屏/司机端同一份口径 */
    private final CorridorAdvisoryService corridorAdvisoryService;

    private final CostCalculator costCalculator;

    public SolutionAgent(RouteService routeService, DemoConfigService config,
                         DecisionSandboxService sandbox, IntermodalService intermodal,
                         DeepseekService deepseekService, RecommendationPool recommendationPool,
                         CostCalculator costCalculator, CorridorAdvisoryService corridorAdvisoryService) {
        this.routeService = routeService;
        this.config = config;
        this.sandbox = sandbox;
        this.intermodal = intermodal;
        this.deepseekService = deepseekService;
        this.recommendationPool = recommendationPool;
        this.costCalculator = costCalculator;
        this.corridorAdvisoryService = corridorAdvisoryService;
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

        // 大模型推荐语（升级方案 §3.2）：数字喂给它，只让它说"为什么推荐"。
        // 常态与改道一视同仁——每次都现调模型（本地优先、≤1s 切在线、再落缓存池），
        // 不再给常态写死一句固定话，避免"接了 AI 却每句雷同"的硬编码观感。
        String recommendation = null;
        boolean aiPowered;
        String aiChannel = null;
        // 情境签名：模式 + 推荐方案 + 成因，让缓存池按当前局势归置文案，
        // 断网兜底时取到的措辞与眼前景况一致（常态不会拿到改道话术）。
        String situationKey = situationKey(mode, recommendedId, plans);
        String userPrompt = "normal".equals(mode) ? buildNormalPrompt(plans) : buildPrompt(risk, plans);
        String replyTag = null;
        try {
            DeepseekService.AiReply reply = deepseekService.chatWithChannel(
                    "你是多式联运方案生成专家。只输出 JSON，不要输出任何其他文字。",
                    userPrompt, 500);
            replyTag = reply.tag();
            JsonNode parsed = AgentJson.extractObject(reply.text());
            recommendation = AgentJson.text(parsed, "recommendation");
            // 模型给出的方案名/推荐若与计算不一致，以计算为准（只取其文字表达）
            if (recommendation == null) {
                recommendation = AgentJson.text(parsed, "reason");
            }
        } catch (Exception e) {
            log.info("方案生成智能体大模型调用失败，转缓存池/模板：{}", e.toString());
        }
        aiPowered = recommendation != null;
        if (aiPowered) {
            // 文字确实来自模型：记下它是哪一级通道写的，并存进缓存池供日后断网复用
            aiChannel = replyTag;
            recommendationPool.record(situationKey, recommendation);
        } else {
            // 两条模型通道都不通（断网）：先从缓存池轮换取一句历史 AI 文案，避免每次同一句模板
            String cached = recommendationPool.pick(situationKey);
            if (cached != null) {
                recommendation = cached;
                aiChannel = DeepseekService.CHANNEL_CACHED;
            } else {
                // 池里也没有该情境的历史文案：才落确定性规则模板
                recommendation = "normal".equals(mode) ? normalRecommendation() : templateRecommendation(recommendedId, plans);
            }
        }
        // 口岸确实熔断了、但断的不是司机这条线：无论推荐语出自模型还是模板，
        // 都先 deterministic 地补一句实况，否则「常态」口吻会让调度员以为什么灾害都没发生。
        String unaffected = unaffectedNote(plans);
        if (unaffected != null) {
            recommendation = (recommendation == null || recommendation.isBlank())
                    ? unaffected : unaffected + "；" + recommendation;
        }
        // 在途分析：推荐语里带一句进度上下文，让调度员看得出"这是按车当前位置算的"
        if (!"normal".equals(mode) && progressRatio >= 0 && recommendation != null) {
            recommendation = "【行程已走 " + Math.round(progressRatio * 100) + "%】" + recommendation;
        }
        return new SolutionResult(plans, recommendedId, recommendation, aiPowered, mode, aiChannel);
    }

    /**
     * 「有口岸熔断，但本程路线不受影响」时的一句实况说明。
     * 路线真被改道过（routeAffected）时绝不能说这句话——那时确实绕了，返回 null。
     */
    private String unaffectedNote(List<Map<String, Object>> plans) {
        String blocked = sandbox.blockedPortNames();
        if (blocked == null || sandbox.waterBlocked()) {
            return null;
        }
        Map<String, Object> planA = planById(plans, "A");
        if (planA == null || Boolean.TRUE.equals(planA.get("routeAffected"))) {
            return null;
        }
        String cause = sandbox.fuseCause();
        String causeText = cause == null ? "" : ("typhoon".equals(cause) ? "因台风" : "rain+typhoon".equals(cause) ? "因暴雨叠加台风" : "因暴雨");
        return blocked + causeText + "熔断，但不在本程路线上，当前路线维持不变、无需绕行";
    }

    /**
     * 判定本次分析模式：本程路线真被灾害打到（重算过 / 公路已无路）或水运禁航 → reroute（展示增量）；
     * 否则为 normal（车刚出发、走廊正常，展示整趟运输的绝对值，避免出现全 0 的对比）。
     * <p>
     * 原来这里用 {@code sandbox.anyPortFused()} 触发：给芒街注入台风、司机本来就走友谊关时
     * 也会翻成 reroute，大屏于是出一句「推荐绕行友谊关」——绕的还是当前这条线，绕了个寂寞。
     * 改道只对「灾害打到了司机正在走的这条线」有意义，判据必须是路线本身有没有被改。
     */
    private String resolveMode(List<Map<String, Object>> plans) {
        if (sandbox.waterBlocked()) {
            return "reroute";
        }
        return plans.stream()
                .filter(p -> "A".equals(p.get("id")))
                .anyMatch(p -> Boolean.TRUE.equals(p.get("routeAffected"))
                        || p.get("extraHours") instanceof Number n && n.doubleValue() > 0)
                ? "reroute" : "normal";
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
        // 全域受阻建议（路线自带 corridorAllFused，不重复搜索）：后面方案C 的形态与推荐语都用它
        Map<String, Object> advisory = null;
        if (route != null) {
            try {
                advisory = corridorAdvisoryService.forRoute(origin, dest,
                        route.getPathNodeIds(), route.getPathEdgeIds(), progressRatio,
                        route.isCorridorAllFused());
            } catch (Exception e) {
                log.warn("方案生成：全域受阻建议算失败 {}", e.toString());
            }
        }

        // ---- 本程到底有没有被灾害打到 ----
        // 唯一判据：风险感知路线与无气象基准路线是否不同（route.isRerouted()），或干脆无路可走。
        // 这条线决定了后面所有措辞——没打到就不许出现「绕行」，也不进改道模式，
        // 否则会出现「标题：公路绕行友谊关 / 说明：当前路线本就不经友谊关」这种自相矛盾。
        String portOnRoute = routePortOf(route);
        boolean routeAffected = route == null || route.isRerouted();

        // ---- 方案A：公路（真实重算结果） ----
        // 名称由 routeAffected 决定：真被改道了才叫绕行（暴雨熔断友谊关→绕行芒街），
        // 没改道就只报“直达哪个口岸”。写死"公路绕行X"就会出现
        // "标题说绕行友谊关、说明说本就不经友谊关"这种把司机已在走的路说成新方案的矛盾。
        Map<String, Object> planA = new LinkedHashMap<>();
        planA.put("id", "A");
        planA.put("type", "road-detour");
        planA.put("routeAffected", routeAffected);
        planA.put("name", planAName(routeAffected, portOnRoute));
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
            // 路网没绕行且本程未受影响：仍要区分“真没口岸熔断”与“熔断的是别的走廊”。
            // 后者若只说"无需绕行"，会与沙盘"芒街已熔断"看起来矛盾，必须点明断的是哪个。
            planA.put("note", sandbox.anyPortFused() ? blockedButPassableNote() : "当前公路走廊仍可用，无需绕行");
        } else {
            planA.put("extraHours", null);
            planA.put("costDeltaYuan", null);
            planA.put("note", "公路走廊全部熔断，无可用公路方案");
        }
        // 六维指标：绕行增量里程按 额外耗时×均速 折算
        double extraKmA = route != null && route.isRerouted() ? route.getExtraHours() * DETOUR_AVG_SPEED_KMH : 0;
        double extraHoursA = route != null && route.isRerouted() ? route.getExtraHours() : 0;
        double totalHoursA = route != null ? route.getCurrentHours() : baselineHours;
        // 货损：用全要素货损指数（环境温/湿 + 绕行延误）算，取代原来写死的"中"
        CargoSafetyIndex.Result cargoA = sandbox.cargoRiskOf(extraHoursA);
        planA.put("damageRisk", cargoA.level());
        planA.put("damageScore", cargoA.score());
        planA.put("damageNote", cargoDamageNote(cargoA));
        double fuelA = costCalculator.fuelLiters(extraKmA);
        double costA = route != null && route.isRerouted()
                ? route.getExtraHours() * DETOUR_COST_PER_HOUR + extraKmA * DETOUR_COST_PER_KM : 0;
        double carbonA = costCalculator.carbonKgForRoad(extraKmA);
        // 全域受阻时打上动作标记：A 的“能走”靠的是把已封闭路段降级放行，不能当作正常优选
        if (advisory != null) {
            planA.put("advisoryAction", advisory.get("action"));
        }
        // 全程绝对值：方案A 走完整趟公路（含绕行增量里程）
        double absKmA = baselineKm + extraKmA;
        double absFuelA = costCalculator.fuelLiters(absKmA);
        double absCostA = intermodal.roadCostYuan(absKmA);
        double absCarbonA = costCalculator.carbonKgForRoad(absKmA);
        planA.put("metrics", costCalculator.build(extraHoursA, totalHoursA, cargoA.level(), cargo,
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

        // ---- 方案C：原地等待（货物延误敏感度模型）----
        // 全域受阻时同一个槽位按局势变形：未出境→返回起点，已出境→就近停靠，未发车→暂缓发车
        Map<String, Object> planC = new LinkedHashMap<>();
        planC.put("id", "C");
        planC.put("type", "wait");
        planC.put("name", planCName(advisory));
        planC.put("extraHours", WAIT_HOURS);
        planC.put("costDeltaYuan", 0);
        double rate = cargo.damageRate(WAIT_HOURS);
        CargoSafetyIndex.Result cargoC = sandbox.cargoRiskOf(WAIT_HOURS);
        planC.put("damageRatePct", round1(rate * 100));
        planC.put("damageLossYuan", round0(cargo.damageLossYuan(WAIT_HOURS)));
        // 货损等级：延误货损率与环境（高/高湿）风险取更不利者
        planC.put("damageRisk", worseLevel(
                rate > 0.03 ? "高" : (rate > 0 ? "中" : "低"), cargoC.level()));
        planC.put("damageScore", cargoC.score());
        planC.put("note", String.format("%s延误 %.0f 小时，货损率预计 %.1f%%（货值 %.0f 万元，损失约 %.0f 元）",
                cargo.name(), WAIT_HOURS, rate * 100, cargo.cargoValueYuan() / 10_000.0,
                cargo.damageLossYuan(WAIT_HOURS)));
        // 全域受阻时把建议本体挂到方案C：大屏与 AgentPanel 直接渲染 title/advice，
        // 不必再从散落字段拼出一句与局势不符的「原地等待」
        if (advisory != null) {
            planC.put("advisory", advisory);
            planC.put("note", advisory.get("advice")
                    + "（货物延误 " + hoursText(WAIT_HOURS) + "，货损率预计 " + round1(rate * 100) + "%）");
        }
        // 六维指标：等待期间无位移，但等待结束后仍需走完全程公路，故全程油耗/费用/碳排放按全程计
        planC.put("metrics", costCalculator.build(WAIT_HOURS, baselineHours + WAIT_HOURS,
                rate > 0.03 ? "高" : (rate > 0 ? "中" : "低"), cargo, WAIT_HOURS, 0, 0, 0,
                costCalculator.fuelLiters(baselineKm), intermodal.roadCostYuan(baselineKm),
                costCalculator.carbonKgForRoad(baselineKm)).toMap());
        plans.add(planC);

        return plans;
    }

    /** 方案C 的卡片标题：全域受阻时按建议动作改写，否则仍是「原地等待」 */
    private static String planCName(Map<String, Object> advisory) {
        if (advisory == null) {
            return "原地等待";
        }
        return switch (String.valueOf(advisory.getOrDefault("action", ""))) {
            case CorridorAdvisoryService.ACTION_RETURN -> "返回起点待命";
            case CorridorAdvisoryService.ACTION_HOLD -> "就近安全停靠等待";
            case CorridorAdvisoryService.ACTION_DEFER -> "暂缓发车待命";
            default -> "原地等待";
        };
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
        // 路网级全域受阻优先于一切单断面判定：此时 A 的“有解”是把已封闭边降级换来的，
        // 再推「继续前进」等于从一个风险区换进另一个风险区，此时只有返回或等待是安全的选择
        if (planA != null && planA.get("advisoryAction") != null) {
            return "C";
        }
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
            // 公路未被波及时会得到「公路直达X」这种名字，再拼“改走公路绕行”就与标题矛盾
            Map<String, Object> pa = planById(plans, "A");
            String tail = Boolean.TRUE.equals(pa == null ? null : pa.get("routeAffected"))
                    ? "改走公路绕行，时效与成本最优。"
                    : "公路走廊不经该熔断口岸，维持当前公路路线即可。";
            return String.format(
                    "推荐方案A（%s）：平陆运河禁航（%s），水运不可用；%s",
                    rec.get("name"),
                    sandbox.waterBlockedReason() == null ? "通航条件超限" : sandbox.waterBlockedReason(),
                    tail);
        }
        if ("C".equals(recommendedId)) {
            // 全域受阻：措辞取自 advisory 本体，动作（返回/停靠/暂缓）与大屏、司机端完全一致
            Object advRaw = rec.get("advisory");
            if (advRaw instanceof Map<?, ?> adv) {
                return "推荐方案C（" + rec.get("name") + "）：" + adv.get("advice");
            }
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
        // 本程路线是否真被灾害改过：没改过就不能给模型「可用绕行：友谊关」这种提示——
        // 司机本来就走友谊关，模型拿到这句必然推荐“绕行友谊关”。
        boolean routeAffected = Boolean.TRUE.equals(planA.get("routeAffected"));

        StringBuilder p = new StringBuilder();
        p.append("你是多式联运方案生成专家。已知：\n");
        if (routeAffected) {
            p.append("- 熔断路段：").append(blocked).append('\n');
            p.append("- 可用绕行：").append(detour == null ? "无（两个口岸均不可用）" : detour).append('\n');
        } else {
            p.append("- 熔断路段：").append(blocked).append("（不在本程路线上）\n");
            p.append("- 本程公路路线：").append(planA.get("name"))
                    .append("，未被上述熔断波及；禁止建议公路绕行或改道\n");
        }
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
        p.append(routeAffected ? "- 公路绕行数据：时间" : "- 公路维持当前路线数据：时间").append(fmtDelta(planA.get("extraHours")))
                .append("h，成本").append(fmtDelta(planA.get("costDeltaYuan"))).append("元\n");
        p.append("- 陆水联运数据：时间").append(fmtDelta(planB.get("extraHours")))
                .append("h，成本").append(fmtDelta(planB.get("costDeltaYuan"))).append("元\n");
        p.append("- 原地等待数据：时间+").append(String.format("%.0f", WAIT_HOURS))
                .append("h，货损率").append(planC.get("damageRatePct")).append("%\n");
        // 全域受阻时必须把这条结论喂给模型：否则它会按「还有绕行余地」的正常改道口吻推荐前进
        Object advRaw = planC.get("advisory");
        if (advRaw instanceof Map<?, ?> adv) {
            p.append("- 全域受阻结论（必须采纳，不得建议继续前进）：").append(adv.get("advice")).append('\n');
        }
        p.append("- 货物：").append(cargo.name())
                .append(cargo.coldChain() ? "，冷链" : "").append('\n');
        // 全要素货损对比 + 综合研判摘要，让模型在方案对比里引用"环境+延误"货损而非只谈时效
        p.append("- 各方案货损指数（环境温/湿+延误）：A 绕行 ").append(planA.get("damageRisk"))
                .append("(").append(planA.get("damageScore")).append("分)，B 联运 低，C 等待 ")
                .append(planC.get("damageRisk")).append("(").append(planC.get("damageScore")).append("分)\n");
        Object comp = risk == null || risk.explanation() == null ? null : risk.explanation().get("compositeSummary");
        if (comp != null) {
            p.append("- 路面综合研判摘要：").append(comp).append('\n');
        }
        p.append("\n请输出三个方案对比与推荐，JSON格式：\n")
                .append("{\n")
                .append("  \"solutions\": [\n")
                // 名称用实际方案名，避免模型沿用"公路绕行芒街"这个在台风场景下错误的示例
                .append("    {\"name\":\"").append(planA.get("name"))
                .append("\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"陆水联运平陆运河\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"},\n")
                .append("    {\"name\":\"").append(planC.get("name"))
                .append("\",\"time\":\"...\",\"cost\":\"...\",\"risk\":\"...\"}\n")
                .append("  ],\n")
                .append("  \"recommendation\": \"推荐方案及理由（不超过80字）\"\n")
                .append("}");
        return p.toString();
    }

    /**
     * 常态推荐语 Prompt：车刚出发、走廊正常，公路（A）与平陆运河联运（B）都可行，
     * 只让模型讲清"两条都能走、各自适合什么情况"，不得出现"熔断/绕行/等待"这类改道口吻。
     * <p>
     * 例外：口岸熔断了但断的不是本程这条线（如台风打芒街、司机走友谊关）也走常态，
     * 此时不能再写"无熔断"（那是假话），要如实说明断在哪、本程为何不用改道。
     */
    private String buildNormalPrompt(List<Map<String, Object>> plans) {
        Map<String, Object> planA = planById(plans, "A");
        Map<String, Object> planB = planById(plans, "B");
        DemoConfigService.CargoType cargo = config.cargo();
        String blocked = sandbox.blockedPortNames();
        StringBuilder p = new StringBuilder();
        p.append("你是多式联运方案生成专家。");
        if (blocked == null) {
            p.append("当前南宁→河内走廊无熔断、无禁航，属常态。已知：\n");
        } else {
            p.append("当前有口岸熔断（").append(blocked).append("），但不在本程路线上，"
                    + "本程维持「").append(planA == null ? "公路" : planA.get("name"))
                    .append("」，属常态通行。已知：\n")
                    .append("- 禁止建议绕行、改道、等待：需要绕开的口岸根本不在这条线上\n");
        }
        p.append("- 方案A 全程公路：约 ").append(fmtHours(planA)).append(" 小时，时效最优\n");
        if (planB != null && !Boolean.TRUE.equals(planB.get("notApplicable"))) {
            p.append("- 方案B 陆水联运（平陆运河）：约 ").append(fmtHours(planB))
                    .append(" 小时，车辆滚装上船、经运河转海运到海防，成本与碳排放更低\n");
        }
        p.append("- 货物：").append(cargo.name()).append(cargo.coldChain() ? "，冷链" : "").append('\n');
        if (blocked == null) {
            p.append("\n请用不超过80字说明：两条路线都是正常可选项（不是备选兜底）——")
                    .append("赶时效选全程公路，不急、想省成本/低碳甚至可不带货全程坐船出境则选平陆运河联运。\n");
        } else {
            p.append("\n请用不超过80字说明：受影响的只是").append(blocked)
                    .append("，本程不受影响，维持当前公路路线即可（不提绕行/改道/等待）；")
                    .append("若不想只跑公路，平陆运河陆水联运同样是正常选项。\n");
        }
        p.append("只输出 JSON：{\"recommendation\":\"...\"}");
        return p.toString();
    }

    /**
     * 缓存池情境签名：模式 + 推荐方案 + 熔断成因。
     * 粒度适中——同一局势下不同轮次的文案进同一池供轮换，不同局势（常态/改道、不同口岸）不串用。
     * <p>
     * 额外包一层：有口岸熔断但本程未受影响时单独建池。否则一句
     * 「芒街熔断不影响本程」会混进“什么都没断”的常态池里，断网时被拿到无关场合去说。
     */
    private String situationKey(String mode, String recommendedId, List<Map<String, Object>> plans) {
        String cause = sandbox.fuseCause();
        String key = mode + ":" + recommendedId + ":" + (cause == null ? "none" : cause);
        return unaffectedNote(plans) == null ? key : key + ":unaffected";
    }

    /**
     * 常态兜底的最后一句固定话术（仅当模型不可用且缓存池也无对应情境时才用）。
     * 保留旧措辞，保证离线首次、无任何历史时仍与“常态双选项都合法”的事实一致。
     */
    private String normalRecommendation() {
        return "去河内不必只有公路：本走廊常态下即可走平陆运河陆水联运（方案B），车辆滚装上船、随船经运河转海运到海防，"
                + "不赶时间甚至不带货也能全程坐船出境；追求时效则直走全程公路（方案A）最快。两条都是正常可选项，不是备选兜底。";
    }

    /** 从方案卡读全程小时数（常态 Prompt 用）；缺失时给占位符，不影响模型生成。 */
    private static String fmtHours(Map<String, Object> plan) {
        Object h = plan == null ? null : plan.get("totalHours");
        return h instanceof Number n ? String.format("%.0f", n.doubleValue()) : "—";
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
     * 方案A 名称：只在「本程真的被改道了」时才叫绕行。
     * <ul>
     *   <li>友谊关被熔断 → 绕行芒街（原剧本场景）</li>
     *   <li>芒街被熔断但司机本来就走友谊关 → 路线一根线没动，叫「公路直达友谊关」，
     *       不能再叫「绕行友谊关」——那是把司机已经在走的路说成是新方案</li>
     *   <li>两个都断 → 无可用公路绕行</li>
     *   <li>都没有断 → 默认主通道友谊关</li>
     * </ul>
     */
    private String planAName(boolean routeAffected, String portOnRoute) {
        String blocked = sandbox.blockedPortNames();
        // 没绕行（或压根没熔断）：名字只报「这趟走哪个口岸」，不出现“绕行”二字
        if (!routeAffected || blocked == null) {
            String on = shortPort(portOnRoute != null ? portOnRoute
                    : (blocked == null ? sandbox.ygg().name : detourPorts(blocked)));
            return on == null ? "维持当前公路路线" : "公路直达" + on;
        }
        String detour = detourPorts(blocked);
        if (detour == null) {
            return "公路绕行（无可用口岸）";
        }
        return "公路绕行" + detour;
    }

    /**
     * 本程路线实际踩在哪个口岸断面上（两口岸都不经时返回 null，如国内段南宁→六景）。
     * 供方案命名用：司机走的是芒街线时就不能把方案写成「直达友谊关」。
     */
    private String routePortOf(RouteResponse route) {
        if (route == null || route.getPathEdgeIds() == null) {
            return null;
        }
        List<String> ids = route.getPathEdgeIds();
        if (ids.contains(sandbox.mc().edgeId)) {
            return sandbox.mc().name;
        }
        if (ids.contains(sandbox.ygg().edgeId)) {
            return sandbox.ygg().name;
        }
        return null;
    }

    /** 口岸名去掉「口岸」后缀，方案标题更短（友谊关口岸 → 友谊关） */
    private static String shortPort(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String s = name.replace("口岸", "").trim();
        return s.isEmpty() ? null : s;
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

    /** 小时数文案（整值不保留小数），与 CorridorAdvisoryService 的等待时长写法一致 */
    private static String hoursText(double h) {
        return h == Math.floor(h) ? String.format("%.0f小时", h) : String.format("%.1f小时", h);
    }

    /** 货损说明：有因子时"建议：因子…"，无不利要素时如实说安全。 */
    private static String cargoDamageNote(CargoSafetyIndex.Result cargo) {
        if (cargo.score() <= 0 || cargo.factors().isEmpty()) {
            return cargo.advice();
        }
        return cargo.advice() + "：" + String.join("；", cargo.factors());
    }

    /** 取两个风险等级中更不利者（低<中<高<极高）。 */
    private static String worseLevel(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String level) {
        return switch (level == null ? "" : level) {
            case "极高" -> 4;
            case "高" -> 3;
            case "中" -> 2;
            case "低" -> 1;
            default -> 0;
        };
    }
}
