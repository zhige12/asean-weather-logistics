package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自然语言调度舱（Copilot）：把调度员的一句中文，翻成「一次受控的工具调用」。
 * <p>
 * 设计红线（与全系统一致）：
 * <ul>
 *   <li>大模型只做<b>意图解析</b>——把自然语言映射到白名单工具 + 参数，绝不参与安全判断，
 *       也不生成任何时效/成本/货损数字（数字仍由确定性引擎算）。</li>
 *   <li>只读工具（查风险 / 规划 / 决策分析 / 查口岸）直接执行并回数据；
 *       写操作（注入场景 / 改降雨 / 禁航 / 重置）一律先回「待确认预览」，
 *       由人点确认后再走 {@link #execute} 落地——延续「AI 管分析、人管决策」。</li>
 *   <li>本地/在线大模型都不可用时，退回<b>规则关键词匹配</b>解析意图，功能不中断；
 *       解析来源（本地/在线/规则）如实标注，不拿规则匹配冒充模型输出。</li>
 * </ul>
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private final DeepseekService deepseekService;
    private final DecisionSandboxService sandbox;
    private final RouteService routeService;
    private final DecisionOrchestrator orchestrator;
    private final CustomsEfficiencyService customsService;

    public CopilotService(DeepseekService deepseekService, DecisionSandboxService sandbox,
                          RouteService routeService, DecisionOrchestrator orchestrator,
                          CustomsEfficiencyService customsService) {
        this.deepseekService = deepseekService;
        this.sandbox = sandbox;
        this.routeService = routeService;
        this.orchestrator = orchestrator;
        this.customsService = customsService;
    }

    /** 一次解析结果：命中哪个工具、参数、要回给用户的话、解析来源。 */
    public record Intent(String tool, Map<String, Object> args, String say, String channel) {
    }

    /** 意图解析系统提示词：只让模型选工具 + 填参数 + 出一句回话，输出严格 JSON。 */
    private static final String INTENT_SYSTEM = """
            你是跨境物流调度台的指令解析器。根据调度员的一句话，从固定工具集中选一个并抽取参数。
            只做意图识别，不做安全判断、不编造任何数字。只输出一个 JSON 对象，不要多余文字。
            工具集（tool 取值必须是其一）：
            - query_risk   查询当前气象风险/口岸熔断状态（无参数）
            - query_customs 查询各口岸通关时效（无参数）
            - plan_routes  规划多条候选路线（无参数，起终点用上下文给定的）
            - run_analysis 触发三智能体决策分析（无参数）
            - inject_scenario 注入演练场景，args:{"scenario":"rain|typhoon|both"}
            - set_rainfall 调整断面预测降雨，args:{"yggMm":数字,"mcMm":数字}
            - block_water  模拟平陆运河禁航（无参数）
            - clear_water  恢复运河通航（无参数）
            - reset_sandbox 重置沙盘、撤销注入的风险（无参数）
            - chat         以上都不匹配的普通问答，把回答写在 say 字段
            输出形如：{"tool":"query_risk","args":{},"say":"好的，正在查友谊关风险"}
            say 是一句简短中文确认/回答；不确定的工具就用 chat。
            """;

    /**
     * 解析用户消息为工具意图。优先走大模型（本地→在线），两条通道都不通时退回规则关键词匹配。
     * 无论哪种来源，工具名都必须在白名单内，否则归为 chat（纯问答）。
     */
    public Intent parse(String message, String originId, String destinationId) {
        if (message == null || message.isBlank()) {
            return new Intent("chat", Map.of(), "请说出你的调度诉求，例如“友谊关现在什么风险”。", "rule");
        }
        // 1) 大模型解析（本地优先，≤1s 切在线）
        try {
            String user = "当前起终点：origin=" + nz(originId) + ", destination=" + nz(destinationId)
                    + "\n调度员说：" + message.trim();
            DeepseekService.AiReply reply = deepseekService.chatWithChannel(INTENT_SYSTEM, user, 300);
            JsonNode node = AgentJson.extractObject(reply.text());
            String tool = AgentJson.text(node, "tool");
            if (tool != null && isKnown(tool)) {
                Map<String, Object> args = parseArgs(node == null ? null : node.get("args"));
                String say = AgentJson.text(node, "say");
                return new Intent(tool, args, say, reply.tag());
            }
        } catch (Exception e) {
            log.info("调度舱大模型解析失败，转规则兜底：{}", e.toString());
        }
        // 2) 规则关键词兜底（离线也能用）
        return ruleParse(message);
    }

    /**
     * 对话主入口：解析意图 → 只读工具直接执行并附数据；写操作只回「待确认预览」不落。
     * 返回体供前端渲染：{tool, args, say, channel, readOnly, pendingConfirm?, preview?, data?}。
     */
    public Map<String, Object> chat(String message, String originId, String destinationId) {
        Intent intent = parse(message, originId, destinationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", intent.tool());
        out.put("args", intent.args());
        out.put("say", intent.say());
        out.put("channel", intent.channel());
        boolean readOnly = isReadOnly(intent.tool());
        out.put("readOnly", readOnly);
        if (readOnly) {
            // chat 工具无实际动作，直接回话；其余只读工具执行并回数据
            if (!"chat".equals(intent.tool())) {
                out.put("data", safeExecute(intent.tool(), intent.args(), originId, destinationId));
            }
        } else {
            // 写操作：先给人类可读的预览，等前端确认后再调 /execute 落地
            out.put("pendingConfirm", true);
            out.put("preview", describe(intent.tool(), intent.args()));
        }
        return out;
    }

    /** 确认执行：前端对某个写操作点「确认」后调用，落地并回真实结果。 */
    public Map<String, Object> execute(String tool, Map<String, Object> args,
                                       String originId, String destinationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isKnown(tool)) {
            out.put("ok", false);
            out.put("summary", "未知指令：" + tool);
            return out;
        }
        out.put("ok", true);
        out.put("data", safeExecute(tool, args, originId, destinationId));
        out.put("summary", isReadOnly(tool) ? describe(tool, args) : "已执行：" + describe(tool, args));
        return out;
    }

    // ---------------------------------------------------------------- 工具白名单

    /** 全部可被调度的工具。不在表内的意图一律降级为 chat，杜绝模型幻觉出不存在的操作。 */
    private static final List<String> TOOLS = List.of(
            "query_risk", "query_customs", "plan_routes", "run_analysis", "chat",
            "inject_scenario", "set_rainfall", "block_water", "clear_water", "reset_sandbox");

    /** 只读工具：查风险、查口岸、规划路线、决策分析、纯问答。 */
    public static boolean isReadOnly(String tool) {
        return "query_risk".equals(tool) || "query_customs".equals(tool)
                || "plan_routes".equals(tool) || "run_analysis".equals(tool) || "chat".equals(tool);
    }

    public static boolean isKnown(String tool) {
        return tool != null && TOOLS.contains(tool);
    }

    /** 执行工具：只读返回结构化数据，写操作落到沙盘/口岸引擎。异常不外抛，回一句说明。 */
    private Map<String, Object> safeExecute(String tool, Map<String, Object> args,
                                            String originId, String destinationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String o = (originId == null || originId.isBlank()) ? "NN" : originId;
            String d = (destinationId == null || destinationId.isBlank()) ? "HN" : destinationId;
            switch (tool) {
                case "query_risk" -> {
                    Map<String, Object> st = sandbox.state();
                    result.put("state", st);
                    result.put("summary", riskSummary(st));
                }
                case "query_customs" -> {
                    List<Map<String, Object>> ports = customsService.listAll();
                    result.put("customs", ports);
                    result.put("summary", "已取回 " + ports.size() + " 个口岸的通关时效。");
                }
                case "plan_routes" -> {
                    List<Map<String, Object>> cands = routeService.planCandidates(o, d, null, null);
                    result.put("candidates", cands);
                    result.put("summary", planSummary(cands));
                }
                case "run_analysis" -> {
                    Map<String, Object> decision = orchestrator.decide(o, d, null, null);
                    result.put("analysis", decision);
                    result.put("summary", "已完成三智能体决策分析（风险研判→方案生成→触达）。");
                }
                case "inject_scenario" -> {
                    String scenario = str(args.get("scenario"), "rain");
                    result.putAll(sandbox.pushScripted(scenario));
                }
                case "set_rainfall" -> {
                    Double ygg = num(args.get("yggMm"));
                    Double mc = num(args.get("mcMm"));
                    result.putAll(sandbox.setRainfall(ygg, mc));
                }
                case "block_water" -> result.putAll(sandbox.setWaterConditions(900.0, 1.8, 0.8));
                case "clear_water" -> result.putAll(sandbox.setWaterConditions(1200.0, 1.8, 0.8));
                case "reset_sandbox" -> result.putAll(sandbox.reset());
                default -> result.put("summary", "该指令无需执行动作。");
            }
            result.putIfAbsent("tool", tool);
        } catch (Exception e) {
            log.warn("调度舱工具执行失败 tool={}", tool, e);
            result.put("error", e.getMessage());
            result.put("summary", "执行失败：" + e.getClass().getSimpleName());
        }
        return result;
    }

    // ---------------------------------------------------------------- 规则兜底解析

    /** 关键词规则解析：模型不可用时的确定性兜底，保证离线演示仍能听懂常用指令。 */
    Intent ruleParse(String msg) {
        String m = msg.toLowerCase();
        Map<String, Object> args = new LinkedHashMap<>();
        // 写操作优先匹配（更具体）
        if (contains(msg, "重置", "撤销", "清空", "恢复原状", "reset")) {
            return new Intent("reset_sandbox", args, "已识别：重置沙盘（需确认）。", "rule");
        }
        if (contains(msg, "禁航", "封航", "停航")) {
            return new Intent("block_water", args, "已识别：模拟运河禁航（需确认）。", "rule");
        }
        if (contains(msg, "恢复通航", "解禁", "通航", "复航")) {
            return new Intent("clear_water", args, "已识别：恢复运河通航（需确认）。", "rule");
        }
        if (contains(msg, "台风")) {
            args.put("scenario", "typhoon");
            return new Intent("inject_scenario", args, "已识别：注入台风场景（需确认）。", "rule");
        }
        if (contains(msg, "双", "叠加", "复合", "both")) {
            args.put("scenario", "both");
            return new Intent("inject_scenario", args, "已识别：注入双风险场景（需确认）。", "rule");
        }
        if (contains(msg, "暴雨", "大雨", "强降雨")) {
            args.put("scenario", "rain");
            return new Intent("inject_scenario", args, "已识别：注入暴雨场景（需确认）。", "rule");
        }
        if (m.contains("mm") || m.contains("毫米") || contains(msg, "降雨", "雨量")) {
            Double ygg = extractNumber(msg, "友谊关");
            Double mc = extractNumber(msg, "芒街");
            if (ygg != null) {
                args.put("yggMm", ygg);
            }
            if (mc != null) {
                args.put("mcMm", mc);
            }
            if (ygg != null || mc != null) {
                return new Intent("set_rainfall", args, "已识别：调整断面降雨量（需确认）。", "rule");
            }
        }
        // 只读查询
        if (contains(msg, "口岸", "通关", "拥堵", "时效")) {
            return new Intent("query_customs", args, "正在查询各口岸通关时效。", "rule");
        }
        if (contains(msg, "分析", "决策", "方案", "智能体", "建议")) {
            return new Intent("run_analysis", args, "正在触发三智能体决策分析。", "rule");
        }
        if (contains(msg, "路线", "规划", "绕行", "候选", "走哪", "路径")) {
            return new Intent("plan_routes", args, "正在规划候选路线。", "rule");
        }
        if (contains(msg, "风险", "状态", "友谊关", "芒街", "熔断", "怎样", "如何", "情况")) {
            return new Intent("query_risk", args, "正在查询当前气象风险。", "rule");
        }
        return new Intent("chat", args,
                "我没听懂这条指令。你可以说：查友谊关风险 / 规划南宁到河内的路线 / 打开暴雨场景演练 / 模拟运河禁航。",
                "rule");
    }

    // ---------------------------------------------------------------- 预览文案

    /** 把一次工具调用翻成一句人话，写操作用于确认前的预览。 */
    public String describe(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "query_risk" -> "查询当前气象风险与口岸熔断状态";
            case "query_customs" -> "查询各口岸通关时效";
            case "plan_routes" -> "规划多条候选路线并预测灾害概率";
            case "run_analysis" -> "触发三智能体决策分析";
            case "inject_scenario" -> "注入演练场景：" + scenarioName(str(args.get("scenario"), "rain"));
            case "set_rainfall" -> "调整断面降雨量：友谊关 " + args.getOrDefault("yggMm", "—")
                    + "mm、芒街 " + args.getOrDefault("mcMm", "—") + "mm";
            case "block_water" -> "模拟平陆运河禁航（能见度降至 900m 触发红线）";
            case "clear_water" -> "恢复平陆运河正常通航";
            case "reset_sandbox" -> "重置沙盘，撤销所有注入的风险";
            case "chat" -> "对话";
            default -> tool;
        };
    }

    private static String scenarioName(String s) {
        return switch (s) {
            case "typhoon" -> "台风";
            case "both" -> "暴雨+台风双风险叠加";
            default -> "暴雨";
        };
    }

    private String riskSummary(Map<String, Object> state) {
        Object fused = state == null ? null : state.get("fusedSections");
        if (fused instanceof List<?> l && !l.isEmpty()) {
            return "当前有 " + l.size() + " 个断面处于熔断状态。";
        }
        return "当前无断面熔断，路网通行正常。";
    }

    private String planSummary(List<Map<String, Object>> cands) {
        if (cands == null || cands.isEmpty()) {
            return "未找到可行路线。";
        }
        Map<String, Object> rec = cands.get(0);
        Object h = rec.get("hazardLevel");
        return "共 " + cands.size() + " 条候选，推荐线路灾害概率等级：" + (h == null ? "未知" : h);
    }

    // ---------------------------------------------------------------- 小工具

    private static Map<String, Object> parseArgs(JsonNode node) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v.isNumber()) {
                    args.put(e.getKey(), v.asDouble());
                } else if (v.isBoolean()) {
                    args.put(e.getKey(), v.asBoolean());
                } else if (v.isTextual()) {
                    args.put(e.getKey(), v.asText());
                }
            });
        }
        return args;
    }

    private static boolean contains(String msg, String... keys) {
        for (String k : keys) {
            if (msg.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 从"友谊关90mm"这类句子里抽取紧跟某地名之后的数字。 */
    private static Double extractNumber(String msg, String place) {
        int idx = msg.indexOf(place);
        if (idx < 0) {
            return null;
        }
        String tail = msg.substring(idx + place.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        try {
            return sb.length() > 0 ? Double.parseDouble(sb.toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "默认" : s;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
