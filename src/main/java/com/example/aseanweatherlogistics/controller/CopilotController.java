package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.CopilotService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然语言调度舱端点：
 * - POST /api/copilot/chat    调度员说一句自然语言 → 解析意图；只读工具直接执行回数据，
 *                             写操作回「待确认预览」（pendingConfirm=true），不落地。
 * - POST /api/copilot/execute 人对写操作点「确认」后调用，真正落地并回真实结果。
 * 请求体：{message, originId?, destinationId?} 或 {tool, args?, originId?, destinationId?}。
 */
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final CopilotService copilot;

    public CopilotController(CopilotService copilot) {
        this.copilot = copilot;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String message = str(b.get("message"));
        String origin = str(b.get("originId"));
        String dest = str(b.get("destinationId"));
        return copilot.chat(message, origin, dest);
    }

    @PostMapping("/execute")
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String tool = str(b.get("tool"));
        Map<String, Object> args = b.get("args") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        String origin = str(b.get("originId"));
        String dest = str(b.get("destinationId"));
        return copilot.execute(tool, args, origin, dest);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
