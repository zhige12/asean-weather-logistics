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
    private static final String CURRENT_VARS =
            "temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,visibility";

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
        return "真实气象接口（Open-Meteo 公网实况）";
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
                out.add(new RealWeatherService.WeatherPoint(
                        node.getId(), node.getLatitude(), node.getLongitude(),
                        tmp, rh, pre, wind, vis, fetchedAt
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
}
