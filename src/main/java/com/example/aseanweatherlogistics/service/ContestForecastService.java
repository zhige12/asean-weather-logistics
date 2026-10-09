package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * GOWFS 三小时预报接入（官方接口 {@code GET /api/v1/forecasts/3h}）：
 * 为决策沙盘提供「未来 6h 累计降雨」的<b>真实</b>数据源，取代原先手填的剧本值。
 * <p>
 * 为什么单独成一个服务而不并入 {@link RealWeatherService}：
 * <ul>
 *   <li>回源路径不同（{@code /forecasts/3h} vs {@code /observations}），参数语义也不同
 *       （run_time + forecast_hours，而非 time_start/time_end）；</li>
 *   <li>预报 3 小时才更新一次，节流窗口应远长于实况（默认 30 分钟），
 *       若共用实况的 60s 窗口会互相挤占回源额度，且白白多打上游；</li>
 *   <li>熔断按通道各自计算：预报 401 不该把实况链路一起封掉，反之亦然。</li>
 * </ul>
 * <p>
 * 合规（比赛方：禁止暴力访问）：本服务自带三道闸——最小回源间隔、连续失败熔断、
 * 401 立即熔断（Token 失效时静默，不重试轰炸）。Audit 计数经 {@link #status()} 暴露。
 */
@Service
public class ContestForecastService {

    private static final String USER_AGENT = "asean-weather-logistics/1.0 (gowfs-forecast)";
    /** 单次变量上限（接口限制：最多 3 个） */
    private static final int MAX_VARIABLES = 3;
    /**
     * 向预报接口请求的要素：取满开放列表。
     * PRE 驱动熔断判定，其余用于界面展示官方气象参数。
     */
    private static final List<String> REQUEST_VARIABLES = List.of(
            "PRE", "TMP", "TMAX", "TMIN", "RH", "PRS", "VIS", "TCC", "LCC", "WIN_U", "WIN_V");
    /** 单次时效上限（接口限制：最多 5 个） */
    private static final int MAX_FORECAST_HOURS = 5;
    /** 允许查询范围（与实况同一份约束，见接口文档 3.2） */
    private static final double BBOX_MIN_LON = 90.0;
    private static final double BBOX_MIN_LAT = -10.0;
    private static final double BBOX_MAX_LON = 170.0;
    private static final double BBOX_MAX_LAT = 30.0;
    /** bbox 外扩：保证断面点位一定落到格点上（0.25° 网格，外扩半格足够） */
    private static final double BBOX_PADDING_DEG = 0.25;

    @Value("${weather.real.base-url:}")
    private String baseUrl;
    @Value("${weather.real.token:}")
    private String token;
    @Value("${weather.forecast.enabled:true}")
    private boolean enabled;
    /** 最小回源间隔（秒）：预报 3h 更新一次，默认 30 分钟足够，且不与实况抢额度 */
    @Value("${weather.forecast.min-request-interval-seconds:1800}")
    private long minRequestIntervalSeconds;
    /** 起报时次（YYYYMMDDHHMM），只允许 08:00 / 20:00 */
    @Value("${weather.forecast.run-time:202412312000}")
    private String runTime;
    /** 参与累计的预报时效（小时），逗号分隔；3,6 即"未来 6h" */
    @Value("${weather.forecast.hours:3,6}")
    private String hoursRaw;
    @Value("${weather.forecast.timeout-seconds:20}")
    private long timeoutSeconds;
    /** 连续失败达该次数后打开熔断窗口 */
    @Value("${weather.forecast.circuit-failure-threshold:3}")
    private long circuitFailureThreshold;
    /** 熔断静默时长（秒）：期间一律返回上次结果，不重试轰炸 */
    @Value("${weather.forecast.circuit-open-seconds:600}")
    private long circuitOpenSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 变量批次并行拉取线程池：接口单次最多 3 个变量，取满 11 个要素需 4 批。
     * 并行后总耗时≈单批，且 4 批同属一轮取数，仍受同一个 60s/30min 节流窗口约束，
     * 不会因要素变多而提高对上游的访问频度。
     */
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "gowfs-forecast-fetch");
        t.setDaemon(true);
        return t;
    });

    // ===== 合规守护状态（只限制回源频度，不改变取数语义） =====
    private volatile long lastRequestAtMs = 0L;
    private volatile long circuitUntilMs = 0L;
    private volatile long consecutiveFailures = 0L;
    private volatile ForecastResult lastResult = null;
    private volatile long lastResultAtMs = 0L;
    private volatile long cntUpstream = 0L;
    private volatile long cntThrottled = 0L;
    private volatile long cntCircuitOpens = 0L;

    /** 预报取数点位（断面代表点） */
    public record ForecastPoint(String id, String name, double lat, double lon) {
    }

    /**
     * 一次预报取数结果。
     * <p>
     * 单位假设：GOWFS 3h 预报的 PRE 视为<b>该时效所对应 3 小时时段内的降水量(mm)</b>，
     * 因此「未来 6h 累计」= 3h 时效值 + 6h 时效值。逐时效原始值一并在 {@code hourlyMm}
     * 中返回，便于现场核对上游口径（若上游改为瞬时雨强，可据此快速修正换算）。
     */
    public record ForecastResult(boolean dataAvailable,
                                 String runTime,
                                 List<Integer> hours,
                                 Map<String, Double> rain6hMm,
                                 Map<String, List<Double>> hourlyMm,
                                 /** 各点位在首个时效上的全部可用要素（变量名 → 值），供界面展示 */
                                 Map<String, Map<String, Double>> pointElements,
                                 /** 风向中文方位（断面id → 方位名），与 pointElements 分开存因为不是数值 */
                                 Map<String, String> windDirectionText,
                                 String sourceLabel,
                                 String message,
                                 boolean servedFresh,
                                 long fetchedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataAvailable", dataAvailable);
            m.put("runTime", runTime);
            m.put("hours", hours);
            m.put("rain6hMm", rain6hMm);
            m.put("hourlyMm", hourlyMm);
            m.put("pointElements", pointElements);
            m.put("windDirectionText", windDirectionText);
            m.put("sourceLabel", sourceLabel);
            m.put("message", message);
            m.put("servedFresh", servedFresh);
            m.put("fetchedAt", fetchedAt);
            return m;
        }
    }

    public boolean isConfigured() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && token != null && !token.isBlank();
    }

    /**
     * 拉取给定点位的未来 6h 累计降雨。
     * 未配置 / 节流窗口内 / 熔断中 / 上游失败时返回 {@code dataAvailable=false}，
     * 由调用方决定是否沿用剧本值——绝不让"没网"被误报成"无降雨"。
     */
    public synchronized ForecastResult fetchRain6h(List<ForecastPoint> points) {
        if (points == null || points.isEmpty()) {
            return unavailable("未指定预报点位", null);
        }
        if (!isConfigured()) {
            return unavailable("GOWFS 预报通道未启用（缺 base-url/token 或已关闭）", null);
        }
        List<Integer> hours = parseHours(hoursRaw);
        if (hours.isEmpty()) {
            return unavailable("预报时效配置非法（需为 3–168 内 3 的倍数，最多 5 个）", null);
        }
        long now = System.currentTimeMillis();
        // 1) 熔断静默期：一律不回源（含 401 Token 失效场景，严禁重试轰炸）
        if (now < circuitUntilMs) {
            cntThrottled++;
            return cached(unavailable("预报通道熔断静默中（"
                    + Math.max(1, (circuitUntilMs - now) / 1000) + "s 后恢复）", lastResult));
        }
        // 2) 最小回源间隔未到：直接返回上次结果
        if (lastResult != null && now - lastRequestAtMs < minRequestIntervalMs()) {
            cntThrottled++;
            return cached(lastResult);
        }
        lastRequestAtMs = now;
        try {
            // 接口限制单次最多 3 个变量，取满要素必须拆成多批；批次并行，
            // 总耗时≈单批，且都落在同一次节流窗口内（不提高回源频度）
            List<List<String>> batches = partition(REQUEST_VARIABLES, MAX_VARIABLES);
            List<CompletableFuture<BatchResponse>> futures = batches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(
                            () -> queryBatch(batch, points, hours), fetchExecutor))
                    .toList();
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(Math.max(1, timeoutSeconds) + 10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 任一批超时/异常不阻塞其余批：下方按批收集已得结果
                futures.forEach(f -> f.cancel(true));
            }
            Map<String, List<Double>> merged = new LinkedHashMap<>();
            List<Double> latitudes = List.of();
            List<Double> longitudes = List.of();
            boolean anyUnauthorized = false;
            boolean anySuccess = false;
            int firstFailCode = 0;
            for (CompletableFuture<BatchResponse> f : futures) {
                BatchResponse br = f.getNow(null);
                if (br == null) {
                    continue;
                }
                if (br.code() == 401) {
                    anyUnauthorized = true;
                } else if (br.ok()) {
                    anySuccess = true;
                    merged.putAll(br.values());
                    if (latitudes.isEmpty()) {
                        latitudes = br.latitudes();
                        longitudes = br.longitudes();
                    }
                } else if (firstFailCode == 0) {
                    firstFailCode = br.code();
                }
            }
            cntUpstream += batches.size();
            if (anyUnauthorized) {
                // Token 无效：立即熔断，静默期内不再发起任何回源
                openCircuit(now);
                return unavailable("上游返回 401（Token 无效），预报通道已熔断", lastResult);
            }
            if (!anySuccess || !merged.containsKey("PRE") || latitudes.isEmpty()) {
                registerFailure(now);
                return unavailable(firstFailCode > 0
                        ? "上游返回 HTTP " + firstFailCode
                        : "上游响应解析失败（可能该时次/区域无数据）", lastResult);
            }
            ForecastResult parsed = parse(merged, latitudes, longitudes, points, hours);
            if (parsed == null || !parsed.dataAvailable()) {
                registerFailure(now);
                return unavailable("上游响应缺少 PRE 或坐标不匹配", lastResult);
            }
            consecutiveFailures = 0;
            circuitUntilMs = 0;
            lastResult = parsed;
            lastResultAtMs = now;
            return parsed;
        } catch (Exception e) {
            registerFailure(now);
            return unavailable("上游请求失败：" + e.getClass().getSimpleName(), lastResult);
        }
    }

    /** 单批结果：成功时带该批变量的数值数组与坐标轴 */
    private record BatchResponse(int code, Map<String, List<Double>> values,
                                 List<Double> latitudes, List<Double> longitudes) {
        boolean ok() {
            return code >= 200 && code < 300 && values != null && !values.isEmpty();
        }
    }

    /** 拉取一批（≤3 个）变量，失败不抛异常，由调用方按 code 汇总 */
    private BatchResponse queryBatch(List<String> vars, List<ForecastPoint> points, List<Integer> hours) {
        try {
            String url = buildUrl(vars, points, hours);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new BatchResponse(resp.statusCode(), Map.of(), List.of(), List.of());
            }
            JsonNode root = mapper.readTree(resp.body());
            return new BatchResponse(resp.statusCode(),
                    extractValues(root, vars),
                    toDoubleList(root.path("coordinates").path("latitude")),
                    toDoubleList(root.path("coordinates").path("longitude")));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new BatchResponse(-1, Map.of(), List.of(), List.of());
        } catch (Exception e) {
            return new BatchResponse(-1, Map.of(), List.of(), List.of());
        }
    }

    /** 从响应体中取出指定变量的数值数组（缺测保持 null） */
    private Map<String, List<Double>> extractValues(JsonNode root, List<String> vars) {
        try {
            JsonNode data = root.path("data");
            Map<String, List<Double>> out = new LinkedHashMap<>();
            for (String var : vars) {
                JsonNode node = data.path(var);
                if (node.isMissingNode() || node.isNull()) {
                    continue;
                }
                List<Double> vals = new ArrayList<>();
                for (JsonNode n : node.path("values")) {
                    vals.add(n.isNumber() ? n.asDouble() : null);
                }
                out.put(var, vals);
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 按固定大小切分：接口单次最多 3 个变量 */
    private static <T> List<List<T>> partition(List<T> src, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < src.size(); i += size) {
            out.add(new ArrayList<>(src.subList(i, Math.min(src.size(), i + size))));
        }
        return out;
    }

    /** 频度自检：只暴露计数与守护状态，不含密钥/Token 信息 */
    public Map<String, Object> status() {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("configured", isConfigured());
        m.put("runTime", runTime);
        m.put("hours", hoursRaw);
        m.put("minIntervalSeconds", Math.max(1, minRequestIntervalSeconds));
        m.put("upstreamRequests", cntUpstream);
        m.put("throttled", cntThrottled);
        m.put("circuitOpens", cntCircuitOpens);
        m.put("circuitOpen", now < circuitUntilMs);
        m.put("consecutiveFailures", consecutiveFailures);
        m.put("secondsSinceLastUpstream", lastRequestAtMs == 0 ? null : (now - lastRequestAtMs) / 1000);
        m.put("secondsSinceLastResult", lastResultAtMs == 0 ? null : (now - lastResultAtMs) / 1000);
        return m;
    }

    // ===== 内部实现 =====

    private String buildUrl(List<String> vars, List<ForecastPoint> points, List<Integer> hours) {
        double minLon = BBOX_MAX_LON;
        double minLat = BBOX_MAX_LAT;
        double maxLon = BBOX_MIN_LON;
        double maxLat = BBOX_MIN_LAT;
        for (ForecastPoint p : points) {
            minLon = Math.min(minLon, p.lon());
            minLat = Math.min(minLat, p.lat());
            maxLon = Math.max(maxLon, p.lon());
            maxLat = Math.max(maxLat, p.lat());
        }
        minLon = Math.max(BBOX_MIN_LON, minLon - BBOX_PADDING_DEG);
        minLat = Math.max(BBOX_MIN_LAT, minLat - BBOX_PADDING_DEG);
        maxLon = Math.min(BBOX_MAX_LON, maxLon + BBOX_PADDING_DEG);
        maxLat = Math.min(BBOX_MAX_LAT, maxLat + BBOX_PADDING_DEG);
        String bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", minLon, minLat, maxLon, maxLat);
        Map<String, String> params = new LinkedHashMap<>();
        // 取满接口开放的全部要素：降雨用于熔断判定，其余（温度/风/湿度/气压/能见度）
        // 用于界面展示——"官方给的数据能用的都放上来"，而不是只显示一个降雨量
        params.put("variables", String.join(",", vars));
        params.put("run_time", runTime);
        params.put("forecast_hours", joinInts(hours));
        params.put("bbox", bbox);
        return HttpUtil.joinUrl(baseUrl, "/api/v1/forecasts/3h") + "?" + HttpUtil.encodeQuery(params);
    }

    /**
     * 把多批合并后的变量数值还原成按点位的降雨与要素。
     * 形状以首批返回的 shape 为准（各批 bbox/时效一致，形状必然相同）。
     */
    private ForecastResult parse(Map<String, List<Double>> varValues,
                                 List<Double> latitudes, List<Double> longitudes,
                                 List<ForecastPoint> points, List<Integer> hours) {
        try {
            List<Double> preValues = varValues.get("PRE");
            if (preValues == null || preValues.isEmpty()) {
                return null;
            }
            int hourSize = hours.size();
            int latSize = latitudes.size();
            int lonSize = longitudes.size();
            if (hourSize <= 0 || latSize <= 0 || lonSize <= 0) {
                return null;
            }
            Map<String, Double> rain6h = new LinkedHashMap<>();
            Map<String, List<Double>> hourly = new LinkedHashMap<>();
            Map<String, Map<String, Double>> pointElements = new LinkedHashMap<>();
            Map<String, String> windText = new LinkedHashMap<>();
            for (ForecastPoint p : points) {
                int latIdx = nearestIndex(latitudes, p.lat());
                int lonIdx = nearestIndex(longitudes, p.lon());
                List<Double> perHour = new ArrayList<>();
                double sum = 0;
                boolean any = false;
                for (int h = 0; h < hourSize; h++) {
                    Double v = valueAt(preValues, h, latIdx, lonIdx, latSize, lonSize);
                    if (v == null || !Double.isFinite(v)) {
                        perHour.add(null);
                        continue;
                    }
                    perHour.add(v);
                    sum += v;
                    any = true;
                }
                if (!any) {
                    continue;
                }
                hourly.put(p.id(), perHour);
                rain6h.put(p.id(), sum);
                // 各要素取首个时效值（3h，最贴近当前），换算为界面友好单位
                Map<String, Double> elems = new LinkedHashMap<>();
                for (Map.Entry<String, List<Double>> e : varValues.entrySet()) {
                    Double raw = valueAt(e.getValue(), 0, latIdx, lonIdx, latSize, lonSize);
                    if (raw != null && Double.isFinite(raw)) {
                        elems.put(e.getKey(), raw);
                    }
                }
                Map<String, Double> converted = convertUnits(elems);
                pointElements.put(p.id(), converted);
                // 静风时风向无意义（u、v 都≈0，方位角是噪声），直接标"静风"
                Double windSpeed = converted.get("windKph");
                Double windDeg = converted.get("windDirectionDeg");
                if (windDeg != null) {
                    windText.put(p.id(), windSpeed != null && windSpeed < 1.0
                            ? "静风" : windDirectionText(windDeg));
                }
            }
            if (rain6h.isEmpty()) {
                return null;
            }
            String detail = "起报 " + runTime + "，时效 " + joinInts(hours) + "h";
            return new ForecastResult(true, runTime, hours, rain6h, hourly, pointElements, windText,
                    "GOWFS 预报", detail, true, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 上游原始单位 → 界面展示单位。
     * WIN_U/WIN_V 是分量，合成风速（km/h）与风向（方位角）；VIS 是 km，换算成 m 与实况卡片一致；
     * 其余（TMP/TMAX/TMIN/PRE/PRS/RH/TCC/LCC）单位与实况一致，直接沿用。
     */
    private Map<String, Double> convertUnits(Map<String, Double> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        Double u = raw.get("WIN_U");
        Double v = raw.get("WIN_V");
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            switch (e.getKey()) {
                case "WIN_U", "WIN_V" -> {
                    // 分量不单独展示，合成成风速/风向后再给（见下方 put）
                }
                case "VIS" -> out.put("visibilityM", e.getValue() * 1000.0);
                default -> out.put(e.getKey(), e.getValue());
            }
        }
        if (u != null && v != null) {
            out.put("windKph", Math.hypot(u, v) * 3.6);
            // 风的去向方位角（正北 0°，顺时针递增）：u 为东向分量，v 为北向分量
            double deg = Math.toDegrees(Math.atan2(u, v));
            if (deg < 0) {
                deg += 360;
            }
            out.put("windDirectionDeg", deg);
        }
        return out;
    }

    /** 16 方位中文名（风的去向） */
    private static final String[] WIND_16 = {
            "北", "北东北", "东北", "东东北", "东", "东东南", "东南", "南东南",
            "南", "南西南", "西南", "西西南", "西", "西西北", "西北", "北西北"
    };

    /**
     * 方位角 → 16 方位中文名。
     * 静风（u、v 都接近 0）时无意义，返回"静风"由调用方按风速判断。
     */
    private static String windDirectionText(double deg) {
        int idx = (int) Math.round(deg / 22.5) % 16;
        return WIND_16[idx];
    }

    private Double valueAt(List<Double> values, int h, int lat, int lon, int latSize, int lonSize) {
        int idx = (h * latSize + lat) * lonSize + lon;
        if (idx < 0 || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
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

    /** 时效参数解析：3–168 内 3 的倍数，去重后最多 5 个 */
    private List<Integer> parseHours(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split(",")) {
            String s = p == null ? "" : p.trim();
            if (s.isBlank()) {
                return List.of();
            }
            try {
                int v = Integer.parseInt(s);
                if (v < 3 || v > 168 || v % 3 != 0) {
                    return List.of();
                }
                if (!out.contains(v)) {
                    out.add(v);
                }
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        if (out.size() > MAX_FORECAST_HOURS) {
            return out.subList(0, MAX_FORECAST_HOURS);
        }
        return out;
    }

    private ForecastResult unavailable(String message, ForecastResult fallback) {
        return new ForecastResult(false, runTime, List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                "GOWFS 预报", message, false,
                fallback == null ? System.currentTimeMillis() : fallback.fetchedAt());
    }

    /** 有上次结果时沿用其数值，但如实标注"非本次回源" */
    private ForecastResult cached(ForecastResult r) {
        if (r == null) {
            return unavailable("暂无预报数据", null);
        }
        return new ForecastResult(r.dataAvailable(), r.runTime(), r.hours(), r.rain6hMm(), r.hourlyMm(),
                r.pointElements(), r.windDirectionText(), r.sourceLabel(),
                r.message() + "（节流窗口内，非本次回源）", false, r.fetchedAt());
    }

    private long minRequestIntervalMs() {
        return Math.max(1, minRequestIntervalSeconds) * 1000L;
    }

    private void registerFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= Math.max(1, circuitFailureThreshold)) {
            openCircuit(now);
        }
    }

    private void openCircuit(long now) {
        circuitUntilMs = now + Math.max(1, circuitOpenSeconds) * 1000L;
        consecutiveFailures = 0;
        cntCircuitOpens++;
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

    private String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
