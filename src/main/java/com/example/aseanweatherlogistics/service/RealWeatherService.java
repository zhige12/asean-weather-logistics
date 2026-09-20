package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 气象数据源路由（对外接口保持不变，RouteService / WeatherController 无需改动）：
 * 按 {@link WeatherSourceService} 当前选择的数据源拉取节点天气，
 * 成功则写入缓存，失败自动降级到未过期的缓存。
 * <p>
 * 可选数据源：比赛官方 CRA40 实况 / Open-Meteo 公网真实气象 / 离线模拟。
 */
@Service
public class RealWeatherService {

    @Value("${weather.real.ttl-minutes:10}")
    private long ttlMinutes;
    /** 最小请求间隔（秒）：任何数据源两次真实请求之间至少间隔这么久，防止暴力访问 */
    @Value("${weather.real.min-request-interval-seconds:60}")
    private long minRequestIntervalSeconds;
    /** 连续失败达到该次数后打开熔断窗口 */
    @Value("${weather.real.circuit-failure-threshold:3}")
    private long circuitFailureThreshold;
    /** 熔断静默时长（秒）：期间不发起任何请求，Token 无效(401)时不重试轰炸 */
    @Value("${weather.real.circuit-open-seconds:300}")
    private long circuitOpenSeconds;

    private final WeatherSourceService sourceService;
    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<>();

    /** 最近一次成功拉取使用的数据源：contest-observation / open-meteo / simulated / none */
    private volatile String lastSource = "none";
    /** 上一次真实网络请求时间戳（最小间隔节流用） */
    private volatile long lastRequestAtMs = 0;
    /** 连续失败计数 */
    private long consecutiveFailures = 0;
    /** 熔断窗口截止时间戳（>now 表示静默中） */
    private volatile long circuitOpenUntilMs = 0;

    public RealWeatherService(WeatherSourceService sourceService) {
        this.sourceService = sourceService;
    }

    public String lastSource() {
        return lastSource;
    }

    /** 单点天气（兼容现有前端/算法字段） */
    public record WeatherPoint(String nodeId, double latitude, double longitude,
                               double temperatureC, double humidityPct, double precipitationMm,
                               double windKph, double visibilityM, long fetchedAt) {

        public Map<String, Object> toMap() {
            return Map.of(
                    "nodeId", nodeId,
                    "latitude", latitude,
                    "longitude", longitude,
                    "temperatureC", temperatureC,
                    "humidityPct", humidityPct,
                    "precipitationMm", precipitationMm,
                    "windKph", windKph,
                    "visibilityM", visibilityM,
                    "fetchedAt", fetchedAt
            );
        }
    }

    private record CachedWeather(WeatherPoint point, long fetchedAt) {
    }

    /**
     * 按当前数据源拉取节点天气；失败降级缓存。
     * <p>
     * 合规保护（比赛方要求：禁止暴力访问）：
     * <ol>
     *   <li>最小请求间隔：任何数据源两次真实网络请求之间至少间隔 minRequestIntervalSeconds，
     *       未到期直接返回缓存，避免路径规划/定时刷新把上游打爆；</li>
     *   <li>熔断：连续失败（含 401 Token 无效）达到阈值后静默一段时间，
     *       不重试轰炸，期间一律返回缓存。</li>
     * </ol>
     */
    public synchronized List<WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        WeatherProvider provider = sourceService.current();
        if (provider == null || !provider.isConfigured()) {
            lastSource = "none";
            return cachedFor(nodes);
        }
        long now = System.currentTimeMillis();
        // 熔断窗口内不发起任何请求（Token 无效/断网时不重试轰炸）
        if (now < circuitOpenUntilMs) {
            lastSource = "none";
            return cachedFor(nodes);
        }
        // 最小请求间隔未到 → 直接用缓存，不发请求
        if (now - lastRequestAtMs < minRequestIntervalMs()) {
            return cachedFor(nodes);
        }
        lastRequestAtMs = now;
        List<WeatherPoint> fresh = provider.fetchForNodes(nodes);
        if (fresh != null && !fresh.isEmpty()) {
            lastSource = provider.id();
            consecutiveFailures = 0;
            circuitOpenUntilMs = 0;
            for (WeatherPoint p : fresh) {
                cache.put(p.nodeId(), new CachedWeather(p, p.fetchedAt()));
            }
            return fresh;
        }
        lastSource = "none";
        registerFailure(now);
        return cachedFor(nodes);
    }

    /** 记录一次失败：连续失败达阈值则打开熔断窗口（静默期，不重试） */
    private void registerFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= Math.max(1, circuitFailureThreshold)) {
            circuitOpenUntilMs = now + circuitOpenSeconds() * 1000L;
            consecutiveFailures = 0;
        }
    }

    /**
     * 切换数据源后重置节流与熔断状态，使新数据源可以立即拉取一次
     * （否则切源后仍被上一个源的节流窗口挡住，用户体验为"切了没反应"）。
     */
    public synchronized void resetThrottle() {
        lastRequestAtMs = 0;
        consecutiveFailures = 0;
        circuitOpenUntilMs = 0;
    }

    private long minRequestIntervalMs() {
        return Math.max(1, minRequestIntervalSeconds) * 1000L;
    }

    private long circuitOpenSeconds() {
        return Math.max(1, circuitOpenSeconds);
    }

    /** 当前未过期的缓存（按给定节点过滤） */
    private List<WeatherPoint> cachedFor(List<RoadNode> nodes) {
        List<WeatherPoint> out = new ArrayList<>();
        for (RoadNode node : nodes) {
            CachedWeather c = cache.get(node.getId());
            if (c != null && !isExpired(c.fetchedAt())) {
                out.add(c.point());
            }
        }
        return out;
    }

    public Map<String, WeatherPoint> cachedPoints() {
        Map<String, WeatherPoint> out = new ConcurrentHashMap<>();
        cache.forEach((k, v) -> {
            if (!isExpired(v.fetchedAt())) {
                out.put(k, v.point());
            }
        });
        return out;
    }

    public boolean isEnabled() {
        WeatherProvider p = sourceService.current();
        return p != null && p.isConfigured();
    }

    private boolean isExpired(long fetchedAt) {
        return System.currentTimeMillis() - fetchedAt >= ttlMinutes * 60_000L;
    }
}
