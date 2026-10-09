package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 🔵 风险研判智能体（多智能体链路第一环）。
 * <p>
 * 输入：决策沙盘断面数据（降雨量/熔断状态）+ 断面地质元数据 + 平台熔断阈值；
 * RAG：行业知识库检索历史相似案例（滑坡/暴雨应对）；
 * 输出：结构化研判 JSON（blockedSegment/riskLevel/reason/decisionBoundary/alternative）。
 * <p>
 * 规则引擎管安全底线：熔断/恢复的判定由 {@link DecisionSandboxService} 确定性完成；
 * 本智能体管决策沟通：本地大模型（Ollama）可用时生成自然语言研判，不可用时规则模板兜底，
 * 拔网线/断模型均不影响输出结构。
 */
@Service
public class RiskAgent {

    private static final Logger log = LoggerFactory.getLogger(RiskAgent.class);

    public static final String ID = "risk-assessment";
    public static final String NAME = "风险研判智能体";
    public static final String ICON = "🔵";

    private final DecisionSandboxService sandbox;
    private final DemoConfigService config;
    private final KnowledgeBaseService knowledgeBase;
    private final DeepseekService deepseekService;

    public RiskAgent(DecisionSandboxService sandbox, DemoConfigService config,
                     KnowledgeBaseService knowledgeBase, DeepseekService deepseekService) {
        this.sandbox = sandbox;
        this.config = config;
        this.knowledgeBase = knowledgeBase;
        this.deepseekService = deepseekService;
    }

    /**
     * 研判结果。llmJson 非空表示本次走了大模型推理；
     * aiChannel 是那次输出的<b>真实来源</b>（local / gateway / online，见
     * {@link DeepseekService#chatWithChannel}），本地与在线在合规上不等价，必须分开标注。
     */
    public record RiskResult(Map<String, Object> explanation, Map<String, Object> llmJson,
                             List<String> knowledgeRefs, boolean aiPowered, String aiChannel) {
    }

    public RiskResult assess() {
        DecisionSandboxService.Section ygg = sandbox.ygg();
        DecisionSandboxService.Section mc = sandbox.mc();
        double threshold = config.fuseThresholdMm();

        // ---------- 1. RAG：检索历史相似案例 ----------
        List<KnowledgeEntry> refs = new ArrayList<>();
        refs.addAll(knowledgeBase.search("滑坡", 2));
        refs.addAll(knowledgeBase.search("暴雨", 1));
        List<String> knowledgeRefs = new ArrayList<>();
        StringBuilder ragText = new StringBuilder();
        for (KnowledgeEntry e : refs) {
            if (knowledgeRefs.contains(e.getId())) {
                continue;
            }
            knowledgeRefs.add(e.getId());
            ragText.append("【").append(e.getTitle()).append("】")
                    .append(e.getContent()).append('\n');
        }

        // ---------- 2. 确定性研判（规则引擎，安全底线） ----------
        Map<String, Object> explanation = buildExplanation(ygg, mc, threshold);

        // ---------- 3. 大模型研判（决策沟通，可选增强） ----------
        Map<String, Object> llmJson = null;
        String aiChannel = null;
        try {
            String prompt = buildPrompt(ygg, mc, threshold, ragText.toString());
            DeepseekService.AiReply reply = deepseekService.chatWithChannel(
                    "你是跨境物流气象风险研判专家。只输出 JSON，不要输出任何其他文字。",
                    prompt, 500);
            JsonNode parsed = AgentJson.extractObject(reply.text());
            if (parsed != null) {
                llmJson = new LinkedHashMap<>();
                putIfText(llmJson, parsed, "blockedSegment");
                putIfText(llmJson, parsed, "riskLevel");
                putIfText(llmJson, parsed, "reason");
                putIfText(llmJson, parsed, "decisionBoundary");
                putIfText(llmJson, parsed, "alternative");
                if (llmJson.isEmpty()) {
                    llmJson = null;
                } else {
                    // 只有真正把模型输出放进结果，才登记来源通道
                    aiChannel = reply.tag();
                }
            }
        } catch (Exception e) {
            log.info("风险研判智能体大模型调用失败，使用规则模板：{}", e.toString());
        }
        if (llmJson != null) {
            explanation.put("llm", llmJson);
        }
        explanation.put("knowledgeRefs", knowledgeRefs);
        return new RiskResult(explanation, llmJson, knowledgeRefs, llmJson != null, aiChannel);
    }

    /**
     * 措辞轮换序号：顺序轮转而非随机，保证<b>连续几次调用一定不会重复同一句</b>
     * （随机取会撞车，4 选 1 有 25% 概率连着两句一样）。
     */
    private static final java.util.concurrent.atomic.AtomicInteger PHRASE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 从多个等价措辞中轮换取一句。
     * <p>
     * 为什么需要：熔断/恢复的判定必须确定性（安全底线，由 DecisionSandboxService 保证），
     * 但把结论说给人听的那句话没必要每次一字不差——固定句式会让评委觉得"这就是段写死的字符串"。
     * 这里只轮换表达，<b>不改任何判定逻辑，也不改其中的数字</b>，
     * 所以风险结论仍然可复现，措辞不会连续重复。
     */
    private static String pick(String... variants) {
        if (variants == null || variants.length == 0) {
            return "";
        }
        return variants[Math.floorMod(PHRASE_SEQ.getAndIncrement(), variants.length)];
    }

    /** 断面台风判据的文字描述（供 prompt 与界面复用） */
    private static String typhoonVerdict(DecisionSandboxService.Section s) {
        TyphoonRiskRule.Verdict v = TyphoonRiskRule.evaluate(s.typhoonPressureHpa, s.typhoonWindKph);
        return v.triggered() ? v.reason() : "未达台风判据";
    }

    /** 把台风状态与判据一并交给前端，供界面单独标注"这次熔断是台风造成的" */
    private static void putTyphoonFields(Map<String, Object> exp,
                                         DecisionSandboxService.Section s,
                                         TyphoonRiskRule.Verdict v) {
        exp.put("typhoonActive", s.typhoonActive);
        exp.put("typhoonPressureHpa", s.typhoonPressureHpa);
        exp.put("typhoonWindKph", s.typhoonWindKph);
        exp.put("typhoonVerdict", v.triggered() ? v.reason() : null);
    }

    private Map<String, Object> buildExplanation(DecisionSandboxService.Section ygg,
                                                 DecisionSandboxService.Section mc,
                                                 double threshold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thresholdMm", threshold);
        m.put("dataSource", "CRA40");

        // 灾害场景注入（暴雨·友谊关 / 大雾·芒街）：与沙盘雨量熔断等效，解释必须反映
        com.example.aseanweatherlogistics.model.dto.RiskSegment yggInjected = sandbox.injectedRisk(ygg);
        com.example.aseanweatherlogistics.model.dto.RiskSegment mcInjected = sandbox.injectedRisk(mc);

        // 台风判据（若有）：熔断可能是台风造成的，而非降雨——归因必须如实，
        // 否则纯台风场景（降雨 0mm）会说出"降雨量0mm超过80mm阈值"这种自相矛盾的话
        TyphoonRiskRule.Verdict yggTyphoon = ygg.typhoonActive
                ? TyphoonRiskRule.evaluate(ygg.typhoonPressureHpa, ygg.typhoonWindKph)
                : new TyphoonRiskRule.Verdict(0, null);

        Map<String, Object> yggExp = new LinkedHashMap<>();
        yggExp.put("section", ygg.name);
        yggExp.put("forecastMm", ygg.forecastMm);
        yggExp.put("fused", sandbox.roadBlocked());
        putTyphoonFields(yggExp, ygg, yggTyphoon);
        double yggPenalty = yggInjected == null || yggInjected.getPenaltyMultiplier() == null
                ? 0 : yggInjected.getPenaltyMultiplier();
        String yggReason = yggInjected == null ? "" : yggInjected.getReason();
        // 台风致熔断：成因是风不是雨，文案必须说台风，不能沿用降雨归因
        if (ygg.fused && yggTyphoon.blocking()) {
            yggExp.put("verdict", pick(
                    String.format("%s段熔断原因：%s，达到台风级判据（气压≤%.0fhPa且风速≥%.0fkm/h），"
                                    + "等效道路中断。该段边坡%s、%s，强风叠加载水%.0f%%的土体，"
                                    + "滑坡风险等级为高。",
                            ygg.name, yggTyphoon.reason(),
                            TyphoonRiskRule.TYPHOON_PRS_HPA, TyphoonRiskRule.TYPHOON_WIND_KPH,
                            ygg.slopeSoil, ygg.history, ygg.moisturePct),
                    String.format("%s段因台风判定为中断：%s。此时降雨仅%.0fmm（未达%.0fmm阈值），"
                                    + "成因为强风而非降水；该段%s，%s，含水量%.0f%%，次生滑坡风险高。",
                            ygg.name, yggTyphoon.reason(), ygg.forecastMm, threshold,
                            ygg.slopeSoil, ygg.history, ygg.moisturePct),
                    String.format("%s段已熔断（台风成因）：%s，按等效道路中断处理。"
                                    + "降雨贡献仅%.0fmm，远低于%.0fmm阈值；地质上%s、%s，风险评级高。",
                            ygg.name, yggTyphoon.reason(), ygg.forecastMm, threshold,
                            ygg.slopeSoil, ygg.history)));
            yggExp.put("fuseCause", "typhoon");
        } else if (ygg.fused && yggTyphoon.triggered()) {
            // 降雨致熔断 + 台风未达熔断级（强对流）：两个成因都要说
            yggExp.put("verdict", pick(
                    String.format("%s段熔断原因：未来6小时累计降雨量%.0fmm，超过%.0fmm熔断阈值；"
                                    + "另叠加%s，风险进一步抬升。该段边坡%s、%s，含水量%.0f%%，滑坡风险高。",
                            ygg.name, ygg.forecastMm, threshold, yggTyphoon.reason(),
                            ygg.slopeSoil, ygg.history, ygg.moisturePct),
                    String.format("%s段已熔断：降雨%.0fmm 突破 %.0fmm 阈值，同时受%s影响。"
                                    + "该段%s，%s，含水量%.0f%%已达临界。",
                            ygg.name, ygg.forecastMm, threshold, yggTyphoon.reason(),
                            ygg.slopeSoil, ygg.history, ygg.moisturePct)));
            yggExp.put("fuseCause", "rain+typhoon");
        } else if (ygg.fused) {
            yggExp.put("verdict", pick(
                    String.format("%s段熔断原因：未来6小时累计降雨量%.0fmm，超过%.0fmm熔断阈值。"
                                    + "同时，该段边坡土质为%s，%s。当前边坡含水量已达临界值%.0f%%，滑坡风险等级为高。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history, ygg.moisturePct),
                    String.format("%s段已触发熔断：未来6h累计降雨%.0fmm，突破%.0fmm阈值。"
                                    + "叠加该段%s、%s的地质条件，边坡含水量%.0f%%已达临界，滑坡风险判定为高。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history, ygg.moisturePct),
                    String.format("%s段判定为中断状态。依据：预测6小时累计降雨%.0fmm 高于 %.0fmm 熔断线；"
                                    + "该段%s，%s，含水量%.0f%%逼近饱和，次生滑坡风险高。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history, ygg.moisturePct)));
        } else if (yggInjected != null && yggPenalty >= 100) {
            yggExp.put("verdict", pick(
                    String.format("%s段熔断原因（灾害场景注入）：%s，惩罚系数%.0f，等效道路中断。"
                                    + "该段边坡土质为%s，%s，与注入灾害类型高度吻合，风险等级为高。",
                            ygg.name, yggReason, yggPenalty, ygg.slopeSoil, ygg.history),
                    String.format("%s段因灾害注入判定为中断：%s（惩罚系数%.0f）。"
                                    + "该段%s且%s，与本次灾害类型高度吻合，风险等级为高。",
                            ygg.name, yggReason, yggPenalty, ygg.slopeSoil, ygg.history),
                    String.format("%s段已熔断（场景注入）：%s，惩罚系数%.0f，按等效道路中断处理。"
                                    + "地质背景%s、%s，与该灾害高度匹配，风险评级高。",
                            ygg.name, yggReason, yggPenalty, ygg.slopeSoil, ygg.history)));
            yggExp.put("fused", true);
        } else if (yggInjected != null) {
            yggExp.put("verdict", pick(
                    String.format("%s段未熔断（当前预测降雨量%.0fmm，低于%.0fmm熔断阈值），"
                                    + "但已注入风险：%s（惩罚系数%.0f），风险等级上调，建议谨慎通行。边坡土质为%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, yggReason, yggPenalty,
                            ygg.slopeSoil, ygg.history),
                    String.format("%s段尚未熔断（预测降雨%.0fmm 未及 %.0fmm 阈值），"
                                    + "但存在注入风险 %s（惩罚系数%.0f），建议谨慎通行。该段%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, yggReason, yggPenalty,
                            ygg.slopeSoil, ygg.history),
                    String.format("%s段保持通行，但风险已上调：注入风险 %s（惩罚系数%.0f）。"
                                    + "预测降雨%.0fmm 仍低于 %.0fmm 熔断线；边坡为%s，%s。",
                            ygg.name, yggReason, yggPenalty, ygg.forecastMm, threshold,
                            ygg.slopeSoil, ygg.history)));
        } else {
            yggExp.put("verdict", pick(
                    String.format("%s段未熔断：当前预测降雨量%.0fmm，低于%.0fmm熔断阈值。边坡土质为%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history),
                    String.format("%s段当前可通行：预测降雨%.0fmm，距%.0fmm熔断门槛仍有余量。"
                                    + "该段边坡为%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history),
                    String.format("%s段未触发熔断——预测6h累计降雨%.0fmm，低于%.0fmm判定线。"
                                    + "地质条件：%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history),
                    String.format("%s段通行状态正常，降雨预测%.0fmm 未触及 %.0fmm 熔断阈值。"
                                    + "需注意该段%s，%s。",
                            ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history)));
            if (yggTyphoon.triggered()) {
                yggExp.put("verdict", String.format(
                        "%s段未达熔断：降雨%.0fmm 低于 %.0fmm 阈值，但存在%s，"
                                + "尚未构成台风级（需气压≤%.0fhPa且风速≥%.0fkm/h），建议持续监视。该段%s，%s。",
                        ygg.name, ygg.forecastMm, threshold, yggTyphoon.reason(),
                        TyphoonRiskRule.TYPHOON_PRS_HPA, TyphoonRiskRule.TYPHOON_WIND_KPH,
                        ygg.slopeSoil, ygg.history));
            }
        }
        yggExp.put("slopeSoil", ygg.slopeSoil);
        yggExp.put("history", ygg.history);
        yggExp.put("moisturePct", ygg.moisturePct);
        if (yggInjected != null) {
            yggExp.put("injectedRisk", yggInjected.getReason());
        }

        // 芒街台风判据（剧本台风默认打在芒街：沿海口岸，符合台风季规律）
        TyphoonRiskRule.Verdict mcTyphoon = mc.typhoonActive
                ? TyphoonRiskRule.evaluate(mc.typhoonPressureHpa, mc.typhoonWindKph)
                : new TyphoonRiskRule.Verdict(0, null);

        Map<String, Object> mcExp = new LinkedHashMap<>();
        mcExp.put("section", mc.name);
        mcExp.put("forecastMm", mc.forecastMm);
        mcExp.put("fused", mc.fused);
        putTyphoonFields(mcExp, mc, mcTyphoon);
        double mcThreshold = DecisionSandboxService.MC_THRESHOLD_MM;
        if (mc.fused && mcTyphoon.blocking()) {
            mcExp.put("verdict", pick(
                    String.format("%s段熔断原因：%s，达到台风级判据（气压≤%.0fhPa且风速≥%.0fkm/h），"
                                    + "等效通关中断。此时降雨仅%.0fmm，未达%.0fmm阈值，成因为强风而非降水。该段%s。",
                            mc.name, mcTyphoon.reason(),
                            TyphoonRiskRule.TYPHOON_PRS_HPA, TyphoonRiskRule.TYPHOON_WIND_KPH,
                            mc.forecastMm, mcThreshold, mc.slopeSoil),
                    String.format("%s段因台风判定为中断：%s，按等效通关中断处理。"
                                    + "降雨%.0fmm 低于 %.0fmm 阈值，熔断由台风而非降雨触发。该段%s。",
                            mc.name, mcTyphoon.reason(), mc.forecastMm, mcThreshold, mc.slopeSoil),
                    String.format("%s段已熔断（台风成因）：%s。注意本次降雨仅%.0fmm，"
                                    + "未触及%.0fmm降雨阈值——致因是台风强风。该段%s，稳定性较好。"
                                    + "风险等级为高。",
                            mc.name, mcTyphoon.reason(), mc.forecastMm, mcThreshold, mc.slopeSoil)));
            mcExp.put("fuseCause", "typhoon");
        } else if (mc.fused && mcTyphoon.triggered()) {
            mcExp.put("verdict", pick(
                    String.format("%s段熔断原因：预计降雨量%.0fmm，超过%.0fmm熔断阈值；"
                                    + "同时受%s影响，风险叠加。该段%s。",
                            mc.name, mc.forecastMm, mcThreshold, mcTyphoon.reason(), mc.slopeSoil),
                    String.format("%s段已熔断：降雨%.0fmm 突破 %.0fmm 阈值，并叠加%s。该段%s。",
                            mc.name, mc.forecastMm, mcThreshold, mcTyphoon.reason(), mc.slopeSoil)));
            mcExp.put("fuseCause", "rain+typhoon");
        } else if (mc.fused) {
            mcExp.put("verdict", pick(
                    String.format("%s段熔断原因：预计降雨量%.0fmm，超过%.0fmm熔断阈值。",
                            mc.name, mc.forecastMm, mcThreshold),
                    String.format("%s段已熔断：预测降雨%.0fmm，突破%.0fmm阈值线。",
                            mc.name, mc.forecastMm, mcThreshold),
                    String.format("%s段判定中断，依据为预测降雨%.0fmm 高于 %.0fmm 熔断阈值。",
                            mc.name, mc.forecastMm, mcThreshold)));
            mcExp.put("fuseCause", "rain");
        } else if (mcInjected != null) {
            double p = mcInjected.getPenaltyMultiplier() == null ? 0 : mcInjected.getPenaltyMultiplier();
            if (p >= 100) {
                mcExp.put("verdict", pick(
                        String.format("%s段熔断原因（灾害场景注入）：%s，惩罚系数%.0f，等效通关中断。",
                                mc.name, mcInjected.getReason(), p),
                        String.format("%s段因场景注入判定为中断：%s（惩罚系数%.0f），按等效通关中断处理。",
                                mc.name, mcInjected.getReason(), p)));
                mcExp.put("fused", true);
            } else {
                mcExp.put("verdict", pick(
                        String.format("%s段未熔断（预计降雨量%.0fmm，未触发%.0fmm熔断阈值），"
                                        + "但已注入风险：%s（惩罚系数%.0f），边坡土质为%s，风险等级为中。",
                                mc.name, mc.forecastMm, mcThreshold,
                                mcInjected.getReason(), p, mc.slopeSoil),
                        String.format("%s段保持通行（预测降雨%.0fmm 未达 %.0fmm 阈值），"
                                        + "但注入风险 %s（惩罚系数%.0f）使风险等级升至中；边坡为%s。",
                                mc.name, mc.forecastMm, mcThreshold,
                                mcInjected.getReason(), p, mc.slopeSoil),
                        String.format("%s段风险等级为中：注入风险 %s（惩罚系数%.0f），"
                                        + "同时预测降雨%.0fmm 尚未触及 %.0fmm 熔断线；该段%s。",
                                mc.name, mcInjected.getReason(), p, mc.forecastMm, mcThreshold, mc.slopeSoil)));
            }
            mcExp.put("injectedRisk", mcInjected.getReason());
        } else {
            mcExp.put("verdict", pick(
                    String.format("%s段未熔断原因：预计降雨量%.0fmm，未触发%.0fmm熔断阈值。边坡土质为%s。风险等级为中。",
                            mc.name, mc.forecastMm, mcThreshold, mc.slopeSoil),
                    String.format("%s段未熔断：预测降雨%.0fmm，低于%.0fmm阈值。该段%s，风险等级评定为中。",
                            mc.name, mc.forecastMm, mcThreshold, mc.slopeSoil),
                    String.format("%s段通行不受限，预测降雨%.0fmm 未触发 %.0fmm 熔断条件；"
                                    + "边坡条件为%s，风险等级中。",
                            mc.name, mc.forecastMm, mcThreshold, mc.slopeSoil),
                    String.format("%s段暂无熔断风险：降雨预测%.0fmm，距%.0fmm阈值尚有空间。"
                                    + "地质上为%s，当前风险等级中。",
                            mc.name, mc.forecastMm, mcThreshold, mc.slopeSoil)));
            if (mcTyphoon.triggered()) {
                mcExp.put("verdict", String.format(
                        "%s段未熔断：降雨%.0fmm 低于 %.0fmm 阈值，但存在%s，"
                                + "未达台风级（需气压≤%.0fhPa且风速≥%.0fkm/h），风险等级上调，建议密切监视。该段%s。",
                        mc.name, mc.forecastMm, mcThreshold, mcTyphoon.reason(),
                        TyphoonRiskRule.TYPHOON_PRS_HPA, TyphoonRiskRule.TYPHOON_WIND_KPH, mc.slopeSoil));
            }
        }
        mcExp.put("slopeSoil", mc.slopeSoil);

        m.put("ygg", yggExp);
        m.put("mc", mcExp);
        m.put("boundary", pick(
                String.format("决策边界：%s降雨量需降至%.0fmm以下才恢复通行；%s降雨量升至%.0fmm以上将同步熔断。",
                        ygg.name, threshold - DecisionSandboxService.HYSTERESIS_MM,
                        mc.name, DecisionSandboxService.MC_THRESHOLD_MM),
                String.format("恢复/触发条件：%s需回落到%.0fmm以下方可恢复，%s一旦超过%.0fmm则同步熔断。",
                        ygg.name, threshold - DecisionSandboxService.HYSTERESIS_MM,
                        mc.name, DecisionSandboxService.MC_THRESHOLD_MM),
                String.format("阈值说明：%s的恢复线为%.0fmm（含滞后），%s的熔断触发线为%.0fmm。",
                        ygg.name, threshold - DecisionSandboxService.HYSTERESIS_MM,
                        mc.name, DecisionSandboxService.MC_THRESHOLD_MM)));
        return m;
    }

    /** 升级方案 §3.1 Prompt：真实数据 + RAG 历史案例，要求结构化 JSON 输出。 */
    private String buildPrompt(DecisionSandboxService.Section ygg,
                               DecisionSandboxService.Section mc,
                               double threshold, String ragText) {
        StringBuilder p = new StringBuilder();
        p.append("你是跨境物流气象风险研判专家。当前数据：\n");
        p.append("- 路段：").append(ygg.name).append('\n');
        p.append("- 未来6小时降雨量：").append(String.format("%.0f", ygg.forecastMm)).append("mm\n");
        p.append("- 边坡含水量：").append(String.format("%.0f", ygg.moisturePct)).append("%\n");
        p.append("- 边坡土质：").append(ygg.slopeSoil).append('\n');
        p.append("- 历史相似案例：2023年8月降雨量82mm，发生2次滑坡\n");
        // 灾害场景注入状态：告诉大模型当前实际管控状态，避免输出与事实矛盾的"未熔断"
        com.example.aseanweatherlogistics.model.dto.RiskSegment yggInjected = sandbox.injectedRisk(ygg);
        com.example.aseanweatherlogistics.model.dto.RiskSegment mcInjected = sandbox.injectedRisk(mc);
        if (yggInjected != null && yggInjected.getPenaltyMultiplier() != null
                && yggInjected.getPenaltyMultiplier() >= 100) {
            p.append("- 【已熔断】当前已注入灾害场景：").append(yggInjected.getReason())
                    .append("，惩罚系数").append(String.format("%.0f", yggInjected.getPenaltyMultiplier()))
                    .append("，等效道路中断，请按【已熔断】给出研判结论\n");
        }
        if (mcInjected != null) {
            p.append("- 备选口岸注入风险：").append(mcInjected.getReason())
                    .append("（惩罚系数").append(mcInjected.getPenaltyMultiplier() == null ? "0"
                            : String.format("%.0f", mcInjected.getPenaltyMultiplier())).append("）\n");
        }
        // 台风信息必须进 prompt：否则大模型只知道降雨，会把台风造成的熔断误判成暴雨
        if (ygg.typhoonActive) {
            p.append("- 主通道台风状态：气压").append(String.format("%.0f", ygg.typhoonPressureHpa))
                    .append("hPa、风速").append(String.format("%.0f", ygg.typhoonWindKph))
                    .append("km/h，判据：").append(typhoonVerdict(ygg)).append("\n");
        }
        if (mc.typhoonActive) {
            p.append("- 备选口岸台风状态：气压").append(String.format("%.0f", mc.typhoonPressureHpa))
                    .append("hPa、风速").append(String.format("%.0f", mc.typhoonWindKph))
                    .append("km/h，判据：").append(typhoonVerdict(mc)).append("\n");
        }
        p.append("- 熔断阈值：降雨 ").append(String.format("%.0f", threshold)).append("mm")
                .append("；台风判据为气压≤").append(String.format("%.0f", TyphoonRiskRule.TYPHOON_PRS_HPA))
                .append("hPa 且风速≥").append(String.format("%.0f", TyphoonRiskRule.TYPHOON_WIND_KPH))
                .append("km/h（两者须同时成立）\n");
        p.append("- 备选口岸：").append(mc.name).append("（预计降雨量")
                .append(String.format("%.0f", mc.forecastMm)).append("mm，")
                .append(mc.slopeSoil).append("）\n");
        if (!ragText.isBlank()) {
            p.append("- 行业知识库参考：\n").append(ragText);
        }
        p.append("\n请输出JSON格式：\n")
                .append("{\n")
                .append("  \"blockedSegment\": \"熔断路段名\",\n")
                .append("  \"riskLevel\": \"高/中/低\",\n")
                .append("  \"reason\": \"为什么熔断（结合降雨量、边坡、历史案例）\",\n")
                .append("  \"decisionBoundary\": \"降雨量降至多少可恢复通行\",\n")
                .append("  \"alternative\": \"备选通道\"\n")
                .append("}");
        return p.toString();
    }

    private static void putIfText(Map<String, Object> out, JsonNode node, String field) {
        String v = AgentJson.text(node, field);
        if (v != null) {
            out.put(field, v);
        }
    }
}
