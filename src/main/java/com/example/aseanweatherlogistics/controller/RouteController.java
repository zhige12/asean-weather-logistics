package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.model.dto.RouteRequest;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.service.GraphHopperRouteService;
import com.example.aseanweatherlogistics.service.RouteService;
import com.example.aseanweatherlogistics.service.OsmDataLoader;
import com.example.aseanweatherlogistics.service.WeatherSimulator;
import com.example.aseanweatherlogistics.service.AIService;
import com.example.aseanweatherlogistics.service.RouteAgentService;
import com.example.aseanweatherlogistics.repository.RouteRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/routes", "/api/route"})
public class RouteController {
    private final RouteService routeService;
    private final OsmDataLoader osmDataLoader;
    private final GraphHopperRouteService graphHopperRouteService;
    private final WeatherSimulator weatherSimulator;
    private final AIService aiService;
    private final RouteRepository routeRepository;
    private final RouteAgentService routeAgentService;

    public RouteController(RouteService routeService, OsmDataLoader osmDataLoader, GraphHopperRouteService graphHopperRouteService, WeatherSimulator weatherSimulator, AIService aiService, RouteRepository routeRepository, RouteAgentService routeAgentService) {
        this.routeService = routeService;
        this.osmDataLoader = osmDataLoader;
        this.graphHopperRouteService = graphHopperRouteService;
        this.weatherSimulator = weatherSimulator;
        this.aiService = aiService;
        this.routeRepository = routeRepository;
        this.routeAgentService = routeAgentService;
    }

    @PostMapping("/plan")
    public RouteResponse plan(@RequestBody RouteRequest request) {
        return routeService.planRoute(request);
    }

    @GetMapping("/plan-real")
    public Map<String, Object> planRealRoute(
            @RequestParam double fromLat,
            @RequestParam double fromLon,
            @RequestParam double toLat,
            @RequestParam double toLon) {
        return graphHopperRouteService.getRoute(fromLat, fromLon, toLat, toLon);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        String message;
        if (!osmDataLoader.isOsmLoaderEnabled()) {
            message = "OSM loader disabled";
        } else if (osmDataLoader.isLoaded()) {
            message = "OSM road network loaded";
        } else if (osmDataLoader.isLoading()) {
            message = "OSM road network loading";
        } else {
            message = "OSM loader idle";
        }
        return Map.of(
                "enabled", osmDataLoader.isOsmLoaderEnabled(),
                "loaded", osmDataLoader.isLoaded(),
                "loading", osmDataLoader.isLoading(),
                "message", message);
    }

    @PostMapping("/osm/start")
    public Map<String, Object> startOsmLoader() {
        osmDataLoader.startAsyncLoad();
        return Map.of("status", "started");
    }

    @PostMapping("/osm/stop")
    public Map<String, Object> stopOsmLoader() {
        osmDataLoader.stopLoader();
        return Map.of("status", "stopped");
    }

    @PostMapping("/block")
    public Map<String, String> blockEdge(
            @RequestParam String edgeId,
            @RequestParam(defaultValue = "AUTO") String reason,
            @RequestParam(defaultValue = "HIGH") String severity,
            @RequestParam(defaultValue = "100") double penaltyMultiplier) {
        com.example.aseanweatherlogistics.model.dto.RiskSegment r = new com.example.aseanweatherlogistics.model.dto.RiskSegment(edgeId, reason, severity, penaltyMultiplier);
        weatherSimulator.injectRisk(r);
        return Map.of("status", "blocked", "edgeId", edgeId);
    }

    @PostMapping("/unblock")
    public Map<String, String> unblockEdge(@RequestParam String edgeId) {
        weatherSimulator.clearRisk(edgeId);
        return Map.of("status", "unblocked", "edgeId", edgeId);
    }

    // New endpoints for frontend visualization
    @GetMapping("/network")
    public Map<String, Object> network() {
        var data = routeRepository.loadRoadNetwork();
        Map<String, Object> res = new HashMap<>();
        List<RoadNode> nodes = data.getNodes();
        List<RoadEdge> edges = data.getEdges();
        res.put("nodes", nodes);
        res.put("edges", edges);
        return res;
    }

    @PostMapping("/plan-sample")
    public Map<String, Object> planSample(@RequestParam String originId, @RequestParam String destinationId) {
        RouteResponse resp = routeService.planRoute(new RouteRequest(originId, destinationId, null, null));
        var data = routeRepository.loadRoadNetwork();
        Map<String, RoadNode> nodeMap = data.getNodes().stream().collect(Collectors.toMap(RoadNode::getId, n -> n));
        var coords = resp.getPathNodeIds().stream().map(id -> {
            RoadNode n = nodeMap.get(id);
            return Map.of("lat", n.getLatitude(), "lon", n.getLongitude(), "id", id);
        }).collect(Collectors.toList());
        return Map.of("coordinates", coords, "response", resp);
    }

    /**
     * 多路线候选：司机端"规划 → 展示多条候选 → 自选"。
     * 返回最多 3 条互不重复的路线（recommended 推荐 / alternate 备选 / fastest 最快），
     * 每条含 coords/nodeIds/edgeIds/途经摘要/耗时/里程/沿线风险数。
     */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(
            @RequestParam(required = false) String originId,
            @RequestParam(required = false) String destinationId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String cargoType,
            @RequestParam(required = false) Boolean forecast) {
        String o = originId != null && !originId.isBlank() ? originId : "NN";
        String d = destinationId != null && !destinationId.isBlank() ? destinationId : "HN";
        List<Map<String, Object>> list = routeService.planCandidates(o, d, period, cargoType);
        // 司机"选路前先看灾害概率"：为每条候选预测未来发生灾害的概率(0-100)与主要灾害类型。
        // 默认走实时气象规则引擎（毫秒级）；forecast=deep 时才调大模型做深度预测。
        boolean deep = forecast != null && "deep".equalsIgnoreCase(String.valueOf(forecast));
        for (Map<String, Object> c : list) {
            try {
                @SuppressWarnings("unchecked")
                List<String> nodeIds = (List<String>) c.get("nodeIds");
                @SuppressWarnings("unchecked")
                List<String> edgeIds = (List<String>) c.get("edgeIds");
                Map<String, Object> risk = aiService.predictRouteRisk(o, d, nodeIds, edgeIds, deep);
                c.put("hazardProbability", risk.get("probability"));
                c.put("hazardLevel", risk.get("level"));
                c.put("hazardTypes", risk.get("hazardTypes"));
                c.put("hazardReason", risk.get("reason"));
                c.put("hazardOccurred", risk.get("occurred"));
                c.put("hazardBreakdown", risk.get("breakdown")); // 逐灾种概率（查看详情）
                c.put("hazardHasWeather", risk.get("hasWeather"));
            } catch (Exception e) {
                // 预测失败不阻塞选路：退化为未知概率
                c.put("hazardProbability", -1);
                c.put("hazardLevel", "未知");
            }
        }
        return Map.of("candidates", list);
    }

    @GetMapping("/plan-with-weather")
    public Map<String, Object> planWithWeather(
            @RequestParam(required = false) String originId,
            @RequestParam(required = false) String destinationId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String cargoType,
            @RequestParam(required = false) String choice,
            /** 是否顺带生成 AI 预警文案。AI 调用耗时秒级，而多数调用方（15 秒轮询、
                局部改派、大屏规划）根本不消费 aiWarning —— 传 false 可让规划回到几十毫秒。 */
            @RequestParam(defaultValue = "true") boolean withAi,
            @RequestParam(required = false) Double fromLat,
            @RequestParam(required = false) Double fromLon,
            @RequestParam(required = false) Double toLat,
            @RequestParam(required = false) Double toLon) {
        // Mode 1: sample graph nodes
        if (originId != null && destinationId != null) {
            // 注册为 agent 活跃任务：突发灾害后 agent 自动对这条 O/D 重算并推送
            routeAgentService.registerActiveRoute(originId, destinationId);
            RouteResponse resp;
            if (choice != null && !choice.isBlank() && !"recommended".equals(choice)) {
                resp = routeService.planRouteByChoice(originId, destinationId, period, cargoType, choice);
            } else {
                resp = routeService.planRoute(new RouteRequest(originId, destinationId, period, cargoType));
            }
            String aiWarning = "";
            if (withAi) {
                try {
                    aiWarning = aiService.buildWarning(resp);
                } catch (Exception e) {
                    aiWarning = "AI warning generation failed: " + e.getMessage();
                }
            }
            return Map.of("route", resp, "aiWarning", aiWarning, "risks", weatherSimulator.currentRisks());
        }

        // Mode 2: lat/lon GraphHopper route
        if (fromLat != null && fromLon != null && toLat != null && toLon != null) {
            Map<String, Object> gh = graphHopperRouteService.getRoute(fromLat, fromLon, toLat, toLon);
            // Build a lightweight summary for AI: distance and list of active risks
            RouteResponse fakeResp = new RouteResponse();
            fakeResp.setPathNodeIds(List.of("REAL_ROUTE"));
            Object distObj = gh.getOrDefault("distance_km", 0.0);
            double dist = 0.0;
            if (distObj instanceof Number) dist = ((Number) distObj).doubleValue();
            fakeResp.setTotalDistanceKm(dist);
            fakeResp.setRiskSegments(weatherSimulator.currentRisks());
            String aiWarning = "";
            try {
                aiWarning = aiService.buildWarning(fakeResp);
            } catch (Exception e) {
                aiWarning = "AI warning generation failed: " + e.getMessage();
            }
            return Map.of("graphhopper", gh, "aiWarning", aiWarning, "risks", weatherSimulator.currentRisks());
        }

        throw new IllegalArgumentException("Either originId+destinationId or lat/lon pairs must be provided");
    }

    /**
     * 局部绕行避灾：司机行驶到中途后，前方出现灾害，从当前位置绕开灾害段并重归原路线。
     * 与全程重算不同，只有受影响路段被替换，其余保持不变。
     */
    @PostMapping("/detour")
    public Map<String, Object> detourAroundHazard(
            @RequestParam String destinationId,
            @RequestParam(defaultValue = "0.3") double progressRatio,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> currentRouteEdgeIds = (List<String>) body.get("currentRouteEdgeIds");
        @SuppressWarnings("unchecked")
        List<String> currentRouteNodeIds = (List<String>) body.get("currentRouteNodeIds");
        @SuppressWarnings("unchecked")
        List<String> hazardEdgeIdsRaw = (List<String>) body.get("hazardEdgeIds");

        if (currentRouteEdgeIds == null || currentRouteNodeIds == null
                || currentRouteNodeIds.isEmpty()) {
            throw new IllegalArgumentException("currentRouteEdgeIds and currentRouteNodeIds are required");
        }

        Set<String> hazardEdgeIds = hazardEdgeIdsRaw != null
                ? new java.util.HashSet<>(hazardEdgeIdsRaw)
                : new java.util.HashSet<>();

        RouteResponse resp = routeService.detourAroundHazard(
                destinationId, currentRouteEdgeIds, currentRouteNodeIds, hazardEdgeIds, progressRatio);

        String advice = resp.isRerouted()
                ? "⚠ 灾害已在路线上检测到，已从当前位置局部绕行避开"
                : "当前路线不受灾害影响，无需绕行";

        return Map.of("route", resp, "risks", weatherSimulator.currentRisks(), "advice", advice);
    }
}
