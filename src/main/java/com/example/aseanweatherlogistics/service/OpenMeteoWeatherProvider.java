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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 公网真实气象接口（Open-Meteo）适配：免费、无需 Token，返回全球实时实况。
 * <p>
 * 一次请求支持多组经纬度（逗号分隔），因此按批次拉取，避免逐节点请求。
 * 字段口径与业务一致：温度℃、相对湿度%、降水mm/h、风速km/h、能见度m。
 */
@Service
public class OpenMeteoWeatherProvider implements WeatherProvider {

    public static final String ID = "open-meteo";

    private static final String USER_AGENT = "asean-weather-logistics/1.0 (open-meteo)";
    /** surface_pressure 用于台风/强对流判据（低气压 + 大风） */
    private static final String CURRENT_VARS =
            "temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,visibility,surface_pressure";

    @Value("${weather.openmeteo.base-url:https://api.open-meteo.com/v1/forecast}")
    private String baseUrl;
    @Value("${weather.openmeteo.timeout-seconds:20}")
    private long timeoutSeconds;
    @Value("${weather.openmeteo.batch-size:20}")
    private int batchSize;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Open-Meteo";
    }

    @Override
    public boolean isConfigured() {
        // 公网免费接口，无需 Token，始终可用（断网时请求失败会自动降级）
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty() || !isConfigured()) {
            return List.of();
        }
        long fetchedAt = System.currentTimeMillis();
        int size = Math.max(1, batchSize);
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i += size) {
            List<RoadNode> batch = nodes.subList(i, Math.min(nodes.size(), i + size));
            out.addAll(fetchBatch(batch, fetchedAt));
        }
        return out;
    }

    private List<RealWeatherService.WeatherPoint> fetchBatch(List<RoadNode> batch, long fetchedAt) {
        StringBuilder lats = new StringBuilder();
        StringBuilder lons = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                lats.append(',');
                lons.append(',');
            }
            lats.append(String.format(Locale.US, "%.4f", batch.get(i).getLatitude()));
            lons.append(String.format(Locale.US, "%.4f", batch.get(i).getLongitude()));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("latitude", lats.toString());
        params.put("longitude", lons.toString());
        params.put("current", CURRENT_VARS);
        params.put("wind_speed_unit", "kmh");
        params.put("timezone", "auto");
        try {
            String url = baseUrl + "?" + HttpUtil.encodeQuery(params);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return List.of();
            }
            return parse(resp.body(), batch, fetchedAt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<RealWeatherService.WeatherPoint> parse(String body, List<RoadNode> batch, long fetchedAt) {
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(body);
            List<JsonNode> items = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(items::add);
            } else {
                items.add(root);
            }
            for (int i = 0; i < batch.size() && i < items.size(); i++) {
                RoadNode node = batch.get(i);
                JsonNode current = items.get(i).path("current");
                if (current.isMissingNode() || current.isNull()) {
                    continue;
                }
                double tmp = num(current, "temperature_2m", 26);
                double rh = num(current, "relative_humidity_2m", 60);
                double pre = num(current, "precipitation", 0);
                double wind = num(current, "wind_speed_10m", 0);
                double vis = num(current, "visibility", 10000);
                double prs = num(current, "surface_pressure", 1013);
                out.add(new RealWeatherService.WeatherPoint(
                        node.getId(), node.getLatitude(), node.getLongitude(),
                        tmp, rh, pre, wind, vis, prs, fetchedAt
                ));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private double num(JsonNode node, String field, double fallback) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || !v.isNumber()) {
            return fallback;
        }
        return v.asDouble();
    }

    /**
     * 日最高/最低气温与云量：走 Open-Meteo 自己的 {@code daily} / {@code current.cloud_cover}。
     * 这个源没有「低云量 LCC」这一项，就不拿总云量冒充——缺项交给界面显示「—」。
     * <p>它与主取数另外发一轮请求，但本接口是公网免 Token 源、不受赛事上游频度约束，
     * 而调用方（区域态势）自己已有半小时节流。</p>
     */
    @Override
    public Map<String, Map<String, Double>> fetchExtendedElements(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty() || !isConfigured()) {
            return Map.of();
        }
        int size = Math.max(1, batchSize);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i += size) {
            List<RoadNode> batch = nodes.subList(i, Math.min(nodes.size(), i + size));
            out.putAll(fetchExtBatch(batch));
        }
        return out;
    }

    private Map<String, Map<String, Double>> fetchExtBatch(List<RoadNode> batch) {
        StringBuilder lats = new StringBuilder();
        StringBuilder lons = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                lats.append(',');
                lons.append(',');
            }
            lats.append(String.format(Locale.US, "%.4f", batch.get(i).getLatitude()));
            lons.append(String.format(Locale.US, "%.4f", batch.get(i).getLongitude()));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("latitude", lats.toString());
        params.put("longitude", lons.toString());
        params.put("daily", "temperature_2m_max,temperature_2m_min");
        params.put("current", "cloud_cover");
        params.put("forecast_days", "1");
        params.put("timezone", "auto");
        try {
            String url = baseUrl + "?" + HttpUtil.encodeQuery(params);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return Map.of();
            }
            return parseExt(resp.body(), batch);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Map<String, Double>> parseExt(String body, List<RoadNode> batch) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else {
            items.add(root);
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < batch.size() && i < items.size(); i++) {
            JsonNode item = items.get(i);
            Map<String, Double> one = new LinkedHashMap<>();
            JsonNode daily = item.path("daily");
            putNum(one, "TMAX", daily.path("temperature_2m_max"));
            putNum(one, "TMIN", daily.path("temperature_2m_min"));
            putNum(one, "TCC", item.path("current").path("cloud_cover"));
            if (!one.isEmpty()) {
                out.put(batch.get(i).getId(), one);
            }
        }
        return out;
    }

    /** 缺项不填默认值（与 CRA40 适配同一口径：展示型数据宁可空着） */
    private static void putNum(Map<String, Double> target, String key, JsonNode node) {
        if (node != null && node.isNumber()) {
            target.put(key, node.asDouble());
        } else if (node != null && node.isArray() && !node.isEmpty() && node.get(0).isNumber()) {
            target.put(key, node.get(0).asDouble());
        }
    }

    /**
     * 前瞻预报：逐小时降水累计出「未来 3h / 未来 6h」。
     * 用 {@code start_hour/end_hour} 把时间轴钉在当前时刻之后的 6 小时上；
     * 上游要是没按该区间返回（拿回整天 24 小时），就当缺项不取——
     * 把「今天凌晨的雨量」当成「未来六小时」是会把危情读成安全的。
     */
    @Override
    public Map<String, Map<String, Double>> fetchForecastOutlook(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty() || !isConfigured()) {
            return Map.of();
        }
        int size = Math.max(1, batchSize);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i += size) {
            List<RoadNode> batch = nodes.subList(i, Math.min(nodes.size(), i + size));
            out.putAll(fetchOutlookBatch(batch));
        }
        return out;
    }

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private Map<String, Map<String, Double>> fetchOutlookBatch(List<RoadNode> batch) {
        StringBuilder lats = new StringBuilder();
        StringBuilder lons = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                lats.append(',');
                lons.append(',');
            }
            lats.append(String.format(Locale.US, "%.4f", batch.get(i).getLatitude()));
            lons.append(String.format(Locale.US, "%.4f", batch.get(i).getLongitude()));
        }
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("latitude", lats.toString());
        params.put("longitude", lons.toString());
        params.put("hourly", "precipitation");
        params.put("start_hour", HOUR_FMT.format(from));
        params.put("end_hour", HOUR_FMT.format(from.plusHours(6)));
        params.put("timezone", "UTC");
        try {
            String url = baseUrl + "?" + HttpUtil.encodeQuery(params);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return Map.of();
            }
            return parseOutlook(resp.body(), batch);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Map<String, Double>> parseOutlook(String body, List<RoadNode> batch) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else {
            items.add(root);
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < batch.size() && i < items.size(); i++) {
            JsonNode arr = items.get(i).path("hourly").path("precipitation");
            if (!arr.isArray() || arr.size() < 3 || arr.size() > 8) {
                continue;
            }
            double sum3 = 0;
            double sum6 = 0;
            boolean any = false;
            for (int k = 0; k < arr.size(); k++) {
                JsonNode v = arr.get(k);
                if (!v.isNumber()) {
                    continue;
                }
                any = true;
                sum6 += v.asDouble();
                if (k < 3) {
                    sum3 += v.asDouble();
                }
            }
            if (!any) {
                continue;
            }
            Map<String, Double> one = new LinkedHashMap<>();
            one.put("rain3hMm", sum3);
            one.put("rain6hMm", sum6);
            out.put(batch.get(i).getId(), one);
        }
        return out;
    }
}
