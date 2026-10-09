package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.RouteAgentService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 实时守护端点：
 * - GET /api/agent/events       调度大屏/司机端订阅 SSE 推送流（灾害→自动重算→推送新路线）
 * - POST /api/agent/register    前端规划路线时注册当前活跃起终点
 * - GET /api/agent/status       agent 运行状态（调试用）
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final RouteAgentService routeAgentService;

    public AgentController(RouteAgentService routeAgentService) {
        this.routeAgentService = routeAgentService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents() {
        return routeAgentService.subscribe();
    }

    /**
     * 注册关注路线。mode 可选：road=公路导航（默认），canal=陆水联运（正在导航去平陆运河六景港），
     * 运河禁航时 canal 模式的关注路线会随 canal-block 事件点名推给调度大屏。
     */
    @PostMapping("/register")
    public Map<String, Object> registerRoute(
            @RequestParam String originId,
            @RequestParam String destinationId,
            @RequestParam(required = false) String mode) {
        routeAgentService.registerActiveRoute(originId, destinationId, mode);
        return Map.of("ok", true, "origin", originId, "destination", destinationId);
    }

    /** 停止关注某条路线（司机结束导航 / 退出，避免被无关路线打扰） */
    @PostMapping("/unregister")
    public Map<String, Object> unregisterRoute(
            @RequestParam String originId,
            @RequestParam String destinationId) {
        routeAgentService.unregisterActiveRoute(originId, destinationId);
        return Map.of("ok", true);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return routeAgentService.status();
    }
}
