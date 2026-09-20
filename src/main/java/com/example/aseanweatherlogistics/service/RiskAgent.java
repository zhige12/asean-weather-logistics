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

    /** 研判结果。llmJson 非空表示本次走了大模型推理。 */
    public record RiskResult(Map<String, Object> explanation, Map<String, Object> llmJson,
                             List<String> knowledgeRefs, boolean aiPowered) {
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
        try {
            String prompt = buildPrompt(ygg, mc, threshold, ragText.toString());
            String output = deepseekService.chat(
                    "你是跨境物流气象风险研判专家。只输出 JSON，不要输出任何其他文字。",
                    prompt, 500);
            JsonNode parsed = AgentJson.extractObject(output);
            if (parsed != null) {
                llmJson = new LinkedHashMap<>();
                putIfText(llmJson, parsed, "blockedSegment");
                putIfText(llmJson, parsed, "riskLevel");
                putIfText(llmJson, parsed, "reason");
                putIfText(llmJson, parsed, "decisionBoundary");
                putIfText(llmJson, parsed, "alternative");
                if (llmJson.isEmpty()) {
                    llmJson = null;
                }
            }
        } catch (Exception e) {
            log.info("风险研判智能体大模型调用失败，使用规则模板：{}", e.toString());
        }
        if (llmJson != null) {
            explanation.put("llm", llmJson);
        }
        explanation.put("knowledgeRefs", knowledgeRefs);
        return new RiskResult(explanation, llmJson, knowledgeRefs, llmJson != null);
    }

    private Map<String, Object> buildExplanation(DecisionSandboxService.Section ygg,
                                                 DecisionSandboxService.Section mc,
                                                 double threshold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thresholdMm", threshold);
        m.put("dataSource", "大赛官方气象接口");

        // 灾害场景注入（暴雨·友谊关 / 大雾·芒街）：与沙盘雨量熔断等效，解释必须反映
        com.example.aseanweatherlogistics.model.dto.RiskSegment yggInjected = sandbox.injectedRisk(ygg);
        com.example.aseanweatherlogistics.model.dto.RiskSegment mcInjected = sandbox.injectedRisk(mc);

        Map<String, Object> yggExp = new LinkedHashMap<>();
        yggExp.put("section", ygg.name);
        yggExp.put("forecastMm", ygg.forecastMm);
        yggExp.put("fused", sandbox.roadBlocked());
        if (ygg.fused) {
            yggExp.put("verdict", String.format(
                    "%s段熔断原因：未来6小时累计降雨量%.0fmm，超过%.0fmm熔断阈值。"
                            + "同时，该段边坡土质为%s，%s。当前边坡含水量已达临界值%.0f%%，滑坡风险等级为高。",
                    ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history, ygg.moisturePct));
        } else if (yggInjected != null && yggInjected.getPenaltyMultiplier() != null
                && yggInjected.getPenaltyMultiplier() >= 100) {
            yggExp.put("verdict", String.format(
                    "%s段熔断原因（灾害场景注入）：%s，惩罚系数%.0f，等效道路中断。"
                            + "该段边坡土质为%s，%s，与注入灾害类型高度吻合，风险等级为高。",
                    ygg.name, yggInjected.getReason(), yggInjected.getPenaltyMultiplier(),
                    ygg.slopeSoil, ygg.history));
            yggExp.put("fused", true);
        } else if (yggInjected != null) {
            yggExp.put("verdict", String.format(
                    "%s段未熔断（当前预测降雨量%.0fmm，低于%.0fmm熔断阈值），"
                            + "但已注入风险：%s（惩罚系数%.0f），风险等级上调，建议谨慎通行。边坡土质为%s，%s。",
                    ygg.name, ygg.forecastMm, threshold,
                    yggInjected.getReason(),
                    yggInjected.getPenaltyMultiplier() == null ? 0 : yggInjected.getPenaltyMultiplier(),
                    ygg.slopeSoil, ygg.history));
        } else {
            yggExp.put("verdict", String.format(
                    "%s段未熔断：当前预测降雨量%.0fmm，低于%.0fmm熔断阈值。边坡土质为%s，%s。",
                    ygg.name, ygg.forecastMm, threshold, ygg.slopeSoil, ygg.history));
        }
        yggExp.put("slopeSoil", ygg.slopeSoil);
        yggExp.put("history", ygg.history);
        yggExp.put("moisturePct", ygg.moisturePct);
        if (yggInjected != null) {
            yggExp.put("injectedRisk", yggInjected.getReason());
        }

        Map<String, Object> mcExp = new LinkedHashMap<>();
        mcExp.put("section", mc.name);
        mcExp.put("forecastMm", mc.forecastMm);
        mcExp.put("fused", mc.fused);
        if (mc.fused) {
            mcExp.put("verdict", String.format(
                    "%s段熔断原因：预计降雨量%.0fmm，超过%.0fmm熔断阈值。",
                    mc.name, mc.forecastMm, DecisionSandboxService.MC_THRESHOLD_MM));
        } else if (mcInjected != null) {
            double p = mcInjected.getPenaltyMultiplier() == null ? 0 : mcInjected.getPenaltyMultiplier();
            if (p >= 100) {
                mcExp.put("verdict", String.format(
                        "%s段熔断原因（灾害场景注入）：%s，惩罚系数%.0f，等效通关中断。",
                        mc.name, mcInjected.getReason(), p));
                mcExp.put("fused", true);
            } else {
                mcExp.put("verdict", String.format(
                        "%s段未熔断（预计降雨量%.0fmm，未触发%.0fmm熔断阈值），"
                                + "但已注入风险：%s（惩罚系数%.0f），边坡土质为%s，风险等级为中。",
                        mc.name, mc.forecastMm, DecisionSandboxService.MC_THRESHOLD_MM,
                        mcInjected.getReason(), p, mc.slopeSoil));
            }
            mcExp.put("injectedRisk", mcInjected.getReason());
        } else {
            mcExp.put("verdict", String.format(
                    "%s段未熔断原因：预计降雨量%.0fmm，未触发%.0fmm熔断阈值。边坡土质为%s。风险等级为中。",
                    mc.name, mc.forecastMm, DecisionSandboxService.MC_THRESHOLD_MM, mc.slopeSoil));
        }
        mcExp.put("slopeSoil", mc.slopeSoil);

        m.put("ygg", yggExp);
        m.put("mc", mcExp);
        m.put("boundary", String.format(
                "决策边界：%s降雨量需降至%.0fmm以下才恢复通行；%s降雨量升至%.0fmm以上将同步熔断。",
                ygg.name, threshold - DecisionSandboxService.HYSTERESIS_MM,
                mc.name, DecisionSandboxService.MC_THRESHOLD_MM));
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
        p.append("- 熔断阈值：").append(String.format("%.0f", threshold)).append("mm\n");
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
