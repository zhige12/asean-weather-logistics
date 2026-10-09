package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    /**
     * 跨数据源最小回源间隔（秒）：节流窗口按源各自计算后，切到另一个上游不该被上一个源挡住，
     * 但两次「任意源」真实请求之间仍要留这个下限，防止来回切源把每个源的窗口变成摆设。
     */
    @Value("${weather.real.min-source-switch-gap-seconds:5}")
    private long minSourceSwitchGapSeconds;

    private final WeatherSourceService sourceService;
    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<>();

    /** 最近一次成功拉取使用的数据源：contest-observation / open-meteo / simulated / none */
    private volatile String lastSource = "none";
    /** 当前展示数据实际来自哪个源（可能是上一个源留下的缓存，界面要如实标注） */
    private volatile String servedSource = "none";
    /** 展示中的数据是否为本次真实回源所得（false = 节流窗口内的缓存） */
    private volatile boolean servedFresh = false;
    /** 上一次任意数据源的真实回源时刻（跨源最小间隔用） */
    private volatile long lastAnyRequestAtMs = 0;
    /**
     * 每个数据源独立的节流 / 熔断状态。
     * <p>
     * 上游是不同的接口（官方 CRA40 与公网 Open-Meteo），一个源的请求窗口不该让另一个源「切了没反应」，
     * 也不该因为一个 Token 失败就把另一个源一起熔断——所以状态按源存，各自有最小间隔与静默期。
     */
    private static final class SourceState {
        /** 该源上一次真实回源时刻 */
        volatile long lastRequestAtMs = 0;
        /** 该源连续失败次数 */
        volatile int consecutiveFailures = 0;
        /** 该源的熔断静默截止时刻（>now 表示静默中） */
        volatile long circuitOpenUntilMs = 0;
        /** 该源上次 resetThrottle 生效时刻：限制切源重置频度，防止反复切源绕过节流 */
        volatile long lastResetAtMs = 0;
        /**
         * 该源上一次「真的取到数据」的时刻。
         * <p>
         * 与 lastRequestAtMs 分开记：后者是发起尝试的时刻，被节流挡掉时不会更新；
         * 而 servedFresh 这类瞬时标志会被随后任意一次缓存读掉，从外部根本抓不到它亮过。
         * 要回答「切了这个源之后到底取到没取到」，只有这个单调的时间戳靠得住（界面与合规脚本都读它）。
         */
        volatile long lastSuccessAtMs = 0;
    }

    private final Map<String, SourceState> states = new ConcurrentHashMap<>();
    // ===== 合规审计计数（真实回源次数等，供 /api/weather/rate-stats 查看） =====
    private volatile long cntUpstreamRequests = 0;
    private volatile long cntCacheServed = 0;
    private volatile long cntThrottleBlocked = 0;
    private volatile long cntCircuitOpens = 0;

    public RealWeatherService(WeatherSourceService sourceService) {
        this.sourceService = sourceService;
    }

    public String lastSource() {
        return lastSource;
    }

    /** 当前展示数据的真实来源（含缓存来源），供界面写清「这份数字到底是谁家的」 */
    public String servedSource() {
        return servedSource;
    }

    /** 展示中的数据是不是本次真实回源拿到的（false 则是节流窗口内的缓存） */
    public boolean servedFresh() {
        return servedFresh;
    }

    private SourceState stateOf(String id) {
        return states.computeIfAbsent(id, k -> new SourceState());
    }

    /**
     * 单点天气（兼容现有前端/算法字段）。
     * <p>
     * {@code pressureHpa} 为海平面气压，用于台风/强对流判据（低气压 + 大风 才是台风，
     * 单看大风会把雷暴阵风误判成台风）。取不到数据时填标准海压 1013，即"不触发任何判据"。
     */
    public record WeatherPoint(String nodeId, double latitude, double longitude,
                               double temperatureC, double humidityPct, double precipitationMm,
                               double windKph, double visibilityM, double pressureHpa, long fetchedAt) {

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
                    "pressureHpa", pressureHpa,
                    "fetchedAt", fetchedAt
            );
        }
    }

    private record CachedWeather(WeatherPoint point, long fetchedAt, String source) {
    }

    /**
     * 按当前数据源拉取节点天气；失败降级缓存。
     * <p>
     * 合规保护（比赛方要求：禁止暴力访问）：
     * <ol>
     *   <li>最小请求间隔：每个数据源两次真实网络请求之间至少间隔 minRequestIntervalSeconds，
     *       未到期直接返回缓存，避免路径规划/定时刷新把上游打爆；</li>
     *   <li>跨源最小间隔：即使切到另一个数据源，两次任意回源之间也至少间隔
     *       minSourceSwitchGapSeconds，切源不等于拿到免节流额度；</li>
     *   <li>熔断：某个源连续失败（含 401 Token 无效）达到阈值后静默一段时间，
     *       不重试轰炸，期间该源一律返回缓存（不影响其它源）。</li>
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
        String sourceId = provider.id();
        SourceState st = stateOf(sourceId);
        long now = System.currentTimeMillis();
        // 该源熔断窗口内不发起任何请求（Token 无效/断网时不重试轰炸）
        if (now < st.circuitOpenUntilMs) {
            lastSource = "none";
            cntThrottleBlocked++;
            return cachedFor(nodes);
        }
        // 该源最小请求间隔未到 → 直接用缓存，不发请求
        if (now - st.lastRequestAtMs < minRequestIntervalMs()) {
            cntThrottleBlocked++;
            return cachedFor(nodes);
        }
        // 跨源保护：刚向别的源回过源，立刻切过来也要等一下（防反复切源刷上游）
        if (now - lastAnyRequestAtMs < minSourceSwitchGapMs()) {
            cntThrottleBlocked++;
            return cachedFor(nodes);
        }
        st.lastRequestAtMs = now;
        lastAnyRequestAtMs = now;
        List<WeatherPoint> fresh = provider.fetchForNodes(nodes);
        cntUpstreamRequests++;
        if (fresh != null && !fresh.isEmpty()) {
            lastSource = sourceId;
            servedSource = sourceId;
            servedFresh = true;
            st.lastSuccessAtMs = now;
            st.consecutiveFailures = 0;
            st.circuitOpenUntilMs = 0;
            for (WeatherPoint p : fresh) {
                cache.put(p.nodeId(), new CachedWeather(p, p.fetchedAt(), sourceId));
            }
            return fresh;
        }
        lastSource = "none";
        registerFailure(st, now);
        return cachedFor(nodes);
    }

    /** 记录一次失败：某源连续失败达阈值则打开该源的熔断窗口（静默期，不重试） */
    private void registerFailure(SourceState st, long now) {
        st.consecutiveFailures++;
        if (st.consecutiveFailures >= Math.max(1, circuitFailureThreshold)) {
            st.circuitOpenUntilMs = now + circuitOpenSeconds() * 1000L;
            st.consecutiveFailures = 0;
            cntCircuitOpens++;
        }
    }

    /**
     * 切换数据源后清掉「新选这个源自己」的失败计数与熔断窗口，使它有机会立刻被尝试一次
     * （否则上一轮 Token 失败留下的静默期会让切源看起来"切了没反应"）。
     * <p>
     * 合规防护：只清该源的失败/静默状态，**不**清它的回源时间戳——每个源仍有自己的
     * min-request-interval 与跨源 min-source-switch-gap 两道闸，反复切源仍是变相暴力访问不来的。
     * 同一数据源的重复重置另外限频（两次生效至少间隔 min-request-interval）。
     */
    public synchronized void resetThrottle(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        SourceState st = stateOf(sourceId);
        long now = System.currentTimeMillis();
        if (now - st.lastResetAtMs < minRequestIntervalMs()) {
            return; // 限频：窗口内的重复重置直接忽略
        }
        st.lastResetAtMs = now;
        st.consecutiveFailures = 0;
        st.circuitOpenUntilMs = 0;
    }

    /** 频度自检：审计计数与守护参数（不含密钥/Token），供合规检查脚本断言节流生效 */
    public Map<String, Object> rateStats() {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upstreamRequests", cntUpstreamRequests);
        m.put("throttleBlocked", cntThrottleBlocked);
        m.put("circuitOpens", cntCircuitOpens);
        m.put("minIntervalSeconds", Math.max(1, minRequestIntervalSeconds));
        m.put("minSourceSwitchGapSeconds", Math.max(1, minSourceSwitchGapSeconds));
        m.put("secondsSinceLastUpstream", lastAnyRequestAtMs == 0 ? null : (now - lastAnyRequestAtMs) / 1000);
        m.put("circuitOpen", selectedSourceCircuitOpen(now));
        m.put("consecutiveFailures", selectedSourceFailures());
        m.put("lastSource", lastSource);
        m.put("servedSource", servedSource);
        m.put("servedFresh", servedFresh);
        m.put("selectedSource", sourceService.source());
        // 逐个数据源的窗口/静默状态：说明“切源为何还没回源”这类问题时能自查
        Map<String, Object> perSource = new LinkedHashMap<>();
        for (Map.Entry<String, SourceState> e : states.entrySet()) {
            SourceState st = e.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("secondsSinceLastUpstream", st.lastRequestAtMs == 0 ? null : (now - st.lastRequestAtMs) / 1000);
            // 该源最近一次成功取数距今多久（null = 从来没成功拉过）：切源是否真的生效看这个
            one.put("secondsSinceLastSuccess", st.lastSuccessAtMs == 0 ? null : (now - st.lastSuccessAtMs) / 1000);
            one.put("circuitOpen", now < st.circuitOpenUntilMs);
            one.put("consecutiveFailures", st.consecutiveFailures);
            perSource.put(e.getKey(), one);
        }
        m.put("sources", perSource);
        return m;
    }

    private boolean selectedSourceCircuitOpen(long now) {
        WeatherProvider p = sourceService.current();
        return p != null && now < stateOf(p.id()).circuitOpenUntilMs;
    }

    private int selectedSourceFailures() {
        WeatherProvider p = sourceService.current();
        return p == null ? 0 : stateOf(p.id()).consecutiveFailures;
    }

    private long minRequestIntervalMs() {
        return Math.max(1, minRequestIntervalSeconds) * 1000L;
    }

    /** 跨源最小回源间隔：不超过本源的 min-request-interval，默认 5 秒 */
    private long minSourceSwitchGapMs() {
        long configured = Math.max(1, minSourceSwitchGapSeconds) * 1000L;
        return Math.min(configured, minRequestIntervalMs());
    }

    private long circuitOpenSeconds() {
        return Math.max(1, circuitOpenSeconds);
    }

    /** 当前未过期的缓存（按给定节点过滤），并记下这批数据真正来自哪个源 */
    private List<WeatherPoint> cachedFor(List<RoadNode> nodes) {
        List<WeatherPoint> out = new ArrayList<>();
        String origin = null;
        for (RoadNode node : nodes) {
            CachedWeather c = cache.get(node.getId());
            if (c != null && !isExpired(c.fetchedAt())) {
                out.add(c.point());
                if (origin == null) {
                    origin = c.source();
                }
            }
        }
        if (!out.isEmpty()) {
            cntCacheServed++;
            // 界面上这份数字可能是上一个数据源留下的缓存，不能假装是刚从这个源拉的
            servedSource = origin == null ? "none" : origin;
        } else {
            servedSource = "none";
        }
        servedFresh = false;
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
