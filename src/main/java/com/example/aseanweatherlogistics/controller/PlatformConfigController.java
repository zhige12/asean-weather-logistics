package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.DecisionLogService;
import com.example.aseanweatherlogistics.service.DecisionSandboxService;
import com.example.aseanweatherlogistics.service.DemoConfigService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放平台配置器端点（演示第十幕）：
 * - GET /api/platform/config  当前配置（风险容忍度/货物类型/触达对象 + 可选项）
 * - PUT /api/platform/config  部分更新；阈值变化后立即重估沙盘（85mm 在保守/平衡下熔断状态实时切换）
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformConfigController {

    private final DemoConfigService config;
    private final DecisionSandboxService sandbox;
    private final DecisionLogService decisionLog;

    public PlatformConfigController(DemoConfigService config, DecisionSandboxService sandbox,
                                    DecisionLogService decisionLog) {
        this.config = config;
        this.sandbox = sandbox;
        this.decisionLog = decisionLog;
    }

    @GetMapping("/config")
    public Map<String, Object> get() {
        return config.toMap();
    }

    @PutMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        String riskProfile = body.get("riskProfile") instanceof String s ? s : null;
        String cargoType = body.get("cargoType") instanceof String s ? s : null;
        java.util.Set<String> targets = null;
        if (body.get("outreachTargets") instanceof List<?> list) {
            targets = new LinkedHashSet<>();
            for (Object o : list) {
                if (o instanceof String s) {
                    targets.add(s);
                }
            }
        }
        Map<String, Object> updated = config.update(riskProfile, cargoType, targets);
        decisionLog.log("CONFIG", "平台配置调整",
                String.format("风险容忍度=%s（阈值%.0fmm），货物=%s，触达对象=%s",
                        updated.get("riskProfileName"), updated.get("fuseThresholdMm"),
                        updated.get("cargoTypeName"), updated.get("outreachTargets")));
        // 阈值或货物变化后：沙盘立即按新阈值重估（熔断状态实时联动）
        if (sandbox.pushed()) {
            sandbox.evaluate("开放平台配置调整");
        }
        return updated;
    }

    /**
     * 一键套用预设智能体模板（冷链火龙果 / 电子元件防潮 / 大宗普货）：
     * 同时切换风险偏好+货物类型+触达名单，沙盘与方案对比实时联动。
     */
    @PostMapping("/template")
    public Map<String, Object> applyTemplate(@RequestBody Map<String, Object> body) {
        String id = body.get("id") instanceof String s ? s : "";
        Map<String, Object> updated = config.applyTemplate(id);
        decisionLog.log("CONFIG", "套用智能体模板",
                String.format("套用模板：%s（风险容忍度=%s，阈值%.0fmm，货物=%s，触达%s个角色）",
                        updated.get("activeTemplate"), updated.get("riskProfileName"),
                        updated.get("fuseThresholdMm"), updated.get("cargoTypeName"),
                        ((List<?>) updated.getOrDefault("outreachTargets", List.of())).size()));
        if (sandbox.pushed()) {
            sandbox.evaluate("套用智能体模板");
        }
        return updated;
    }
}
