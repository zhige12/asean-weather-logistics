package com.example.aseanweatherlogistics.service.waterway;

import com.example.aseanweatherlogistics.service.DecisionSandboxService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 运河通航观测数据接入层：把「数据源 → 禁航红线比对 → canal-block/recover 广播」
 * 串成与官方平台对接完全一致的管道。
 * <p>
 * 数据源经 {@code waterway.source} 配置切换（mock / official），切换只换
 * {@link WaterwayDataSource} 实现，判定与触达链路零改动——这正是答辩口径
 * "架构上已预留与调度平台的数据对接能力，比赛阶段用模拟数据验证决策逻辑"的落地。
 * <p>
 * 失败语义与支流实况联动同一原则：拉取失败**保持既有通航状态**，
 * 绝不把"没网/接口不可用"错报成"参数正常→解除禁航"。
 */
@Service
public class WaterwayDataService {

    private static final Logger log = LoggerFactory.getLogger(WaterwayDataService.class);

    private final MockWaterwayDataSource mockSource;
    private final OfficialWaterwayDataSource officialSource;
    private final DecisionSandboxService sandboxService;

    /** 当前生效数据源标识（配置 waterway.source，运行时状态接口可读） */
    private final String configuredSource;

    private volatile WaterwayObservation lastObservation;
    private volatile long lastFetchAt;
    private volatile String lastError;
    private volatile int fetchFailures = 0;

    public WaterwayDataService(MockWaterwayDataSource mockSource,
                               OfficialWaterwayDataSource officialSource,
                               DecisionSandboxService sandboxService,
                               @org.springframework.beans.factory.annotation.Value("${waterway.source:mock}")
                               String configuredSource) {
        this.mockSource = mockSource;
        this.officialSource = officialSource;
        this.sandboxService = sandboxService;
        this.configuredSource = configuredSource == null ? "mock" : configuredSource.trim();
    }

    /**
     * 定时拉取：仅 source=official 时启用（默认每 15 分钟，与水文站上报频率一致）。
     * mock 模式不轮询——避免定时把演示剧本手动注入的禁航值（如能见度900m）打回正常。
     */
    @Scheduled(initialDelayString = "${waterway.poll-initial-delay-ms:30000}",
               fixedRateString = "${waterway.poll-interval-ms:900000}")
    public void scheduledPoll() {
        if (!"official".equals(configuredSource)) {
            return;
        }
        pollOnce();
    }

    /** 立即执行一轮拉取（official 配置后可手动触发/调试用），返回接入状态。 */
    public synchronized Map<String, Object> pollOnce() {
        WaterwayDataSource source = "official".equals(configuredSource) ? officialSource : mockSource;
        try {
            WaterwayObservation obs = source.fetch();
            lastFetchAt = System.currentTimeMillis();
            lastError = null;
            fetchFailures = 0;
            ingest(obs);
        } catch (Exception e) {
            fetchFailures++;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("运河观测拉取失败（第 {} 次），保持既有通航状态不变: {}", fetchFailures, lastError);
        }
        return status();
    }

    /**
     * 演示核心入口：手动模拟一轮"官方平台回传"（如能见度 900m 触发禁航），
     * 走与定时拉取完全相同的 ingest 链路。参数 null 表示该项不注入（沿用基准值）。
     */
    public synchronized Map<String, Object> simulatePlatformPush(Double visibilityM, Double currentMs,
                                                                 Double waveHeightM, Double windMs) {
        mockSource.inject(visibilityM, currentMs, waveHeightM, windMs);
        WaterwayObservation obs = mockSource.fetch();
        lastFetchAt = System.currentTimeMillis();
        ingest(obs);
        return status();
    }

    /** 观测 → 红线比对（引擎/沙盘现成链路）。值无变化时跳过，不刷决策日志。 */
    private void ingest(WaterwayObservation obs) {
        if (obs.sameValues(lastObservation)) {
            lastObservation = obs;
            return;
        }
        lastObservation = obs;
        String trigger = ("official".equals(configuredSource) && officialSource.configured())
                ? "调度平台观测数据接入"
                : "运河观测数据接入（模拟平台推送）";
        // setWaterConditions 内部：写入引擎红线判定 → 状态翻转时 evaluate 广播
        // canal-block / canal-recover → 大屏换线卡 + 司机端弹替代路线
        sandboxService.setWaterConditions(obs.visibilityM(), obs.currentMs(),
                obs.waveHeightM(), obs.windMs(), trigger);
    }

    /** 接入层状态（供 GET /api/waterway/status 与答辩演示）。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configuredSource", configuredSource);
        m.put("officialApiConfigured", officialSource.configured());
        m.put("lastObservation", lastObservation == null ? null : observationMap(lastObservation));
        m.put("lastFetchAt", lastFetchAt);
        m.put("lastError", lastError);
        m.put("fetchFailures", fetchFailures);
        m.put("note", "official".equals(configuredSource) && officialSource.configured()
                ? "已按调度平台接口配置回源（字段映射以官方接口文档为准）"
                : "比赛阶段：官方平台接口经合作协议获取，当前以模拟推送验证同一接入链路");
        return m;
    }

    private static Map<String, Object> observationMap(WaterwayObservation obs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibilityM", obs.visibilityM());
        m.put("currentMs", obs.currentMs());
        m.put("waveHeightM", obs.waveHeightM());
        m.put("windMs", obs.windMs());
        m.put("station", obs.station());
        m.put("source", obs.source());
        m.put("observedAt", obs.observedAt());
        return m;
    }
}
