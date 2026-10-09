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
 * - POST /api/trip/progress  导航中周期上报行程进度（0~1），方案生成据此禁用"掉头回港口"类方案
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

    /** 司机端导航中上报进度：ratio∈[0,1]。不在途时静默忽略。 */
    @PostMapping("/progress")
    public Map<String, Object> progress(@RequestParam double ratio) {
        tripSignal.updateProgress(ratio);
        return Map.of("ok", true, "progressRatio", tripSignal.progress());
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
