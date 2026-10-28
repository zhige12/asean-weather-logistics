package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.AiMetricsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 调用计量查询：把「推理速度、成本、并发」摊开给评委当场核。
 * 只读、无副作用，返回的是本进程启动以来的累计量（重启归零）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiMetricsController {

    private final AiMetricsService metrics;

    public AiMetricsController(AiMetricsService metrics) {
        this.metrics = metrics;
    }

    /** 累计 token / 平均与最大耗时 / 失败数 / 在飞与峰值并发 / 成本折算状态。 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }
}
