package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.service.RouteService;
import com.example.aseanweatherlogistics.service.WeatherSimulator;
import com.example.aseanweatherlogistics.service.DeepseekService;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.util.GeoUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {
    private final WeatherSimulator weatherSimulator;
    private final DeepseekService deepseekService;
    private final RouteService routeService;
    private final com.example.aseanweatherlogistics.repository.RouteRepository routeRepository;
    private final com.example.aseanweatherlogistics.service.RealWeatherService realWeatherService;
    private final com.example.aseanweatherlogistics.service.WeatherSourceService weatherSourceService;
    private final AtomicLong lastRefreshAt = new AtomicLong(0L);
    @Value("${weather.real.refresh-min-interval-seconds:15}")
    private long refreshMinIntervalSeconds;

    public WeatherController(WeatherSimulator weatherSimulator, DeepseekService deepseekService,
                             RouteService routeService,
                             com.example.aseanweatherlogistics.repository.RouteRepository routeRepository,
                             com.example.aseanweatherlogistics.service.RealWeatherService realWeatherService,
                             com.example.aseanweatherlogistics.service.WeatherSourceService weatherSourceService) {
        this.weatherSimulator = weatherSimulator;
        this.deepseekService = deepseekService;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
        this.realWeatherService = realWeatherService;
        this.weatherSourceService = weatherSourceService;
    }

    /** 气象数据源：当前选中 + 可选清单（比赛官方 / 真实气象 / 离线模拟） */
    @GetMapping("/source")
    public Map<String, Object> weatherSource() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", weatherSourceService.source());
        out.put("currentLabel", weatherSourceService.labelOf(weatherSourceService.source()));
        out.put("lastSource", realWeatherService.lastSource());
        out.put("options", weatherSourceService.options());
        return out;
    }

    /** 切换气象数据源：contest-observation / open-meteo / simulated */
    @PostMapping("/source/{sourceId}")
    public Map<String, Object> switchWeatherSource(@PathVariable String sourceId) {
        boolean ok = weatherSourceService.setSource(sourceId);
        if (ok) {
            // 重置节流/熔断，让新数据源立刻可拉一次（否则被上个源的节流窗口挡住）
            realWeatherService.resetThrottle();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("source", weatherSourceService.source());
        out.put("sourceLabel", weatherSourceService.labelOf(weatherSourceService.source()));
        if (!ok) {
            out.put("message", "unknown weather source: " + sourceId);
        }
        return out;
    }

    /** 实时气象：返回起点→终点沿线城市天气与当前风险；未传起终点则回退全量；网络失败自动降级模拟 */
    @GetMapping("/real")
    public Map<String, Object> realWeather(@RequestParam(required = false) String originId,
                                           @RequestParam(required = false) String destinationId) {
        return realWeatherView(routeService.syncRealWeatherForRoute(false, originId, destinationId));
    }

    /** 强制刷新实时气象（仅拉起点→终点沿线城市） */
    @PostMapping("/real/refresh")
    public Map<String, Object> refreshRealWeather(@RequestParam(required = false) String originId,
                                                  @RequestParam(required = false) String destinationId) {
        long now = System.currentTimeMillis();
        long minIntervalMs = Math.max(1, refreshMinIntervalSeconds) * 1000L;
        long last = lastRefreshAt.get();
        boolean allowForce = now - last >= minIntervalMs && lastRefreshAt.compareAndSet(last, now);
        return realWeatherView(routeService.syncRealWeatherForRoute(allowForce, originId, destinationId));
    }

    private Map<String, Object> realWeatherView(List<Map<String, Object>> points) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 实际生效的数据源：contest-observation / open-meteo / simulated / none（接口失败且无缓存）
        String src = realWeatherService.lastSource();
        out.put("source", src);
        out.put("sourceLabel", weatherSourceService.labelOf(src));
        out.put("points", points);
        out.put("risks", weatherSimulator.currentRisks());
        return out;
    }

    @PostMapping("/risk")
    public RiskSegment injectRisk(@RequestBody RiskSegment riskSegment) {
        return weatherSimulator.injectRisk(riskSegment);
    }

    /** 预置灾害场景列表（演示：一键注入暴雨/大雾/泥石流等） */
    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return weatherSimulator.listScenarios();
    }

    /** 一键注入预置灾害场景 */
    @PostMapping("/scenario/{scenarioId}")
    public Map<String, Object> applyScenario(@PathVariable String scenarioId) {
        RiskSegment risk = weatherSimulator.applyScenario(scenarioId);
        return Map.of("status", "applied", "scenarioId", scenarioId, "risk", risk);
    }

    /** 清除预置灾害场景注入的风险 */
    @DeleteMapping("/scenario/{scenarioId}")
    public Map<String, String> clearScenario(@PathVariable String scenarioId) {
        weatherSimulator.clearScenario(scenarioId);
        return Map.of("status", "cleared", "scenarioId", scenarioId);
    }

    @GetMapping("/risk")
    public List<RiskSegment> currentRisks() {
        return weatherSimulator.currentRisks();
    }

    @DeleteMapping("/risk/{edgeId}")
    public Map<String, String> clearRisk(@PathVariable String edgeId) {
        weatherSimulator.clearRisk(edgeId);
        return Map.of("message", "risk removed", "edgeId", edgeId);
    }

    @DeleteMapping("/risk")
    public Map<String, String> clearAllRisks() {
        weatherSimulator.clearAll();
        return Map.of("message", "all risks removed");
    }

    /**
     * Call Deepseek (chat) to get forecasts along a route (interpolated points),
     * map to penalty multipliers and inject RiskSegments for nearby edges in the sample network.
     */
    @PostMapping("/deepseek/scan")
    public Map<String, Object> deepseekScan(@RequestParam double fromLat,
                                             @RequestParam double fromLon,
                                             @RequestParam double toLat,
                                             @RequestParam double toLon) {
        // build 5 sample points along straight line
        int pts = 5;
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < pts; i++) {
            double t = i / (double) (pts - 1);
            double lat = fromLat + (toLat - fromLat) * t;
            double lon = fromLon + (toLon - fromLon) * t;
            points.add(new double[]{lat, lon});
        }
        List<Map<String, Object>> forecasts;
        try {
            forecasts = deepseekService.queryForecastForPoints(points);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Deepseek query failed: " + e.getMessage());
        }

        // map forecasts to nearest edge in sample graph and inject
        var data = routeRepository.loadRoadNetwork();
        var nodes = data.getNodes();
        var edges = data.getEdges();

        int injected = 0;
        for (Map<String, Object> f : forecasts) {
            double lat = ((Number) f.getOrDefault("lat", 0.0)).doubleValue();
            double lon = ((Number) f.getOrDefault("lon", 0.0)).doubleValue();
            double precipitation = ((Number) f.getOrDefault("precipitation_mm", 0.0)).doubleValue();
            double wind = ((Number) f.getOrDefault("wind_kph", 0.0)).doubleValue();
            double penalty = deepseekService.mapToPenalty(precipitation, wind);
            if (penalty <= 0) continue;

            // find nearest edge by node midpoint distance
            String nearestEdgeId = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (var e : edges) {
                RoadEdge re = e;
                RoadNode a = nodes.stream().filter(n->n.getId().equals(re.getFromNodeId())).findFirst().orElse(null);
                RoadNode b = nodes.stream().filter(n->n.getId().equals(re.getToNodeId())).findFirst().orElse(null);
                if (a == null || b == null) continue;
                double midLat = (a.getLatitude()+b.getLatitude())/2.0;
                double midLon = (a.getLongitude()+b.getLongitude())/2.0;
                double d = GeoUtils.haversineKm(lat, lon, midLat, midLon);
                if (d < bestDist) { bestDist = d; nearestEdgeId = re.getId(); }
            }
            // threshold (e.g., 100 km) to consider applying
            if (nearestEdgeId != null && bestDist < 200.0) {
                RiskSegment rs = new RiskSegment(nearestEdgeId, "Deepseek forecast", "HIGH", penalty);
                weatherSimulator.injectRisk(rs);
                injected++;
            }
        }

        return Map.of("status", "ok", "injected", injected, "forecasts", forecasts);
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        return GeoUtils.haversineKm(lat1, lon1, lat2, lon2);
    }
}
