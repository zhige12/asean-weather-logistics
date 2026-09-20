package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.model.dto.RouteRequest;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.service.BriefingService;
import com.example.aseanweatherlogistics.service.RouteService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务简报控制器：提供简报生成 API
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService briefingService;
    private final RouteService routeService;

    public BriefingController(BriefingService briefingService, RouteService routeService) {
        this.briefingService = briefingService;
        this.routeService = routeService;
    }

    /**
     * 根据起终点生成任务简报
     *
     * @param originId      起点节点 ID
     * @param destinationId 终点节点 ID
     * @param period        时段（normal/peak/holiday）
     * @param cargoType     货物类型（general/cold/dangerous/oversized）
     * @param plate         车牌号（可选）
     * @return 包含 briefing 文本的 Map
     */
    @GetMapping("/generate")
    public Map<String, Object> generate(
            @RequestParam(defaultValue = "NN") String originId,
            @RequestParam(defaultValue = "HN") String destinationId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String cargoType,
            @RequestParam(required = false) String plate) {

        RouteResponse route = routeService.planRoute(originId, destinationId, period, cargoType);

        // 起终点名称
        String originName = getNodeName(originId);
        String destName = getNodeName(destinationId);

        String briefing = briefingService.generateBriefing(route, originName, destName, plate, cargoType);

        return Map.of(
                "briefing", briefing,
                "route", route,
                "originName", originName,
                "destinationName", destName
        );
    }

    /**
     * 根据已有路线结果直接生成简报（POST，接收完整路线数据）
     */
    @PostMapping("/generate")
    public Map<String, Object> generateFromRoute(@RequestBody Map<String, Object> body) {
        // 从请求中提取参数
        String originId = (String) body.getOrDefault("originId", "NN");
        String destinationId = (String) body.getOrDefault("destinationId", "HN");
        String plate = (String) body.get("plate");
        String cargoType = (String) body.get("cargoType");

        RouteResponse route = routeService.planRoute(originId, destinationId, null, cargoType);

        String originName = getNodeName(originId);
        String destName = getNodeName(destinationId);

        String briefing = briefingService.generateBriefing(route, originName, destName, plate, cargoType);

        return Map.of(
                "briefing", briefing,
                "route", route
        );
    }

    private String getNodeName(String nodeId) {
        var node = routeService.getNode(nodeId);
        if (node != null && node.getName() != null && !node.getName().isBlank()) {
            return node.getName();
        }
        return nodeId;
    }
}
