package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>(nodes.size());
        for (RoadNode node : nodes) {
            int latIdx = nearestIndex(latitudes, node.getLatitude());
            int lonIdx = nearestIndex(longitudes, node.getLongitude());
            double tmp = read(merged, "TMP", lastTime, latIdx, lonIdx, 0);
            double rh = read(merged, "RH", lastTime, latIdx, lonIdx, 50);
            double pre = read(merged, "PRE", lastTime, latIdx, lonIdx, 0);
            double visKm = read(merged, "VIS", lastTime, latIdx, lonIdx, 20);
            double windU = read(merged, "WIN_U", lastTime, latIdx, lonIdx, 0);
            double windV = read(merged, "WIN_V", lastTime, latIdx, lonIdx, 0);
            double windKph = Math.hypot(windU, windV) * 3.6;
            // 气压取不到时填标准海压 1013：等价于"无低气压信号"，不触发台风判据
            double prs = read(merged, "PRS", lastTime, latIdx, lonIdx, 1013.0);
            out.add(new RealWeatherService.WeatherPoint(
                    node.getId(), node.getLatitude(), node.getLongitude(),
                    tmp, rh, pre, windKph, visKm * 1000.0, prs, fetchedAt
            ));
        }
        return out;
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
                    .header("Authorization", "Bearer " + token)
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
}
