package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 🟡 触达智能体（多智能体链路第三环）。
 * <p>
 * 输入：方案生成结果 + 平台配置的触达对象名单；
 * 输出：各角色专属指令预览（调度员/司机/船东/沿岸百姓）。
 * 本地大模型可用时按角色生成差异化文案（升级方案 §3.3），不可用时规则模板兜底。
 * <p>
 * 原则：司机收到的是执行指令+权益保障，不是"你想走公路还是水运"——
 * 切换运输方式是调度端的决策权限。
 */
@Service
public class TouchAgent {

    private static final Logger log = LoggerFactory.getLogger(TouchAgent.class);

    public static final String ID = "outreach";
    public static final String NAME = "触达智能体";
    public static final String ICON = "🟡";

    private final DemoConfigService config;
    private final DeepseekService deepseekService;

    public TouchAgent(DemoConfigService config, DeepseekService deepseekService) {
        this.config = config;
        this.deepseekService = deepseekService;
    }

    /** 触达预览结果。 */
    public record TouchResult(Map<String, Object> preview, boolean aiPowered) {
    }

    public TouchResult generate(SolutionAgent.SolutionResult solution) {
        // 1. 规则模板生成各角色指令（兜底，也是 prompt 的参考素材）
        List<Map<String, Object>> targets = templateTargets(solution);

        // 2. 大模型按角色润色（升级方案 §3.3）
        boolean aiPowered = false;
        try {
            String output = deepseekService.chat(
                    "你是多角色预警触达专家。只输出 JSON，不要输出任何其他文字。",
                    buildPrompt(solution, targets), 700);
            JsonNode parsed = AgentJson.extractObject(output);
            if (parsed != null) {
                for (Map<String, Object> t : targets) {
                    String role = String.valueOf(t.get("role"));
                    String polished = AgentJson.text(parsed, roleKey(role));
                    if (polished != null) {
                        t.put("instruction", polished);
                        t.put("aiPolished", true);
                        aiPowered = true;
                    }
                }
            }
        } catch (Exception e) {
            log.info("触达智能体大模型调用失败，使用规则模板：{}", e.toString());
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("targets", targets);
        preview.put("count", targets.size());
        preview.put("note", "触达对象可在开放平台配置器中勾选调整");
        return new TouchResult(preview, aiPowered);
    }

    private List<Map<String, Object>> templateTargets(SolutionAgent.SolutionResult solution) {
        String planName = planName(solution);
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String t : DemoConfigService.ALL_TARGETS) {
            if (!config.targetEnabled(t)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", t);
            item.put("name", DemoConfigService.TARGET_NAMES.get(t));
            item.put("instruction", switch (t) {
                case DemoConfigService.TARGET_DISPATCHER ->
                        "确认切换" + planName + "方案，任务变更指令自动下发至各执行端";
                case DemoConfigService.TARGET_DRIVER ->
                        "任务变更通知 + 权益保障包（随船补贴/专项保险/休息舱位/返程交通/不算违约），确认后自动切换导航"
                                + "；水运方案必须点明「平陆运河」";
                case DemoConfigService.TARGET_DRIVER_VN ->
                        "越方接力司机指令（越南语为主）：接货时间地点、新路线下发、口岸优先通关，权益保障同上";
                case DemoConfigService.TARGET_SHIPOWNER ->
                        "过闸建议：流速/能见度实时研判，建议装船窗口与锚泊预案";
                case DemoConfigService.TARGET_PUBLIC ->
                        "气象预警推送（公众模式），确认回执闭环";
                default -> "";
            });
            targets.add(item);
        }
        return targets;
    }

    /** 升级方案 §3.3 Prompt：为四个角色生成专属指令。 */
    private String buildPrompt(SolutionAgent.SolutionResult solution,
                               List<Map<String, Object>> targets) {
        StringBuilder p = new StringBuilder();
        p.append("你是多角色预警触达专家。当前决策：切换").append(planName(solution)).append("。\n");
        p.append("推荐理由：").append(solution.recommendation()).append('\n');
        p.append("请为以下角色生成专属指令：\n");
        int i = 1;
        for (Map<String, Object> t : targets) {
            p.append(i++).append(". ").append(t.get("name")).append("：")
                    .append(t.get("instruction")).append('\n');
        }
        p.append("\n输出JSON格式，每个角色一段文本（每段不超过60字，执行指令口吻，不要解释）：\n")
                .append("{\n")
                .append("  \"dispatcher\": \"给调度员的指令\",\n")
                .append("  \"driver\": \"给货车司机的指令\",\n")
                .append("  \"driverVn\": \"给越南司机的指令（越南语）\",\n")
                .append("  \"shipowner\": \"给船东的指令\",\n")
                .append("  \"public\": \"给沿岸百姓的指令\"\n")
                .append("}");
        return p.toString();
    }

    private static String planName(SolutionAgent.SolutionResult solution) {
        return solution.plans().stream()
                .filter(pl -> solution.recommendedId().equals(pl.get("id")))
                .findFirst().map(pl -> String.valueOf(pl.get("name"))).orElse("备选方案");
    }

    private static String roleKey(String role) {
        return switch (role) {
            case DemoConfigService.TARGET_DISPATCHER -> "dispatcher";
            case DemoConfigService.TARGET_DRIVER -> "driver";
            case DemoConfigService.TARGET_DRIVER_VN -> "driverVn";
            case DemoConfigService.TARGET_SHIPOWNER -> "shipowner";
            case DemoConfigService.TARGET_PUBLIC -> "public";
            default -> role.toLowerCase();
        };
    }
}
