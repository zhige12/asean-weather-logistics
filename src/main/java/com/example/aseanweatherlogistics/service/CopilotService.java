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
    /** 行业知识库：调度舱的 RAG 召回来处。为 null（单测直接构造）时知识问答降级为一句说明。 */
    private final KnowledgeBaseService knowledge;

    public CopilotService(DeepseekService deepseekService, DecisionSandboxService sandbox,
                          RouteService routeService, DecisionOrchestrator orchestrator,
                          CustomsEfficiencyService customsService, KnowledgeBaseService knowledge) {
        this.deepseekService = deepseekService;
        this.sandbox = sandbox;
        this.routeService = routeService;
        this.orchestrator = orchestrator;
        this.customsService = customsService;
        this.knowledge = knowledge;
    }

    /**
     * 一次解析结果：命中哪个工具、参数、要回给用户的话、解析来源，以及本次模型调用的开销。
     * 后三个字段只在走了大模型时有值（规则兜底没花 token），四参构造器给规则路径用。
     */
    public record Intent(String tool, Map<String, Object> args, String say, String channel,
                         long elapsedMs, long promptTokens, long completionTokens) {
        public Intent(String tool, Map<String, Object> args, String say, String channel) {
            this(tool, args, say, channel, 0, 0, 0);
        }

        /** 是否真走了大模型（决定前端要不要显示 token/耗时）。 */
        public boolean modelBacked() {
            return elapsedMs > 0 || tokens() > 0;
        }

        public long tokens() {
            return promptTokens + completionTokens;
        }
    }

    /** 意图解析系统提示词：只让模型选工具 + 填参数 + 出一句回话，输出严格 JSON。 */
    private static final String INTENT_SYSTEM = """
            你是跨境物流调度台的指令解析器。根据调度员的一句话，从固定工具集中选一个并抽取参数。
            只做意图识别，不做安全判断、不编造任何数字。只输出一个 JSON 对象，不要多余文字。
            工具集（tool 取值必须是其一）：
            - query_risk   查询当前气象风险/口岸熔断状态（无参数）
            - query_customs 查询各口岸通关时效（无参数）
            - query_knowledge 回答行业知识/法规/操作规范类问题（通关单证、冷链温区、检疫、限速、
              区域气候特点等系统里没有数值答案的问题），args:{"topic":"问题原文或关键词"}
              注：本工具的答案只从知识库检索并改写，不产生任何时效/成本数字
            - plan_routes  规划多条候选路线（无参数，起终点用上下文给定的）
            - navigate_to  司机/用户要导航前往某地，args:{"destination":"目的地地名"}，如“导航去河内”
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
                return new Intent(tool, args, say, reply.tag(),
                        reply.elapsedMs(), reply.promptTokens(), reply.completionTokens());
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
        // 本次调用的开销随回复一起下发：「推理速度、成本」要能在屏幕上当场看到，
        // 而不是只沉在 /api/ai/metrics 里等人去请求。
        if (intent.modelBacked()) {
            Map<String, Object> cost = new LinkedHashMap<>();
            cost.put("elapsedMs", intent.elapsedMs());
            cost.put("promptTokens", intent.promptTokens());
            cost.put("completionTokens", intent.completionTokens());
            cost.put("tokens", intent.tokens());
            out.put("cost", cost);
        }
        boolean readOnly = isReadOnly(intent.tool());
        out.put("readOnly", readOnly);
        if (readOnly) {
            // chat 工具无实际动作，直接回话；其余只读工具执行并回数据
            if (!"chat".equals(intent.tool())) {
                out.put("data", safeExecute(intent.tool(), withTopic(intent, message), originId, destinationId));
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

    /**
     * 知识库问答没拿到 topic 时，用用户原句兼当检索词：大模型常常把 topic 写成关键词，
     * 而规则路径与部分口语提问根本不会拆词，拿原句去分词反而召回更稳。
     */
    private static Map<String, Object> withTopic(Intent intent, String message) {
        Map<String, Object> args = intent.args();
        if (!"query_knowledge".equals(intent.tool())) {
            return args;
        }
        String topic = str(args.get("topic"), "");
        if (!topic.isBlank()) {
            return args;
        }
        Map<String, Object> patched = new LinkedHashMap<>(args);
        patched.put("topic", message == null ? "" : message.trim());
        return patched;
    }

    // ---------------------------------------------------------------- 工具白名单

    /** 全部可被调度的工具。不在表内的意图一律降级为 chat，杜绝模型幻觉出不存在的操作。 */
    private static final List<String> TOOLS = List.of(
            "query_risk", "query_customs", "query_knowledge", "plan_routes", "navigate_to",
            "run_analysis", "chat",
            "inject_scenario", "set_rainfall", "block_water", "clear_water", "reset_sandbox");

    /** 只读工具：查风险、查口岸、查知识库、规划路线、决策分析、纯问答。 */
    public static boolean isReadOnly(String tool) {
        return "query_risk".equals(tool) || "query_customs".equals(tool)
                || "query_knowledge".equals(tool)
                || "plan_routes".equals(tool) || "navigate_to".equals(tool)
                || "run_analysis".equals(tool) || "chat".equals(tool);
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
                case "query_knowledge" -> result.putAll(answerKnowledge(str(args.get("topic"), "")));
                case "plan_routes" -> {
                    List<Map<String, Object>> cands = routeService.planCandidates(o, d, null, null);
                    result.put("candidates", cands);
                    result.put("summary", planSummary(cands));
                }
                case "navigate_to" -> {
                    // 导航是端侧动作：后端只回抽取到的目的地，真正的进入路线规划由前端执行
                    String dest = str(args.get("destination"), "");
                    result.put("destination", dest);
                    result.put("summary", dest.isBlank()
                            ? "请告诉我要去哪里，例如“导航去河内”。"
                            : "已识别目的地：" + dest + "，正在为你进入路线规划。");
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

    // ---------------------------------------------------------------- 知识库问答（RAG）

    /**
     * 检索增强作答的系统提示词。这里的硬约束是「不得超出资料做推断」：
     * 知识条目写的是行业惯例与参考区间，一旦模型把它们当成本系统的实时结果说出来，
     * 就变成幻觉；所以宁可回答“资料不足”，也不能编一个看上去正确的时限/温度。
     */
    private static final String KNOWLEDGE_SYSTEM = """
            你是跨境物流行业的问答助手。下面给出从知识库检索到的条目，只能依据这些条目回答。
            条目里没有的数字、时限、温度、政策结论一律不得编造或推测；资料不够时直接说明资料不足。
            不要复述全段资料，用 120 字以内中文概括要点；末尾用（依据：KB-xxx, KB-yyy）标出实际引用的条目编号。
            资料：
            """;

    /** 检索知识条目→（有模型时）基于资料改写作答；模型不可用时直接给原文并标为离线检索。 */
    private Map<String, Object> answerKnowledge(String topic) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tool", "query_knowledge");
        r.put("topic", topic);
        List<Map<String, Object>> sources = new ArrayList<>();
        r.put("sources", sources);
        if (knowledge == null || topic == null || topic.isBlank()) {
            r.put("summary", "行业知识库当前不可用。");
            return r;
        }
        StringBuilder ctx = new StringBuilder();
        for (KnowledgeBaseService.Match m : knowledge.searchDetailed(topic, KNOWLEDGE_TOP_N)) {
            KnowledgeEntry e = m.entry();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", e.getId());
            s.put("title", e.getTitle());
            s.put("category", e.getCategory());
            s.put("score", m.score());
            sources.add(s);
            ctx.append('[').append(e.getId()).append("] ").append(safeText(e.getTitle())).append('\n')
                    .append(safeText(e.getContent())).append('\n');
        }
        r.put("retrieved", sources.size());
        if (sources.isEmpty()) {
            // 召不回就承认召不回：绝不让模型凭常识顶上，也不把无关条目当成答案
            r.put("summary", "知识库里没有检索到与「" + topic + "」相关的条目，不作答。");
            return r;
        }
        if (deepseekService != null) {
            try {
                DeepseekService.AiReply reply =
                        deepseekService.chatWithChannel(KNOWLEDGE_SYSTEM + ctx, "问题：" + topic, 400);
                if (reply.text() != null && !reply.text().isBlank()) {
                    r.put("summary", reply.text().trim());
                    r.put("answerChannel", reply.tag());
                    Map<String, Object> cost = new LinkedHashMap<>();
                    cost.put("elapsedMs", reply.elapsedMs());
                    cost.put("promptTokens", reply.promptTokens());
                    cost.put("completionTokens", reply.completionTokens());
                    cost.put("tokens", reply.tokens());
                    r.put("answerCost", cost);
                    return r;
                }
            } catch (Exception e) {
                log.info("知识库改写失败，降级为直接引用原文：{}", e.toString());
            }
        }
        // 离线降级：不伪装成模型输出，直接把检索到的原文交给用户，并明说是原文
        KnowledgeEntry top = knowledge.searchDetailed(topic, 1).get(0).entry();
        r.put("summary", "【离线检索，未经模型改写】" + safeText(top.getTitle()) + "：" + safeText(top.getContent()));
        r.put("answerChannel", "rule");
        return r;
    }

    // ---------------------------------------------------------------- 规则兜底解析

    /** 知识问答最多取几条：再多会把提示词撑大、并把不相关的弱命中一起喂给模型。 */
    private static final int KNOWLEDGE_TOP_N = 3;

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
        // 「双」字单独命中太宽（双方/双重/双手都会误注入双风险），必须是明确的叠加说法
        if (contains(msg, "双风险", "双重风险", "两种风险", "叠加", "复合", "都注入", "both")) {
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
        // 行业知识问答：系统里没有数值答案的那类问题（单证/检疫/温区/限速/区域气候）。
        // 只认多字的具体标记，不收「通关」「时效」这类裸词，否则会把“各口岸通关时效”这种
        // 实时数据查询抢走（那条有引擎给的数字，不应去知识库里拿一个参考区间）。
        if (contains(msg, "单证", "报关", "检疫", "原产地", "关税", "法规", "规定", "规范", "合规",
                "冷链", "温控", "温区", "冷害", "限速", "限行", "危化品", "危险品",
                "互市", "疏港", "堆场", "换轨", "米轨", "吃水", "枯水", "雨季", "旱季", "季风", "气候",
                "内涝", "涉水", "爆胎", "货损", "理赔", "时区", "手续", "材料", "流程", "注意事项",
                "怎么办", "准入", "保险", "货值")
                // 英文缩写走小写后的句子匹配：司机说的是“Form E / TIR”，而 contains 比的是全小写 key
                || m.contains("tir") || m.contains("rcep") || m.contains("form e")) {
            args.put("topic", msg.trim());
            return new Intent("query_knowledge", args, "正在查行业知识库。", "rule");
        }
        if (contains(msg, "口岸", "通关", "拥堵", "时效")) {
            return new Intent("query_customs", args, "正在查询各口岸通关时效。", "rule");
        }
        if (contains(msg, "分析", "决策", "方案", "智能体", "建议")) {
            return new Intent("run_analysis", args, "正在触发三智能体决策分析。", "rule");
        }
        if (contains(msg, "路线", "规划", "绕行", "候选", "走哪", "路径")) {
            return new Intent("plan_routes", args, "正在规划候选路线。", "rule");
        }
        // 导航意图：司机/用户说“导航去X/我要去X/前往X”，抽取目的地，交由前端进入路线规划。
        // 放在 plan_routes 之后，保证“规划去河内的候选路线”这类仍走多候选规划，只有纯导航口令才升级为 navigate_to。
        String navDest = extractNavDestination(msg);
        if (navDest != null) {
            args.put("destination", navDest);
            return new Intent("navigate_to", args, "好的，正在为你规划去" + navDest + "的路线。", "rule");
        }
        // 只读查询。强风险词直接命中；「天气/气象」这类中性词必须再配一个疑问或查询标记——
        // 规则解析是离线兜底，宁可降级成 chat 让模型去接，也不能把「今天天气不错啊」听成一条查风险指令。
        // 「怎么样」不含连续子串「怎样」（中间隔着“么”），两个都得列，否则最高频的问法落空
        boolean weatherAsk = contains(msg, "天气", "气象", "下雨", "降雨", "雨量", "风力", "能见度")
                && contains(msg, "查", "看", "现在", "当前", "怎样", "怎么样", "如何", "情况", "多少",
                        "有没有", "会不会", "吗", "呢", "?", "？", "预报", "报告", "通报");
        if (contains(msg, "风险", "熔断", "状态", "友谊关", "芒街", "路况", "通行") || weatherAsk) {
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
            case "query_knowledge" -> "检索行业知识库：" + str(args.get("topic"), "相关问题");
            case "plan_routes" -> "规划多条候选路线并预测灾害概率";
            case "navigate_to" -> "导航前往 " + str(args.get("destination"), "目的地");
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
        // 旧实现读 state.get("fusedSections")，但沙盘 state() 里根本没有这个键（只有 sections），
        // 于是永远走「无熔断、路网通行正常」这条——用户问「河内天气」却被回一句路网通不通，答非所问。
        // 改为逐断面报出实际气象读数（未来6h累计降雨 + 状态），把「天气」真正答出来，
        // 熔断与否只作为结尾结论，不再是唯一输出。
        if (state == null) {
            return "暂无气象风险数据。";
        }
        if (!(state.get("sections") instanceof List<?> sections) || sections.isEmpty()) {
            return "暂无断面气象数据。";
        }
        StringBuilder sb = new StringBuilder();
        int fused = 0;
        boolean anyData = false;
        for (Object o : sections) {
            if (!(o instanceof Map<?, ?> s)) {
                continue;
            }
            String name = str(s.get("name"), "");
            double mm = s.get("forecastMm") instanceof Number n ? n.doubleValue() : Double.NaN;
            String statusText = str(s.get("statusText"), "");
            boolean isFused = Boolean.TRUE.equals(s.get("fused"));
            if (isFused) {
                fused++;
            }
            // 「等待气象数据」是未推送的空态，不算有读数
            if (Double.isFinite(mm) && !"等待气象数据".equals(statusText)) {
                anyData = true;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(name);
            if (Double.isFinite(mm)) {
                sb.append(String.format(" 未来6h累计降雨 %.0fmm", mm));
            }
            if (!statusText.isBlank()) {
                // 用逗号而非括号包裹：statusText 自身可能带括号（如“可用（降雨偏高，保持关注）”），
                // 再套一层会变成“（可用（…））”的双括号，读起来别扭
                sb.append("，").append(statusText);
            }
        }
        if (!anyData) {
            return "尚未收到气象推送，暂无断面降雨数据。";
        }
        sb.append(fused > 0 ? String.format("。当前 %d 个断面熔断。", fused) : "。各断面通行正常。");
        return sb.toString();
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

    /**
     * 从“导航去河内 / 我要去谅山 / 前往芒街”这类句子里抽取目的地地名。
     * 无导航意图或抽不出像地名的片段时返回 null（交回上层继续走其它规则）。
     */
    private static String extractNavDestination(String msg) {
        // 长口令优先，避免“导航去”被短词“去”抢先截断
        String[] markers = {"导航去", "导航到", "导航至", "带我去", "送我去", "我要去", "我要到",
                "我想去", "想去", "要去", "前往", "怎么去", "怎么走", "如何到达", "导航"};
        for (String mk : markers) {
            String dest = cutAfter(msg, mk);
            if (looksLikePlace(dest)) {
                return dest;
            }
        }
        // 裸“去”只在句首才算导航口令：“去河内”成立；
        // 而“去掉这个风险”“我想知道去不去”里的“去”是动词/虚词，抽出来的片段根本不是地名。
        String bare = msg.startsWith("去") ? cutAfter(msg, "去") : null;
        return looksLikePlace(bare) ? bare : null;
    }

    /** 取口令之后的片段，遇到“的/，/路线/导航/吗”等边界词就地截断。 */
    private static String cutAfter(String msg, String marker) {
        int idx = msg.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String dest = msg.substring(idx + marker.length());
        String[] stops = {"的", "，", ",", "。", " ", "路线", "导航", "规划", "吗", "呢", "?", "？"};
        for (String s : stops) {
            int c = dest.indexOf(s);
            if (c >= 0) {
                dest = dest.substring(0, c);
            }
        }
        return dest.trim();
    }

    /**
     * 抽出来的片段是否像个地名：2~12 字、不含数字与降雨单位，且不含疑问/叙述碎片词。
     * 没有这一层，口令表越全误判越多（“我想知道河内的天气”会切出“知道河内”当目的地）。
     */
    private static boolean looksLikePlace(String dest) {
        if (dest == null || dest.length() < 2 || dest.length() > 12) {
            return false;
        }
        if (dest.matches(".*[0-9].*") || dest.contains("mm") || dest.contains("毫米")) {
            return false;
        }
        String[] junk = {"知道", "什么", "怎", "样", "情况", "时候", "问题", "地方", "掉", "了", "过",
                "吗", "呢", "啊", "吧", "看", "查", "说", "告诉", "帮忙", "是否", "不", "没", "想", "要"};
        for (String j : junk) {
            if (dest.contains(j)) {
                return false;
            }
        }
        return true;
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "默认" : s;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    /** 知识条目的文本字段：null 时返回空串，不能拿 {@link #nz} 顶（它会把“默认”填进正文）。 */
    private static String safeText(String s) {
        return s == null ? "" : s;
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
