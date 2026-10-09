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
 * <p>
 * 同时记录司机端周期上报的行程进度（0~1）：方案生成需要知道车开到哪了——
 * 已经开出半程的车不能再推荐"回南宁港坐船"的陆水联运方案。
 */
@Service
public class TripSignalService {

    private volatile long startedAt = 0;
    private volatile String originId = "";
    private volatile String destinationId = "";
    /** 司机端上报的行程进度 0~1；-1 表示本次行程还没有进度数据 */
    private volatile double progressRatio = -1;
    private volatile long progressAt = 0;
    /** 进度新鲜度窗口：司机端约 30 秒报一次，超过 5 分钟没报视为行程已停 */
    private static final long PROGRESS_FRESH_MS = 5 * 60 * 1000;

    /** 司机端开始导航：登记一次行程启动（覆盖上一次），进度清零重新计。 */
    public synchronized void markStarted(String originId, String destinationId) {
        this.originId = originId == null ? "" : originId;
        this.destinationId = destinationId == null ? "" : destinationId;
        this.startedAt = System.currentTimeMillis();
        this.progressRatio = 0;
        this.progressAt = this.startedAt;
    }

    /** 司机端周期上报行程进度（导航中每 ~30 秒一次）。未开始导航时忽略。 */
    public synchronized void updateProgress(double ratio) {
        if (startedAt <= 0) {
            return;
        }
        this.progressRatio = Math.max(0, Math.min(1, ratio));
        this.progressAt = System.currentTimeMillis();
    }

    /**
     * 当前有效行程进度：行程进行中且进度新鲜才返回 0~1，否则 -1（= 不在途，
     * 方案对比按"还没出发"处理，不应拿陈旧进度去禁用方案）。
     */
    public double progress() {
        if (startedAt <= 0 || progressRatio < 0) {
            return -1;
        }
        return System.currentTimeMillis() - progressAt <= PROGRESS_FRESH_MS ? progressRatio : -1;
    }

    /** 清空信号（演示重置/司机退出导航）。 */
    public synchronized void reset() {
        this.startedAt = 0;
        this.originId = "";
        this.destinationId = "";
        this.progressRatio = -1;
        this.progressAt = 0;
    }

    public Map<String, Object> signal() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", startedAt > 0);
        m.put("startedAt", startedAt);
        m.put("originId", originId);
        m.put("destinationId", destinationId);
        m.put("progressRatio", progressRatio);
        m.put("progressAt", progressAt);
        return m;
    }
}
