package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 多智能体决策编排器：
 * 前端一次请求 → 风险研判智能体 → 方案生成智能体 → 触达智能体，串行调用，
 * 前一个的输出作为后一个的输入。不引入外部框架，Spring Boot 内方法调用完成。
 * <p>
 * 编排职责：
 * <ul>
 *   <li>每个智能体开始/结束时通过 SSE（agent-status 事件）实时广播，
 *       大屏三个图标按真实推理进度依次亮起，而非假动画；</li>
 *   <li>预生成缓存：演示前用脚本按 scenarioId 跑好结果存成 JSON，
 *       演示时优先读缓存 2 秒出结果（拔网线 + 模型冷启动双保险）；</li>
 *   <li>汇总返回统一结构（agents / agentStatus / explanation / plans / outreachPreview）。</li>
 * </ul>
 */
@Service
public class DecisionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DecisionOrchestrator.class);

    private final RiskAgent riskAgent;
    private final SolutionAgent solutionAgent;
    private final TouchAgent touchAgent;
    private final DemoConfigService config;
    private final DecisionLogService decisionLog;
    private final RouteAgentService routeAgentService;
    private final DecisionSandboxService sandbox;
    private final TripSignalService tripSignal;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${decision.cache.dir:decision-cache}")
    private String cacheDir;

    public DecisionOrchestrator(RiskAgent riskAgent, SolutionAgent solutionAgent,
                                TouchAgent touchAgent, DemoConfigService config,
                                DecisionLogService decisionLog,
                                RouteAgentService routeAgentService,
                                DecisionSandboxService sandbox,
                                TripSignalService tripSignal) {
        this.riskAgent = riskAgent;
        this.solutionAgent = solutionAgent;
        this.touchAgent = touchAgent;
        this.config = config;
        this.decisionLog = decisionLog;
        this.routeAgentService = routeAgentService;
        this.sandbox = sandbox;
        this.tripSignal = tripSignal;
    }

    /**
     * 三智能体串行决策。
     *
     * @param scenarioId    非空时走预生成缓存（有缓存直接返回并标记 cacheHit）
     * @param progressRatio 司机当前行程进度 0~1；传 null 时自动读司机端周期上报的在途进度。
     *                      在途（进度有效）时不走预生成缓存：缓存里的推荐没考虑车的位置，
     *                      会把已开过装货港的车推荐回"陆水联运"。
     */
    public Map<String, Object> decide(String originId, String destinationId, String scenarioId,
                                      Double progressRatio) {
        String origin = (originId == null || originId.isBlank()) ? "NN" : originId;
        String dest = (destinationId == null || destinationId.isBlank()) ? "HN" : destinationId;
        double progress = progressRatio != null ? progressRatio : tripSignal.progress();
        boolean inTrip = progress >= 0;

        // ---------- 演示模式：优先读预生成缓存 ----------
        if (scenarioId != null && !scenarioId.isBlank() && !inTrip) {
            Map<String, Object> cached = loadCache(scenarioId);
            if (cached != null) {
                cached.put("cacheHit", true);
                // 旧版本写进缓存的 aiChannel 是一句写死的「本地大模型（Ollama）/ 远程通道
                // 自动切换」，那个字符串本身无法证明任何事。读到它时必须降为「未记录」，
                // 不能让历史缓存顶着一个无法核实的本地标签继续展示。
                Object legacy = cached.get("aiChannel");
                if (legacy == null || String.valueOf(legacy).contains("自动切换")) {
                    cached.put("aiChannel", "unrecorded");
                    cached.put("aiChannelLabel", "预生成文案（生成时未记录真实通道）");
                }
                decisionLog.log("AGENT", "多智能体决策分析（缓存）",
                        "场景 " + scenarioId + " 命中预生成缓存");
                return cached;
            }
        }

        long t0 = System.currentTimeMillis();
        boolean anyAi = false;

        // ---------- 🔵 风险研判智能体 ----------
        broadcastAgentStatus(RiskAgent.ID, RiskAgent.NAME, RiskAgent.ICON, "RUNNING", 0, null);
        long t1 = System.currentTimeMillis();
        RiskAgent.RiskResult risk = riskAgent.assess();
        long riskMs = System.currentTimeMillis() - t1;
        anyAi |= risk.aiPowered();
        broadcastAgentStatus(RiskAgent.ID, RiskAgent.NAME, RiskAgent.ICON, "DONE", riskMs,
                summarize(risk.explanation().get("boundary")));

        // ---------- 🟢 方案生成智能体 ----------
        broadcastAgentStatus(SolutionAgent.ID, SolutionAgent.NAME, SolutionAgent.ICON,
                "RUNNING", 0, null);
        long t2 = System.currentTimeMillis();
        SolutionAgent.SolutionResult solution = solutionAgent.generate(risk, origin, dest, progress);
        long solMs = System.currentTimeMillis() - t2;
        anyAi |= solution.aiPowered();
        broadcastAgentStatus(SolutionAgent.ID, SolutionAgent.NAME, SolutionAgent.ICON,
                "DONE", solMs, "推荐方案" + solution.recommendedId());
        decisionLog.log("AGENT", "方案生成",
                "进度=" + (inTrip ? Math.round(progress * 100) + "%" : "不在途")
                        + "，推荐方案" + solution.recommendedId());

        // ---------- 🟡 触达智能体 ----------
        broadcastAgentStatus(TouchAgent.ID, TouchAgent.NAME, TouchAgent.ICON, "RUNNING", 0, null);
        long t3 = System.currentTimeMillis();
        TouchAgent.TouchResult touch = touchAgent.generate(solution);
        long touchMs = System.currentTimeMillis() - t3;
        anyAi |= touch.aiPowered();
        broadcastAgentStatus(TouchAgent.ID, TouchAgent.NAME, TouchAgent.ICON, "DONE", touchMs,
                ((Number) touch.preview().get("count")).intValue() + " 个触达角色就绪");

        // ---------- 汇总 ----------
        Map<String, Object> result = assemble(origin, dest, scenarioId,
                risk, solution, touch, riskMs, solMs, touchMs, anyAi);
        result.put("progressRatio", progress);

        // 在途结果带着车的位置信息，不能落进演示缓存（下次不在途命中缓存会拿着旧进度误导）
        if (scenarioId != null && !scenarioId.isBlank() && !inTrip) {
            saveCache(scenarioId, result);
        }
        decisionLog.log("AGENT", "多智能体决策分析",
                String.format("三智能体串行完成：研判%dms / 方案%dms / 触达%dms，推荐方案%s（%s）",
                        riskMs, solMs, touchMs, solution.recommendedId(),
                        anyAi ? "AI增强" : "规则模板"));
        log.info("orchestrator done in {}ms (ai={})", System.currentTimeMillis() - t0, anyAi);
        return result;
    }

    // ==================== 组装 ====================

    private Map<String, Object> assemble(String origin, String dest, String scenarioId,
                                         RiskAgent.RiskResult risk,
                                         SolutionAgent.SolutionResult solution,
                                         TouchAgent.TouchResult touch,
                                         long riskMs, long solMs, long touchMs, boolean anyAi) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", System.currentTimeMillis());
        result.put("originId", origin);
        result.put("destinationId", dest);
        result.put("cargo", config.cargo());
        result.put("cacheHit", false);
        result.put("aiPowered", anyAi);
        // 汇总口径取三个智能体里"最远程"的那一级：宁可标重，不可标轻。
        String worst = worstChannel(risk.aiChannel(), solution.aiChannel(), touch.aiChannel());
        result.put("aiChannel", worst == null ? "template" : worst);
        result.put("aiChannelLabel", channelLabel(worst));
        if (scenarioId != null && !scenarioId.isBlank()) {
            result.put("scenarioId", scenarioId);
        }

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentCard(RiskAgent.ID, RiskAgent.NAME, RiskAgent.ICON,
                "解析官方气象数据与地质元数据，RAG 检索历史相似案例，输出熔断决策解释",
                risk.explanation(), riskMs, risk.aiPowered(), risk.aiChannel()));
        Map<String, Object> solDetail = new LinkedHashMap<>();
        solDetail.put("planCount", solution.plans().size());
        solDetail.put("recommended", solution.recommendedId());
        solDetail.put("recommendation", solution.recommendation());
        agents.add(agentCard(SolutionAgent.ID, SolutionAgent.NAME, SolutionAgent.ICON,
                "生成公路绕行 / 陆水联运 / 原地等待三个方案并量化对比",
                solDetail, solMs, solution.aiPowered(), solution.aiChannel()));
        agents.add(agentCard(TouchAgent.ID, TouchAgent.NAME, TouchAgent.ICON,
                "按平台配置为各角色生成专属执行指令（非选择题，是执行指令+权益保障）",
                touch.preview(), touchMs, touch.aiPowered(), touch.aiChannel()));
        result.put("agents", agents);

        // 前端"依次点亮"动画数据源（升级方案 §2.3）。按分析模式分口径：
        // 常态（刚开始导航/派单前）不能沿用熔断、改任务口吻的文案，
        // 否则车刚出发就看到"降雨需降至75mm才恢复通行/5个改任务指令就绪"这种误导信息
        boolean normalMode = !"reroute".equals(solution.mode());
        List<Map<String, Object>> agentStatus = new ArrayList<>();
        agentStatus.add(statusRow(RiskAgent.NAME, normalMode
                ? "沿线口岸无生效熔断，气象与通关状态正常，持续监控中"
                : summarize(risk.explanation().get("boundary"))));
        agentStatus.add(statusRow(SolutionAgent.NAME, normalMode
                ? solution.recommendation()
                : "推荐方案" + solution.recommendedId() + "：" + solution.recommendation()));
        agentStatus.add(statusRow(TouchAgent.NAME, normalMode
                ? "派单触达指令与权益保障已就绪，调度选定路线派单后推送司机端"
                : touch.preview().get("count") + " 个触达角色指令已生成"));
        result.put("agentStatus", agentStatus);

        result.put("explanation", risk.explanation());
        result.put("plans", solution.plans());
        result.put("recommendation", solution.recommendation());
        result.put("outreachPreview", touch.preview());
        // 分析模式：normal=常态整趟对比（绝对值）/ reroute=熔断绕行对比（增量）
        result.put("analysisMode", solution.mode());
        // 双向切换方向（road_to_water / water_to_road / dual_risk / normal）
        result.put("direction", sandbox.direction());
        result.put("roadBlocked", sandbox.roadBlocked());
        result.put("waterBlocked", sandbox.waterBlocked());
        result.put("waterBlockedReason", sandbox.waterBlockedReason());
        return result;
    }

    /**
     * 通道标记 -> 展示文案。标记格式见 {@link DeepseekService.AiReply#tag()}：
     * {@code local:<model>} / {@code online:<model>}，null 表示未用模型。
     */
    private static String channelLabel(String tag) {
        if (tag == null || tag.isBlank()) {
            return "规则模板";
        }
        int colon = tag.indexOf(':');
        String ch = colon > 0 ? tag.substring(0, colon) : tag;
        String model = colon > 0 ? tag.substring(colon + 1) : null;
        return switch (ch) {
            case DeepseekService.CHANNEL_LOCAL -> "本地大模型（Ollama" + (model == null ? "" : " " + model) + "）";
            case DeepseekService.CHANNEL_ONLINE -> "在线 API（" + (model == null ? "远程大模型" : model) + "）· 非本地";
            default -> tag;
        };
    }

    /**
     * 数据离本机的程度：local 不出门 < online 出公网。
     * 汇总时取最大，避免"两个本地 + 一个在线"被平均成一个看起来无害的标签。
     */
    private static int severity(String tag) {
        if (tag == null) {
            return -1;
        }
        if (tag.startsWith(DeepseekService.CHANNEL_LOCAL)) {
            return 1;
        }
        if (tag.startsWith(DeepseekService.CHANNEL_ONLINE)) {
            return 2;
        }
        return 0;
    }

    private static String worstChannel(String... tags) {
        String worst = null;
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            if (worst == null || severity(t) > severity(worst)) {
                worst = t;
            }
        }
        return worst;
    }

    private Map<String, Object> agentCard(String id, String name, String icon, String duty,
                                          Map<String, Object> detail, long elapsedMs,
                                          boolean aiPowered, String aiChannel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("duty", duty);
        m.put("status", "DONE");
        m.put("elapsedMs", elapsedMs);
        m.put("aiPowered", aiPowered);
        m.put("aiChannel", aiChannel == null ? "template" : aiChannel);
        m.put("aiChannelLabel", channelLabel(aiChannel));
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> statusRow(String name, String output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", "done");
        m.put("output", output == null ? "" : output);
        return m;
    }

    private String summarize(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // ==================== SSE 状态广播 ====================

    private void broadcastAgentStatus(String id, String name, String icon, String status,
                                      long elapsedMs, String output) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "agent-status");
            payload.put("agentId", id);
            payload.put("name", name);
            payload.put("icon", icon);
            payload.put("status", status);
            if (elapsedMs > 0) {
                payload.put("elapsedMs", elapsedMs);
            }
            if (output != null) {
                payload.put("output", output);
            }
            payload.put("ts", System.currentTimeMillis());
            routeAgentService.broadcastEvent("agent-status", payload);
        } catch (Exception e) {
            log.debug("agent-status broadcast failed: {}", e.toString());
        }
    }

    // ==================== 预生成缓存 ====================

    private Path cacheFile(String scenarioId) {
        String safe = scenarioId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return Path.of(cacheDir, "scenario_" + safe + ".json");
    }

    private Map<String, Object> loadCache(String scenarioId) {
        try {
            Path f = cacheFile(scenarioId);
            if (!Files.exists(f)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(Files.readString(f), Map.class);
            return new LinkedHashMap<>(m);
        } catch (Exception e) {
            log.warn("读取决策缓存 {} 失败：{}", scenarioId, e.toString());
            return null;
        }
    }

    private void saveCache(String scenarioId, Map<String, Object> result) {
        try {
            Path f = cacheFile(scenarioId);
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            log.info("决策缓存已保存：{}", f);
        } catch (Exception e) {
            log.warn("保存决策缓存 {} 失败：{}", scenarioId, e.toString());
        }
    }
}
