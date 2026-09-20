package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.DecisionLogService;
import com.example.aseanweatherlogistics.service.OutreachService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多角色触达端点（演示第四/五/六幕）：
 * - POST /api/outreach/dispatch       调度员确认方案 → 任务变更指令下发各角色
 * - POST /api/outreach/confirm        司机/船东 App 点击"确认接收"
 * - GET  /api/outreach/status         触达状态面板（送达/确认/升级）
 * - GET  /api/outreach/message        某目标的消息内容（司机端拉取，含越南语）
 * - POST /api/outreach/reset          清空触达批次
 * - GET  /api/outreach/log            决策日志（全程留痕）
 */
@RestController
@RequestMapping("/api/outreach")
public class OutreachController {

    private final OutreachService outreachService;
    private final DecisionLogService decisionLog;

    public OutreachController(OutreachService outreachService, DecisionLogService decisionLog) {
        this.outreachService = outreachService;
        this.decisionLog = decisionLog;
    }

    @PostMapping("/dispatch")
    public Map<String, Object> dispatch(@RequestBody(required = false) Map<String, Object> body) {
        String planId = body != null && body.get("planId") instanceof String s ? s : "B";
        String planName = body != null && body.get("planName") instanceof String s ? s : "公水联运";
        return outreachService.dispatch(planId, planName);
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, Object> body) {
        String targetId = String.valueOf(body.get("targetId"));
        return outreachService.confirm(targetId);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return outreachService.status();
    }

    @GetMapping("/message")
    public Map<String, Object> message(@RequestParam String targetId) {
        return outreachService.message(targetId);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return outreachService.reset();
    }

    @GetMapping("/log")
    public List<DecisionLogService.Entry> log(@RequestParam(defaultValue = "100") int limit) {
        return decisionLog.recent(limit);
    }
}
