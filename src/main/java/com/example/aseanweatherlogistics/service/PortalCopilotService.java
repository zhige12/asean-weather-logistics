package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 门户首页的「AI志宁 · 快速导航」：大屏调度舱的受限投影。
 * <p>
 * 能力边界是产品决策（用户原话）：首页 AI <b>只做两件事</b>——
 * <ul>
 *   <li><b>快速进入某个小功能</b>：「打开司机端」→ 跳 /driver.html；「看看水运」→ 切水运视图；
 *       「导航去河内」→ 打开司机端并直接进入路线规划（端侧动作，算路仍走司机端既有链路）。</li>
 *   <li><b>知识库问答</b>：复用大屏同一条 RAG 链路（{@link CopilotService#answerKnowledgeQuery}），出处照带。</li>
 * </ul>
 * 其余一切——场景模拟、禁航推演、降雨滑杆、重置沙盘，乃至查风险/决策分析这类要改动操作台状态的
 * 只读工具——都回「此功能正在学习中」并引导去调度大屏的 AI 助手。首页不执行、不确认、不落地。
 * <p>
 * 解析通道与大屏同一套纪律：本地 Ollama 优先 → 在线降级 → 规则兜底（离线可演示），
 * 通道如实标注；规则路径里「打开X」口令表是纯字面量匹配，绝不会把闲聊听成跳转。
 */
@Service
public class PortalCopilotService {

    private static final Logger log = LoggerFactory.getLogger(PortalCopilotService.class);

    private final DeepseekService deepseekService;
    private final CopilotService copilotService;

    public PortalCopilotService(DeepseekService deepseekService, CopilotService copilotService) {
        this.deepseekService = deepseekService;
        this.copilotService = copilotService;
    }

    /** 门户可打开的目标 → 跳转地址。与 HomeApp 的入口一致（前端也会校验这张地址表）。 */
    private static final Map<String, String> TARGET_URL = Map.of(
            "home", "/",
            "dashboard", "/dashboard.html",
            "driver", "/driver.html",
            "weather", "#view=weather",
            "amri", "#view=amri",
            "waterway", "#view=waterway",
            "customs", "#view=customs");

    /** 大模型意图解析的系统提示词：工具集刻意只有三个 + chat，越界意图模型压根看不见，比事后拦截稳。 */
    private static final String INTENT_SYSTEM = """
            你是任务门户的导航助手解析器。用户的一句话只可能是以下几类之一，选一个工具并抽取参数。
            只做意图识别，不编造任何数字，只输出一个 JSON 对象，不要多余文字。
            工具集（tool 取值必须是其一）：
            - open_target    用户想打开/进入门户里的某个页面，args:{"target":"home|dashboard|driver|weather|amri|waterway|customs"}
              （home=门户首页，dashboard=调度大屏，driver=司机端，weather=实时气象，amri=AMRI风险，waterway=水运，customs=通关时效）
            - navigate_to    用户要导航前往某个地方（地图上的城市/口岸/港口，不是门户页面），args:{"destination":"目的地地名"}，如“导航去河内”
            - query_knowledge 行业知识/法规/操作规范类问题（通关单证、冷链温区、检疫、限速、区域气候等），args:{"topic":"问题原文或关键词"}
            - chat           以上都不匹配的普通问答，把回答写在 say 字段
            输出形如：{"tool":"open_target","args":{"target":"driver"},"say":"好的，正在打开司机端"}
            say 是一句简短中文确认；不确定就用 chat。
            """;

    /** 门户越界提示：用户点名要的文案口径——不执行、不装会，只说在学习，并指路大屏 AI。 */
    private static final String LEARNING_SAY = "此功能正在学习中——场景模拟、禁航推演、沙盘重置这类调度操作，"
            + "请进入调度大屏，由那边的「AI志宁」助手执行。";

    /**
     * 推演类意图的强信号词：句子带这些词时，无论模型把 tool 判成什么都不允许直接执行——
     * 实测过模型会把「打开暴雨模拟」判成 open_target(weather)（拿「暴雨」关联到气象页），
     * 这是幻觉不是理解；而模型自己回「无法模拟」也不够，必须给统一的学习中口径。
     * 词表只收多字具体词（模拟/演练/禁航/注入/重置/推演/沙盘…），不收裸「雨」这类会误伤知识提问的单字。
     */
    private static final List<String> OUT_OF_SCOPE_WORDS = List.of(
            "模拟", "演练", "禁航", "封航", "注入", "重置", "推演", "沙盘", "熔断", "解禁", "复航",
            "滑杆", "场景", "降雨调", "调到", "调高", "调低");

    /** 操作类动词：与推演主题词双信号同时命中才算越界指令，
     * 「暴雨场景下货损怎么理赔」这种带「场景」的知识提问不得被误拦。 */
    private static final List<String> ACTION_WORDS = List.of(
            "打开", "开启", "启动", "模拟", "注入", "重置", "恢复", "切换", "调到", "调高", "调低", "执行", "运行", "来一个", "来一场", "安排");

    /** 大屏专属工具的名单（模型把这些判成合法 tool 时也照样拦）。 */
    private static final List<String> SCREEN_ONLY_TOOLS = List.of(
            "query_risk", "query_customs", "plan_routes", "run_analysis",
            "inject_scenario", "set_rainfall", "block_water", "clear_water", "reset_sandbox");

    private static boolean outOfScope(String msg) {
        String m = msg == null ? "" : msg;
        boolean topic = false;
        for (String w : OUT_OF_SCOPE_WORDS) {
            if (m.contains(w)) {
                topic = true;
                break;
            }
        }
        if (!topic) {
            return false;
        }
        // 纯主题词不算指令（「运河禁航规定是什么」是知识提问）；必须另带操作动词
        for (String w : ACTION_WORDS) {
            if (m.contains(w)) {
                return true;
            }
        }
        // 但「禁航/封航」本身在中文里兼作动词，无操作动词时再排掉提问句式（规定/是什么/为什么/吗/如何…）：
        // 「运河禁航规定是什么」是知识提问，「禁航」裸口令才按指令拦下
        if (m.contains("规定") || m.contains("法规") || m.contains("是什么") || m.contains("为什么") || m.contains("怎么")
                || m.contains("如何") || m.contains("怎样") || m.contains("怎么样") || m.contains("吗")
                || m.contains("呢") || m.contains("？") || m.contains("?")) {
            return false;
        }
        return m.contains("禁航") || m.contains("封航");
    }

    /** 对话主入口：解析 → 分流为 open / navigate / knowledge / say 四类动作。 */
    public Map<String, Object> chat(String message) {
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            return wrap("say", Map.of(), "想看哪个功能？可以说：打开司机端 / 导航去河内 / 冷链货物要什么单证。", "rule");
        }
        // 1) 大模型解析（本地优先→在线降级；失败落规则）
        try {
            DeepseekService.AiReply reply = deepseekService.chatWithChannel(INTENT_SYSTEM, "用户说：" + msg, 200);
            JsonNode node = AgentJson.extractObject(reply.text());
            String tool = AgentJson.text(node, "tool");
            // 越界词前置拦截：原句带推演强信号词，或模型判出大屏专属工具名，都直接回学习中——
            // 不给幻觉把「打开暴雨模拟」包装成 open_target 的机会（实测发生过）。
            if (outOfScope(msg) || (tool != null && SCREEN_ONLY_TOOLS.contains(tool))) {
                return wrap("say", Map.of(), LEARNING_SAY, reply.tag(), reply);
            }
            Map<String, Object> out = route(msg, tool, argsOf(node), reply.tag(), reply);
            // 模型判 chat、但规则对同一句能判出具体动作（打开X/导航去X）时以规则为准：
            // 模型对明确口令回「没听懂」是漏判不是理解，而规则的口令表是字面量匹配、不会幻觉。
            if (out != null && !"chat".equals(tool)) {
                return out;
            }
            if (out != null) {
                String[] probe = ruleRoute(msg);
                if ("open_target".equals(probe[0]) || "navigate_to".equals(probe[0])) {
                    Map<String, Object> strictArgs = new LinkedHashMap<>();
                    strictArgs.put("open_target".equals(probe[0]) ? "target" : "destination", probe[1]);
                    Map<String, Object> byRule = route(msg, probe[0], strictArgs, "rule", null);
                    if (byRule != null) {
                        return byRule;
                    }
                }
                return out;
            }
        } catch (Exception e) {
            log.info("门户 AI 大模型解析失败，转规则兜底：{}", e.toString());
        }
        // 2) 规则兜底（离线也能用）
        String[] rule = ruleRoute(msg);
        Map<String, Object> ruleArgs = new LinkedHashMap<>();
        if ("open_target".equals(rule[0])) {
            ruleArgs.put("target", rule[1]);
        } else if ("navigate_to".equals(rule[0])) {
            ruleArgs.put("destination", rule[1]);
        } else if ("query_knowledge".equals(rule[0])) {
            ruleArgs.put("topic", rule[1]);
        }
        Map<String, Object> byRule = route(msg, rule[0], ruleArgs, "rule", null);
        if (byRule != null) {
            return byRule;
        }
        return learnFrom(rule[0], msg);
    }

    /**
     * 规则兜底解析：抽出一个 [工具, 参数原文] 二元组。
     * 先过门户自己的「打开X」口令表（字面量匹配、要求带进入类动词），再借用大屏
     * CopilotService 已固化测试的导航/知识抽取——同一条逻辑，不各写一份。
     */
    private String[] ruleRoute(String msg) {
        String target = openTargetByRule(msg);
        if (target != null) {
            return new String[]{"open_target", target};
        }
        // 大屏规则把「暴雨/禁航/熔断」等词当写操作指令前置匹配（在那边是对的，用户就是在下命令）；
        // 门户里同一批词更多出现在知识提问里（「暴雨场景下货损怎么理赔」「运河禁航的规定是什么」），
        // 所以提问句式必须先于大屏规则归到知识问答，否则会被 learnFrom 误判越界回「学习中」。
        // 判据是双信号的镜像：带疑问标记+知识信号词、且不带操作动词——「打开暴雨模拟」这类命令不受影响。
        if (isKnowledgeQuestion(msg)) {
            return new String[]{"query_knowledge", msg.trim()};
        }
        CopilotService.Intent it = copilotService.ruleParse(msg);
        // 知识问答不得被页面词表抢走：「运河枯水期流程」含「运河」但显然是提问，
        // 只有大屏规则判出 query_knowledge 以外的意图才继续往导航口令归。
        if ("query_knowledge".equals(it.tool()) || "chat".equals(it.tool())) {
            return new String[]{it.tool(), String.valueOf(it.args().getOrDefault("topic", msg))};
        }
        return new String[]{it.tool(), String.valueOf(it.args().getOrDefault(
                "destination", it.args().getOrDefault("topic", "")))};
    }

    /** 知识提问识别：疑问标记 × 知识信号词同句命中，且不含操作动词（详见 ruleRoute 注释）。 */
    private static boolean isKnowledgeQuestion(String msg) {
        String m = msg == null ? "" : msg;
        boolean question = m.contains("怎么") || m.contains("如何") || m.contains("怎样") || m.contains("是什么")
                || m.contains("为什么") || m.contains("多少") || m.contains("哪些") || m.contains("标准")
                || m.contains("吗") || m.contains("呢") || m.contains("？") || m.contains("?");
        if (!question) {
            return false;
        }
        for (String w : ACTION_WORDS) {
            if (m.contains(w)) {
                return false; // 带操作动词按指令走既有拦截/口令路径，不在这里翻案
            }
        }
        return containsAny(m, "理赔", "货损", "规定", "法规", "规范", "标准", "流程", "手续", "单证", "报关",
                "检疫", "关税", "冷链", "温区", "限速", "限行", "危化品", "枯水", "雨季", "气候", "判定", "材料");
    }

    /**
     * 「打开/进入/切换到 + 页面名」的字面量匹配：动词与页面名缺一不发，
     * 「司机端拥堵吗」「今天天气怎么样」这类提问不会被听成跳转指令。
     */
    private static String openTargetByRule(String msg) {
        String m = msg.toLowerCase();
        boolean wantsOpen = containsAny(m, "打开", "进入", "切换", "跳转", "跳到", "看看", "前往");
        if (!wantsOpen) {
            return null;
        }
        // 页面词表按最具体先匹配，避免「大屏」抢了「调度大屏」
        if (containsAny(m, "司机端", "司机界面", "driver")) {
            return "driver";
        }
        if (containsAny(m, "调度大屏", "大屏", "操作台", "dashboard")) {
            return "dashboard";
        }
        if (containsAny(m, "通关", "口岸时效", "海关公告", "customs")) {
            return "customs";
        }
        if (containsAny(m, "水运", "运河", "waterway")) {
            return "waterway";
        }
        if (containsAny(m, "amri", "风险指数", "风险模型")) {
            return "amri";
        }
        if (containsAny(m, "气象", "天气实况", "weather")) {
            return "weather";
        }
        if (containsAny(m, "门户", "首页", "home")) {
            return "home";
        }
        return null; // 没命中任何页面词，交给下面的大屏规则（导航/知识/其余）
    }

    /** 把 (tool, args) 分流成前端可执行的四类动作；判不进任何类时返回 null 交给上层继续处理。 */
    private Map<String, Object> route(String msg, String tool, Map<String, Object> args,
                                      String channel, DeepseekService.AiReply reply) {
        if (tool == null) {
            return null;
        }
        switch (tool) {
            case "open_target" -> {
                String target = String.valueOf(args.getOrDefault("target", ""));
                String url = TARGET_URL.get(target);
                if (url == null) {
                    return wrap("say", Map.of(), "门户里没有叫「" + target + "」的页面，可打开的有："
                            + "调度大屏 / 司机端 / 实时气象 / AMRI 风险 / 水运 / 通关时效。", channel, reply);
                }
                return wrap("open", Map.of("target", target, "url", url),
                        blankTo(args.get("say"), "好的，正在打开" + targetLabel(target) + "。"), channel, reply);
            }
            case "navigate_to" -> {
                String dest = blankTo(args.get("destination"), "");
                if (dest.isEmpty()) {
                    // 模型有时把地名只写在 say 里：抽取失败宁可追问，不拿整句当目的地
                    return wrap("say", Map.of(), "要导航去哪里？说一个地名即可，例如「导航去河内」。", channel, reply);
                }
                return wrap("navigate", Map.of("destination", dest, "url", "/driver.html?nav=" + dest),
                        "已识别目的地：" + dest + "，正在打开司机端进入路线规划。", channel, reply);
            }
            case "query_knowledge" -> {
                String topic = blankTo(args.get("topic"), msg);
                Map<String, Object> data = new LinkedHashMap<>(copilotService.answerKnowledgeQuery(topic));
                String summary = String.valueOf(data.getOrDefault("summary", ""));
                return wrap("knowledge", Map.of(), summary, channel, reply, data);
            }
            case "chat" -> {
                return wrap("say", Map.of(), blankTo(args.get("say"),
                        "我是门户导航助手，可以说：打开司机端 / 导航去河内 / 冷链货物要什么单证。"), channel, reply);
            }
            default -> {
                return null; // 大屏专属工具（query_risk/inject_scenario/block_water/run_analysis…）→ 越界
            }
        }
    }

    /** 规则路径判出的大屏工具：能对应到门户视图的引导过去（同一意图按原句词选最贴切视图），其余回「学习中」。 */
    private Map<String, Object> learnFrom(String tool, String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        String view = switch (tool) {
            case "query_customs" -> "customs";
            // 同一句 query_risk 兜底，提问里带「天气/气象/下雨」的先看实况表，风险指数页是其次的落点
            case "query_risk" -> (m.contains("天气") || m.contains("气象") || m.contains("下雨") || m.contains("降雨"))
                    ? "weather" : "amri";
            default -> null;
        };
        if (view != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("target", view);
            a.put("url", TARGET_URL.get(view));
            return wrap("open", a, "此功能正在学习中——首页助手只做快速导航与知识问答。"
                    + "相关数据可在「" + targetLabel(view) + "」视图只读查看：" + LEARNING_SAY, "rule");
        }
        return wrap("say", Map.of(), LEARNING_SAY, "rule");
    }

    private static String targetLabel(String target) {
        return switch (target) {
            case "home" -> "门户首页";
            case "dashboard" -> "调度大屏";
            case "driver" -> "司机端";
            case "weather" -> "实时气象";
            case "amri" -> "AMRI 风险";
            case "waterway" -> "水运";
            case "customs" -> "通关时效";
            default -> target;
        };
    }

    private Map<String, Object> wrap(String action, Map<String, Object> args, String say, String channel) {
        return wrap(action, args, say, channel, null, null);
    }

    private Map<String, Object> wrap(String action, Map<String, Object> args, String say, String channel,
                                     DeepseekService.AiReply reply) {
        return wrap(action, args, say, channel, reply, null);
    }

    /** 统一回包：动作 + 话术 + 通道溯源；走了大模型就带本次开销（与大屏同一口径）。 */
    private Map<String, Object> wrap(String action, Map<String, Object> args, String say, String channel,
                                     DeepseekService.AiReply reply, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("args", args);
        out.put("say", say);
        out.put("channel", channel);
        if (reply != null && (reply.elapsedMs() > 0 || reply.tokens() > 0)) {
            Map<String, Object> cost = new LinkedHashMap<>();
            cost.put("elapsedMs", reply.elapsedMs());
            cost.put("promptTokens", reply.promptTokens());
            cost.put("completionTokens", reply.completionTokens());
            cost.put("tokens", reply.tokens());
            out.put("cost", cost);
        }
        if (data != null) {
            out.put("data", data);
        }
        return out;
    }

    private static Map<String, Object> argsOf(JsonNode node) {
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode a = node == null ? null : node.get("args");
        if (a != null && a.isObject()) {
            a.fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().isTextual()
                    ? e.getValue().asText() : e.getValue().asDouble()));
        }
        // say 也在顶层：route 里要读它，一并带上（非字符串就忽略）
        if (node != null && node.hasNonNull("say") && node.get("say").isTextual()) {
            args.putIfAbsent("say", node.get("say").asText());
        }
        return args;
    }

    private static String blankTo(Object v, String fallback) {
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? fallback : s;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
