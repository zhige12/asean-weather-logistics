package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.IntermodalService;
import com.example.aseanweatherlogistics.service.TributaryRiskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平陆运河水运端点：
 * - GET /api/canal/tributaries  支流风险差异化预测（第九幕：各支流独立特征向量 → 概率）
 * - GET /api/canal/intermodal   公水联运方案详情（方案B分段明细）
 */
@RestController
@RequestMapping("/api/canal")
public class CanalController {

    private final TributaryRiskService tributaryRiskService;
    private final IntermodalService intermodalService;

    public CanalController(TributaryRiskService tributaryRiskService,
                           IntermodalService intermodalService) {
        this.tributaryRiskService = tributaryRiskService;
        this.intermodalService = intermodalService;
    }

    @GetMapping("/tributaries")
    public Map<String, Object> tributaries(@RequestParam(defaultValue = "45") double rainfallMm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rainfallMm", rainfallMm);
        m.put("horizonHours", 6);
        m.put("tributaries", tributaryRiskService.predict(rainfallMm));
        m.put("note", "每条支流独立特征向量，输出概率而非简单熔断");
        return m;
    }

    @GetMapping("/intermodal")
    public Map<String, Object> intermodal(@RequestParam(defaultValue = "390") double roadKm,
                                          @RequestParam(defaultValue = "8.5") double roadHours) {
        return intermodalService.plan(roadKm, roadHours);
    }
}
