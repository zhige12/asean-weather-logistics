package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.DecisionSandboxService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策沙盘端点（演示第一/二幕）：
 * - POST /api/sandbox/push      模拟官方气象接口推送（第一幕触发点，默认友谊关85mm/芒街62mm）
 * - POST /api/sandbox/rainfall  调度员拖动滑块调整预测降雨量（第二幕）
 * - GET  /api/sandbox/state     沙盘当前状态（断面/阈值/熔断状态/决策边界）
 * - POST /api/sandbox/reset     重置沙盘，撤销沙盘注入的风险
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private final DecisionSandboxService sandbox;

    public SandboxController(DecisionSandboxService sandbox) {
        this.sandbox = sandbox;
    }

    @PostMapping("/push")
    public Map<String, Object> push(@RequestParam(defaultValue = "85") double yggMm,
                                    @RequestParam(defaultValue = "62") double mcMm) {
        return sandbox.pushOfficialForecast(yggMm, mcMm);
    }

    @PostMapping("/rainfall")
    public Map<String, Object> rainfall(@RequestBody Map<String, Object> body) {
        Double ygg = body.get("yggMm") == null ? null : ((Number) body.get("yggMm")).doubleValue();
        Double mc = body.get("mcMm") == null ? null : ((Number) body.get("mcMm")).doubleValue();
        return sandbox.setRainfall(ygg, mc);
    }

    /** 场景B：调整运河通航条件（模拟水运禁航）。可单传/组合传能见度/流速/浪高。 */
    @PostMapping("/water")
    public Map<String, Object> water(@RequestBody Map<String, Object> body) {
        Double vis = body.get("visibilityM") == null ? null : ((Number) body.get("visibilityM")).doubleValue();
        Double cur = body.get("currentMs") == null ? null : ((Number) body.get("currentMs")).doubleValue();
        Double wave = body.get("waveM") == null ? null : ((Number) body.get("waveM")).doubleValue();
        return sandbox.setWaterConditions(vis, cur, wave);
    }

    /** 一键模拟水运禁航（能见度 900m，触发红线），演示场景B快捷入口。 */
    @PostMapping("/water/block")
    public Map<String, Object> waterBlock() {
        return sandbox.setWaterConditions(900.0, 1.8, 0.8);
    }

    /** 恢复运河正常通航。 */
    @PostMapping("/water/clear")
    public Map<String, Object> waterClear() {
        return sandbox.setWaterConditions(1200.0, 1.8, 0.8);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return sandbox.state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return sandbox.reset();
    }
}
