package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 行程启动信号（内存态，演示用）。
 * <p>
 * 司机端点击"开始导航"时登记一次行程启动，调度大屏轮询到新的启动信号后
 * 自动触发一次 AI 六维分析（常态方案对比），由调度员人工确认路线后再下发给司机。
 * 这样"分析"不再是只有熔断才做，车一动就先给调度端一份完整方案对比。
 */
@Service
public class TripSignalService {

    private volatile long startedAt = 0;
    private volatile String originId = "";
    private volatile String destinationId = "";

    /** 司机端开始导航：登记一次行程启动（覆盖上一次）。 */
    public synchronized void markStarted(String originId, String destinationId) {
        this.originId = originId == null ? "" : originId;
        this.destinationId = destinationId == null ? "" : destinationId;
        this.startedAt = System.currentTimeMillis();
    }

    /** 清空信号（演示重置）。 */
    public synchronized void reset() {
        this.startedAt = 0;
        this.originId = "";
        this.destinationId = "";
    }

    public Map<String, Object> signal() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", startedAt > 0);
        m.put("startedAt", startedAt);
        m.put("originId", originId);
        m.put("destinationId", destinationId);
        return m;
    }
}
