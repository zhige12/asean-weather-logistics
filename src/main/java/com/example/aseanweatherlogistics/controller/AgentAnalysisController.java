package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.DecisionOrchestrator;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体决策分析端点（演示第三幕）：
 * - GET /api/agents/analysis  一键触发编排器串行调度三智能体：
 *   🔵 风险研判（为什么熔断友谊关而不是芒街，RAG 历史案例）
 *   → 🟢 方案生成（公路绕行/陆水联运/原地等待）
 *   → 🟡 触达（各角色专属指令）
 * - scenarioId 非空时优先读预生成缓存（演示模式 2 秒出结果），无缓存则真实推理并落盘；
 *   但司机在途（progressRatio 有效）时强制真实推理，不读也不写缓存——方案要按车当前位置算。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentAnalysisController {

    private final DecisionOrchestrator orchestrator;

    public AgentAnalysisController(DecisionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/analysis")
    public Map<String, Object> analysis(@RequestParam(required = false) String originId,
                                        @RequestParam(required = false) String destinationId,
                                        @RequestParam(required = false) String scenarioId,
                                        @RequestParam(required = false) Double progressRatio) {
        return orchestrator.decide(originId, destinationId, scenarioId, progressRatio);
    }
}
