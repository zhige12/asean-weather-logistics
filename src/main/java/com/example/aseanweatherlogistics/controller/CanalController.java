package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.DecisionSandboxService;
import com.example.aseanweatherlogistics.service.IntermodalService;
import com.example.aseanweatherlogistics.service.TributaryRiskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平陆运河水运端点：
 * - GET  /api/canal/tributaries        支流风险差异化预测（第九幕：各支流独立特征向量 → 概率）
 * - GET  /api/canal/tributaries/live   支流风险实况评估（降雨输入：当前气象源实时雨强）
 * - POST /api/canal/tributaries/apply  支流风险联动干流：概率聚合为顶托流速并参与禁航红线判定
 * - POST /api/canal/tributaries/apply-live 实况模式联动（真实气象驱动，无数据时不注入）
 * - GET  /api/canal/intermodal         陆水联运方案详情（方案B分段明细）
 * - GET  /api/canal/corridor           陆水联运后续走廊几何（南宁港→运河→钦州港→海运→海防→河内，供地图预览）
 */
@RestController
@RequestMapping("/api/canal")
public class CanalController {

    private final TributaryRiskService tributaryRiskService;
    private final IntermodalService intermodalService;
    private final DecisionSandboxService sandboxService;

    public CanalController(TributaryRiskService tributaryRiskService,
                           IntermodalService intermodalService,
                           DecisionSandboxService sandboxService) {
        this.tributaryRiskService = tributaryRiskService;
        this.intermodalService = intermodalService;
        this.sandboxService = sandboxService;
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

    /**
     * 支流风险→干流禁航联动（把支流卡"用上"）：
     * rainfallMm 聚合为顶托设计流速后走沙盘同一套禁航判定，状态翻转时经 SSE
     * 推送 canal-block / canal-recover（大屏换线卡、司机端弹替代路线）。
     * 传 0 解除联动。返回沙盘完整 state（含 water 断面与 tributaryImpact 明细）。
     */
    @PostMapping("/tributaries/apply")
    public Map<String, Object> applyTributaries(@RequestParam(defaultValue = "45") double rainfallMm) {
        return sandboxService.syncTributaryRisk(rainfallMm);
    }

    /** 支流风险实况评估：按当前气象数据源拉各支流口实时雨强，只读不联动（预览用） */
    @GetMapping("/tributaries/live")
    public Map<String, Object> tributariesLive() {
        return tributaryRiskService.liveAssessment();
    }

    /** 实况模式联动干流禁航：真实气象驱动，链路同 /apply；气象无数据时保持原状态不变 */
    @PostMapping("/tributaries/apply-live")
    public Map<String, Object> applyTributariesLive() {
        return sandboxService.syncTributaryRiskLive();
    }

    @GetMapping("/intermodal")
    public Map<String, Object> intermodal(@RequestParam(defaultValue = "390") double roadKm,
                                          @RequestParam(defaultValue = "8.5") double roadHours) {
        return intermodalService.plan(roadKm, roadHours);
    }

    /** 方案B后续走廊折线：司机段只到南宁港，运河/海运/越南通段只供图上总览 */
    @GetMapping("/corridor")
    public Map<String, Object> corridor() {
        return intermodalService.corridor();
    }
}
