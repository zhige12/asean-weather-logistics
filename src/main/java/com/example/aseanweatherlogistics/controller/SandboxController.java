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

    /**
     * 剧本场景推送（整合入口）：scenario = rain（暴雨 85/62mm）/ typhoon（芒街台风）/ both（两者叠加）。
     * 切换场景会先清台风状态，所以从"台风"切回"暴雨"能真正解除台风。
     */
    @PostMapping("/push-scenario")
    public Map<String, Object> pushScenario(@RequestBody(required = false) Map<String, Object> body) {
        String scenario = body == null || body.get("scenario") == null
                ? "rain" : String.valueOf(body.get("scenario"));
        return sandbox.pushScripted(scenario);
    }

    /**
     * 拉取 GOWFS 真实预报作为沙盘降雨输入（取代手填剧本值）。
     * 取数失败时返回当前沙盘状态 + forecastMessage，绝不把"没拉到"当成"无降雨"。
     */
    @PostMapping("/push-live")
    public Map<String, Object> pushLive() {
        return sandbox.pushLiveForecast();
    }

    /**
     * 进入模拟模式：气象参数转为可调，拖到阈值自动涌现灾害
     * （公路熔断 / 台风熔断 / 水运禁航），判定与真实数据同一套规则。
     */
    @PostMapping("/sim/enter")
    public Map<String, Object> simEnter() {
        return sandbox.enterSimMode();
    }

    /** 退出模拟模式：恢复 GOWFS 真实数据（只读） */
    @PostMapping("/sim/exit")
    public Map<String, Object> simExit() {
        return sandbox.exitSimMode();
    }

    /** 模拟模式下调整气象参数（未传的项保持不变） */
    @PostMapping("/sim/params")
    public Map<String, Object> simParams(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        return sandbox.setSimParams(
                num(body, "yggRainMm"), num(body, "mcRainMm"),
                num(body, "yggPressureHpa"), num(body, "yggWindKph"),
                num(body, "mcPressureHpa"), num(body, "mcWindKph"));
    }

    private static Double num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** GOWFS 预报通道自检（配置/节流/熔断状态，不含密钥），供界面说明"为何没拉到" */
    @GetMapping("/forecast-status")
    public Map<String, Object> forecastStatus() {
        return sandbox.forecastStatus();
    }

    /**
     * 模拟台风：给断面设置气压/风速，走与真实气象相同的 TyphoonRiskRule 判据。
     * 默认 985hPa / 75km/h 可触发熔断；sectionId 传 YGG 或 MC。
     */
    @PostMapping("/typhoon")
    public Map<String, Object> typhoon(@RequestBody(required = false) Map<String, Object> body) {
        String sectionId = body == null || body.get("sectionId") == null
                ? "MC" : String.valueOf(body.get("sectionId"));
        double prs = body == null || body.get("pressureHpa") == null
                ? 985.0 : ((Number) body.get("pressureHpa")).doubleValue();
        double wind = body == null || body.get("windKph") == null
                ? 75.0 : ((Number) body.get("windKph")).doubleValue();
        return sandbox.setTyphoon(sectionId, prs, wind);
    }

    /** 解除某断面的模拟台风（默认芒街），回到仅按降雨判定。 */
    @PostMapping("/typhoon/clear")
    public Map<String, Object> typhoonClear(@RequestBody(required = false) Map<String, Object> body) {
        String sectionId = body == null || body.get("sectionId") == null
                ? "MC" : String.valueOf(body.get("sectionId"));
        return sandbox.clearTyphoon(sectionId);
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
