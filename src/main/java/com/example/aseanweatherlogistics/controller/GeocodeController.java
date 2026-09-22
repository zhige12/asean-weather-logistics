package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.GeocodeService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地理编码接口（PC 大屏与手机端共用）：
 * - GET /api/geocode?name=南宁   → {name, lat, lng, source: local|tianditu}
 * - GET /api/geocode/list        → 本地地名库全量（前端下拉候选 + 离线预匹配）
 * 坐标 → 路线规划走 POST /api/route/plan（RouteController，请求体带起终点坐标）。
 */
@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    private final GeocodeService geocodeService;

    public GeocodeController(GeocodeService geocodeService) {
        this.geocodeService = geocodeService;
    }

    @GetMapping
    public Map<String, Object> geocode(@RequestParam String name) {
        Map<String, Object> hit = geocodeService.geocode(name);
        if (hit == null) {
            throw new IllegalArgumentException("未找到地名「" + name + "」，请检查输入或改输附近城市");
        }
        return hit;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> places = geocodeService.list();
        return Map.of("places", places, "total", places.size());
    }

    /**
     * 坐标 → 最近路网节点（吸附）。手机端把地名编码成坐标后调此接口拿到 nodeId，
     * 再复用司机端既有的按节点算路/候选/熔断/公水联运流程。
     * 超出覆盖范围（最近节点 &gt; 5km）由 GeocodeService 抛 IllegalArgumentException → 400。
     */
    @GetMapping("/snap")
    public Map<String, Object> snap(@RequestParam double lat, @RequestParam double lng,
                                    @RequestParam(required = false) String label) {
        return geocodeService.snap(label == null || label.isBlank() ? "该点" : label, lat, lng);
    }
}
