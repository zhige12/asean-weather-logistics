package com.example.aseanweatherlogistics.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.aseanweatherlogistics.service.PortalCopilotService;

/**
 * 门户首页「AI志宁 · 快速导航」端点：
 * - POST /api/copilot/portal  一句话 → {action: open|navigate|knowledge|say, args, say, channel, data?}
 * 与大屏 /api/copilot/chat 的区别是能力收窄：只允许打开门户页面、跳司机端导航、知识库问答；
 * 场景模拟/禁航推演等大屏专属意图在这里回「学习中」，服务端不执行任何沙盘写操作。
 */
@RestController
@RequestMapping("/api/copilot")
public class PortalCopilotController {

    private final PortalCopilotService portalCopilot;

    public PortalCopilotController(PortalCopilotService portalCopilot) {
        this.portalCopilot = portalCopilot;
    }

    @PostMapping("/portal")
    public Map<String, Object> chat(@RequestBody(required = false) Map<String, Object> body) {
        String message = body == null || body.get("message") == null ? null : String.valueOf(body.get("message"));
        return portalCopilot.chat(message);
    }
}
