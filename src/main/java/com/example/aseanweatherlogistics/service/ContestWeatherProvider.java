package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.util.HttpUtil;
import com.example.aseanweatherlogistics.util.SecretCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 比赛官方气象数据接口（CRA40 实况）适配：
 * 按 bbox + variables + 指定时次拉取格点，再映射到业务节点。
 * 需要配置 weather.real.base-url 与 weather.real.token，未配置时 isConfigured() 为 false。
 */
@Service
public class ContestWeatherProvider implements WeatherProvider {

    public static final String ID = "contest-observation";

    private static final String USER_AGENT = "asean-weather-logistics/1.0 (contest)";
    private static final List<String> OBS_PRIMARY_VARS = List.of("TMP", "RH", "PRE");
    private static final List<String> OBS_SECONDARY_VARS = List.of("WIN_U", "WIN_V", "VIS");
    /** 气压单独一批：接口单次最多 3 变量，前两批已满；PRS 用于台风/强对流判据 */
    private static final List<String> OBS_PRESSURE_VARS = List.of("PRS");

    @Value("${weather.real.base-url:}")
    private String baseUrl;
    @Value("${weather.real.token:}")
    private String token;
    @Value("${weather.real.observation-time:202412312000}")
    private String observationTime;
    @Value("${weather.real.bbox-padding-deg:0.0}")
    private double bboxPaddingDeg;
    @Value("${weather.real.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;
    /** 缓存 TTL（与 RealWeatherService 同一份配置）：上一轮格点超过这个时长就不再拿来补数 */
    @Value("${weather.real.ttl-minutes:10}")
    private long ttlMinutes;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 三批气象请求并行拉取线程池：官方接口单次最多 3 个变量，7 个变量必须分三批；
     * 并发后总耗时≈单批（约 10s）而非三批相加（约 30s），避免现场“刷新气象”让评委干等。
     * <p>
     * 合规：三批是同一轮取数的并发分片，仍落在 {@code RealWeatherService} 的
     * 60s 最小回源间隔内，不会因加变量而提高对上游的访问频度。
     */
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "contest-weather-fetch");
        t.setDaemon(true);
        return t;
    });

    /**
     * 上一轮真实回源取回的格点快照（纯内存、整体替换，读侧不加锁）。
     * <p>存在的理由：调度员换一条经芒街的线，芒街不在已有缓存里，而上游正被节流挡着——
     * 实况接口按固定时次取数、内容未变会指数退避（实测 240 秒窗口），守护每 60 秒的回源还会不断
     * 把窗口往后顶，实测新口岸 9 分钟都进不了打分点。格点已经在这台机器内存里了，
     * 直接给它插值一次不用打上游。</p>
     */
    private volatile LastGrid lastGrid;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "CRA40";
    }

    @Override
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
    }

    @Override
    public List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
        return fetchObservation(nodes);
    }

    /**
     * 节流窗口内给新进来的口岸补数：拿上一轮已下载的格点在内存里插值，**不发任何请求**。
     * 只补落在那张格点覆盖范围内的点（见 {@link #inGrid}）：范围外取最近边界格点等于
     * 把几百公里外的读数当成这个口岸的天气，那种数据不如没有，等上游窗口打开真拉一次。</p>
     */
    @Override
    public List<RealWeatherService.WeatherPoint> interpolateFromLastFetch(List<RoadNode> nodes) {
        LastGrid g = lastGrid;
        if (g == null || nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        // 格点比缓存 TTL 还旧就不再补：免得把几小时前的实况当“现在”画在屏幕上
        if (System.currentTimeMillis() - g.fetchedAt() >= ttlMinutes * 60_000L) {
            return List.of();
        }
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>();
        for (RoadNode node : nodes) {
            if (inGrid(g, node)) {
                out.add(sampleNode(g, node));
            }
        }
        return out;
    }

    /** 节点是否落在这张格点场的经纬度范围内 */
    private static boolean inGrid(LastGrid g, RoadNode node) {
        double minLat = Collections.min(g.latitudes());
        double maxLat = Collections.max(g.latitudes());
        double minLon = Collections.min(g.longitudes());
        double maxLon = Collections.max(g.longitudes());
        return node.getLatitude() >= minLat && node.getLatitude() <= maxLat
                && node.getLongitude() >= minLon && node.getLongitude() <= maxLon;
    }

    private List<RealWeatherService.WeatherPoint> fetchObservation(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty() || !isConfigured()) {
            return List.of();
        }
        Bbox bbox = buildBbox(nodes);
        // 主批次(TMP/RH/PRE) 与 次批次(WIN_U/VIS) 并行拉取（两批各≤3 变量，符合官方单次上限）
        CompletableFuture<ObservationResponse> primaryFuture =
                CompletableFuture.supplyAsync(() -> queryObservations(OBS_PRIMARY_VARS, bbox), fetchExecutor);
        CompletableFuture<ObservationResponse> secondaryFuture =
                CompletableFuture.supplyAsync(() -> queryObservations(OBS_SECONDARY_VARS, bbox), fetchExecutor);
        CompletableFuture<ObservationResponse> pressureFuture =
                CompletableFuture.supplyAsync(() -> queryObservations(OBS_PRESSURE_VARS, bbox), fetchExecutor);
        try {
            CompletableFuture.allOf(primaryFuture, secondaryFuture, pressureFuture)
                    .get(requestTimeoutSeconds + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 任一批超时/异常不阻塞另一批：下方用 getNow(null) 各取已得结果
            primaryFuture.cancel(true);
            secondaryFuture.cancel(true);
            pressureFuture.cancel(true);
        }
        ObservationResponse p1 = primaryFuture.getNow(null);
        ObservationResponse p2 = secondaryFuture.getNow(null);
        ObservationResponse p3 = pressureFuture.getNow(null);
        // 仅主批次（含熔断核心 PRE、温度 TMP、湿度 RH）为硬性必需；
        // 次批次（风/能见度）失败时按默认值降级，不因次要变量缺失而整条链路空返回。
        if (p1 == null) {
            return List.of();
        }
        if (p1.latitudes().isEmpty() || p1.longitudes().isEmpty() || p1.times().isEmpty()) {
            return List.of();
        }
        List<Double> latitudes = p1.latitudes();
        List<Double> longitudes = p1.longitudes();
        int lastTime = p1.times().size() - 1;
        Map<String, VariableGrid> merged = new HashMap<>(p1.data());
        if (p2 != null) {
            merged.putAll(p2.data());
        }
        if (p3 != null) {
            merged.putAll(p3.data());
        }
        long fetchedAt = System.currentTimeMillis();
        // 把这张格点存下来：下一轮若被节流挡住、而路线又多了新口岸，就靠它在内存里补数
        LastGrid grid = new LastGrid(latitudes, longitudes, Map.copyOf(merged), lastTime, fetchedAt);
        this.lastGrid = grid;
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>(nodes.size());
        for (RoadNode node : nodes) {
            out.add(sampleNode(grid, node));
        }
        return out;
    }

    /**
     * 把一张格点场按最近网格点取到单个节点上（真实回源与节流窗口内补数走同一个取值规则，
     * 不会因为“走的分支不同”而给同一个节点两套数字）。
     */
    private RealWeatherService.WeatherPoint sampleNode(LastGrid g, RoadNode node) {
        Map<String, VariableGrid> merged = g.grids();
        int lastTime = g.timeIndex();
        int latIdx = nearestIndex(g.latitudes(), node.getLatitude());
        int lonIdx = nearestIndex(g.longitudes(), node.getLongitude());
        double tmp = read(merged, "TMP", lastTime, latIdx, lonIdx, 0);
        double rh = read(merged, "RH", lastTime, latIdx, lonIdx, 50);
        double pre = read(merged, "PRE", lastTime, latIdx, lonIdx, 0);
        double visKm = read(merged, "VIS", lastTime, latIdx, lonIdx, 20);
        double windU = read(merged, "WIN_U", lastTime, latIdx, lonIdx, 0);
        double windV = read(merged, "WIN_V", lastTime, latIdx, lonIdx, 0);
        double windKph = Math.hypot(windU, windV) * 3.6;
        // 气压取不到时填标准海压 1013：等价于"无低气压信号"，不触发台风判据
        double prs = read(merged, "PRS", lastTime, latIdx, lonIdx, 1013.0);
        return new RealWeatherService.WeatherPoint(
                node.getId(), node.getLatitude(), node.getLongitude(),
                tmp, rh, pre, windKph, visKm * 1000.0, prs, g.fetchedAt()
        );
    }

    private ObservationResponse queryObservations(List<String> variables, Bbox bbox) {
        try {
            String endpoint = HttpUtil.joinUrl(baseUrl, "/api/v1/observations");
            Map<String, String> params = Map.of(
                    "variables", String.join(",", variables),
                    "time_start", observationTime,
                    "time_end", observationTime,
                    "bbox", bbox.toParam()
            );
            String url = endpoint + "?" + HttpUtil.encodeQuery(params);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + SecretCodec.resolve(token))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            return parseObservation(resp.body(), variables);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private ObservationResponse parseObservation(String body, List<String> variables) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<String> times = toStringList(root.path("times"));
        JsonNode coordinates = root.path("coordinates");
        List<Double> latitudes = toDoubleList(coordinates.path("latitude"));
        List<Double> longitudes = toDoubleList(coordinates.path("longitude"));
        JsonNode dataNode = root.path("data");
        Map<String, VariableGrid> grids = new HashMap<>();
        for (String v : variables) {
            JsonNode vn = dataNode.path(v);
            if (vn.isMissingNode() || vn.isNull()) {
                continue;
            }
            List<Integer> shape = toIntList(vn.path("shape"));
            if (shape.size() != 3) {
                continue;
            }
            List<Double> values = toDoubleListAllowNull(vn.path("values"));
            grids.put(v, new VariableGrid(shape.get(0), shape.get(1), shape.get(2), values));
        }
        return new ObservationResponse(times, latitudes, longitudes, grids);
    }

    private double read(Map<String, VariableGrid> grids, String variable, int t, int lat, int lon, double fallback) {
        VariableGrid grid = grids.get(variable);
        if (grid == null) {
            return fallback;
        }
        double v = grid.valueAt(t, lat, lon);
        return Double.isFinite(v) ? v : fallback;
    }

    private int nearestIndex(List<Double> values, double target) {
        int idx = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.size(); i++) {
            double d = Math.abs(values.get(i) - target);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }

    private Bbox buildBbox(List<RoadNode> nodes) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (RoadNode node : nodes) {
            minLon = Math.min(minLon, node.getLongitude());
            minLat = Math.min(minLat, node.getLatitude());
            maxLon = Math.max(maxLon, node.getLongitude());
            maxLat = Math.max(maxLat, node.getLatitude());
        }
        minLon = Math.max(90.0, minLon - bboxPaddingDeg);
        minLat = Math.max(-10.0, minLat - bboxPaddingDeg);
        maxLon = Math.min(170.0, maxLon + bboxPaddingDeg);
        maxLat = Math.min(30.0, maxLat + bboxPaddingDeg);
        return new Bbox(minLon, minLat, maxLon, maxLat);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            if (!n.isNull()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private List<Integer> toIntList(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            out.add(n.asInt());
        }
        return out;
    }

    private List<Double> toDoubleList(JsonNode node) {
        List<Double> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            if (n.isNumber()) {
                out.add(n.asDouble());
            }
        }
        return out;
    }

    private List<Double> toDoubleListAllowNull(JsonNode node) {
        List<Double> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            out.add(n.isNumber() ? n.asDouble() : null);
        }
        return out;
    }

    private record Bbox(double minLon, double minLat, double maxLon, double maxLat) {
        String toParam() {
            return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", minLon, minLat, maxLon, maxLat);
        }
    }

    private record VariableGrid(int timeSize, int latitudeSize, int longitudeSize, List<Double> values) {
        double valueAt(int t, int lat, int lon) {
            if (t < 0 || lat < 0 || lon < 0 || t >= timeSize || lat >= latitudeSize || lon >= longitudeSize) {
                return Double.NaN;
            }
            int idx = (t * latitudeSize + lat) * longitudeSize + lon;
            if (idx < 0 || idx >= values.size()) {
                return Double.NaN;
            }
            Double v = values.get(idx);
            return v == null ? Double.NaN : v;
        }
    }

    private record ObservationResponse(List<String> times,
                                       List<Double> latitudes,
                                       List<Double> longitudes,
                                       Map<String, VariableGrid> data) {
    }

    /** 上一轮取回的整张格点场：经纬度轴 + 各变量网格 + 取的是哪个时次 + 本机拿到它的时刻 */
    private record LastGrid(List<Double> latitudes,
                            List<Double> longitudes,
                            Map<String, VariableGrid> grids,
                            int timeIndex,
                            long fetchedAt) {
    }
}
