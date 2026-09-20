package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.model.dto.RouteRequest;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.vo.WarningVO;
import com.example.aseanweatherlogistics.service.AIService;
import com.example.aseanweatherlogistics.service.RouteService;
import com.example.aseanweatherlogistics.service.WeatherSimulator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AIController {
    private final RouteService routeService;
    private final AIService aiService;
    private final WeatherSimulator weatherSimulator;

    public AIController(RouteService routeService, AIService aiService,
                        WeatherSimulator weatherSimulator) {
        this.routeService = routeService;
        this.aiService = aiService;
        this.weatherSimulator = weatherSimulator;
    }

    @GetMapping("/warning")
    public WarningVO warning(@RequestParam String originId, @RequestParam String destinationId) {
        RouteResponse route = routeService.planRoute(new RouteRequest(originId, destinationId));
        String warning = aiService.buildWarning(route);
        String level = route.getRiskSegments().isEmpty() ? "LOW" : "HIGH";
        return new WarningVO(level, warning, Instant.now());
    }

    /** 中越双语预警（演示流程步骤4：大模型生成双语专业预警） */
    @GetMapping("/warning-bilingual")
    public WarningVO warningBilingual(@RequestParam String originId, @RequestParam String destinationId) {
        RouteResponse route = routeService.planRoute(new RouteRequest(originId, destinationId));
        String warning = aiService.buildBilingualWarning(route);
        String level = route.getRiskSegments().isEmpty() ? "LOW" : "HIGH";
        return new WarningVO(level, warning, Instant.now());
    }

    /**
     * AI + 实时气象灾害预测（闭环核心）：
     * 拉取沿线实时气象原始数据 → DeepSeek 预测未来灾害 → 注入风险
     * → 触发 RouteAgentService 实时重算 → SSE 推送新路线到调度大屏与司机端。
     */
    @PostMapping("/predict-hazards")
    public Map<String, Object> predictHazards(@RequestParam String originId,
                                              @RequestParam String destinationId) {
        return aiService.predictWeatherHazards(originId, destinationId);
    }

    /**
     * 路线级灾害概率预测（司机选路 / 途中实时预测通用）。
     * deep=true 时调用大模型做深度预测（途中实时预测用，较慢）；false 走实时气象规则引擎（选路列表用，毫秒级）。
     * 返回 probability(0-100) / level(低中高) / hazardTypes / occurred(是否已有风险生效) / reason / advice。
     */
    @GetMapping("/route-risk")
    public Map<String, Object> routeRisk(@RequestParam String originId,
                                         @RequestParam String destinationId,
                                         @RequestParam(required = false) String choice,
                                         @RequestParam(required = false) Boolean deep) {
        RouteResponse resp = ("alternate".equals(choice) || "fastest".equals(choice))
                ? routeService.planRouteByChoice(originId, destinationId, null, null, choice)
                : routeService.planRoute(new RouteRequest(originId, destinationId));
        boolean d = Boolean.TRUE.equals(deep);
        Map<String, Object> risk = aiService.predictRouteRisk(
                originId, destinationId, resp.getPathNodeIds(), resp.getPathEdgeIds(), d);
        risk.put("originId", originId);
        risk.put("destinationId", destinationId);
        risk.put("choice", choice == null ? "recommended" : choice);
        risk.put("estimatedHours", resp.getEstimatedHours());
        risk.put("rerouted", resp.isRerouted());
        return risk;
    }

    /** 清除 AI 预测注入的风险（reason 前缀为 "AI预测:" 的边） */
    @DeleteMapping("/predict-hazards")
    public Map<String, Object> clearPredictHazards() {
        int cleared = 0;
        for (com.example.aseanweatherlogistics.model.dto.RiskSegment rs
                : weatherSimulator.currentRisks()) {
            if (rs.getReason() != null && rs.getReason().startsWith("AI预测:")) {
                weatherSimulator.clearRisk(rs.getEdgeId());
                cleared++;
            }
        }
        return Map.of("cleared", cleared);
    }
}
