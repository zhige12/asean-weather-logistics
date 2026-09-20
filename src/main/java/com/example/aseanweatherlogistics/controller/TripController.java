package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.TripSignalService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行程启动信号端点：
 * - POST /api/trip/start  司机端点击"开始导航"时登记
 * - GET  /api/trip/signal 调度大屏轮询，发现新的启动即自动触发 AI 分析
 */
@RestController
@RequestMapping("/api/trip")
public class TripController {

    private final TripSignalService tripSignal;

    public TripController(TripSignalService tripSignal) {
        this.tripSignal = tripSignal;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(required = false) String originId,
                                     @RequestParam(required = false) String destinationId) {
        tripSignal.markStarted(originId, destinationId);
        return Map.of("ok", true, "startedAt", tripSignal.signal().get("startedAt"));
    }

    @GetMapping("/signal")
    public Map<String, Object> signal() {
        return tripSignal.signal();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        tripSignal.reset();
        return Map.of("ok", true);
    }
}
