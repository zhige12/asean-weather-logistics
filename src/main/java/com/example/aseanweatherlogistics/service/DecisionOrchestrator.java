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
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${decision.cache.dir:decision-cache}")
    private String cacheDir;

    public DecisionOrchestrator(RiskAgent riskAgent, SolutionAgent solutionAgent,
                                TouchAgent touchAgent, DemoConfigService config,
                                DecisionLogService decisionLog,
                                RouteAgentService routeAgentService,
                                DecisionSandboxService sandbox) {
        this.riskAgent = riskAgent;
        this.solutionAgent = solutionAgent;
        this.touchAgent = touchAgent;
        this.config = config;
        this.decisionLog = decisionLog;
        this.routeAgentService = routeAgentService;
        this.sandbox = sandbox;
    }

    /**
     * 三智能体串行决策。
     *
     * @param scenarioId 非空时走预生成缓存（有缓存直接返回并标记 cacheHit）
     */
    public Map<String, Object> decide(String originId, String destinationId, String scenarioId) {
        String origin = (originId == null || originId.isBlank()) ? "NN" : originId;
        String dest = (destinationId == null || destinationId.isBlank()) ? "HN" : destinationId;

        // ---------- 演示模式：优先读预生成缓存 ----------
        if (scenarioId != null && !scenarioId.isBlank()) {
            Map<String, Object> cached = loadCache(scenarioId);
            if (cached != null) {
                cached.put("cacheHit", true);
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
        SolutionAgent.SolutionResult solution = solutionAgent.generate(risk, origin, dest);
        long solMs = System.currentTimeMillis() - t2;
        anyAi |= solution.aiPowered();
        broadcastAgentStatus(SolutionAgent.ID, SolutionAgent.NAME, SolutionAgent.ICON,
                "DONE", solMs, "推荐方案" + solution.recommendedId());

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

        if (scenarioId != null && !scenarioId.isBlank()) {
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
        result.put("aiChannel", anyAi ? channelName() : "规则模板");
        if (scenarioId != null && !scenarioId.isBlank()) {
            result.put("scenarioId", scenarioId);
        }

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentCard(RiskAgent.ID, RiskAgent.NAME, RiskAgent.ICON,
                "解析官方气象数据与地质元数据，RAG 检索历史相似案例，输出熔断决策解释",
                risk.explanation(), riskMs, risk.aiPowered()));
        Map<String, Object> solDetail = new LinkedHashMap<>();
        solDetail.put("planCount", solution.plans().size());
        solDetail.put("recommended", solution.recommendedId());
        solDetail.put("recommendation", solution.recommendation());
        agents.add(agentCard(SolutionAgent.ID, SolutionAgent.NAME, SolutionAgent.ICON,
                "生成公路绕行 / 公水联运 / 原地等待三个方案并量化对比",
                solDetail, solMs, solution.aiPowered()));
        agents.add(agentCard(TouchAgent.ID, TouchAgent.NAME, TouchAgent.ICON,
                "按平台配置为各角色生成专属执行指令（非选择题，是执行指令+权益保障）",
                touch.preview(), touchMs, touch.aiPowered()));
        result.put("agents", agents);

        // 前端"依次点亮"动画数据源（升级方案 §2.3）
        List<Map<String, Object>> agentStatus = new ArrayList<>();
        agentStatus.add(statusRow(RiskAgent.NAME, summarize(risk.explanation().get("boundary"))));
        agentStatus.add(statusRow(SolutionAgent.NAME,
                "推荐方案" + solution.recommendedId() + "：" + solution.recommendation()));
        agentStatus.add(statusRow(TouchAgent.NAME,
                touch.preview().get("count") + " 个触达角色指令已生成"));
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

    private String channelName() {
        return "本地大模型（Ollama）/ 远程通道自动切换";
    }

    private Map<String, Object> agentCard(String id, String name, String icon, String duty,
                                          Map<String, Object> detail, long elapsedMs,
                                          boolean aiPowered) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("duty", duty);
        m.put("status", "DONE");
        m.put("elapsedMs", elapsedMs);
        m.put("aiPowered", aiPowered);
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
