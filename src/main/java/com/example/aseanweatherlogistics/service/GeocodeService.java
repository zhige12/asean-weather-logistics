package com.example.aseanweatherlogistics.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 地名 → 坐标（地理编码）+ 坐标 → 路网节点（吸附）。
 * <p>
 * 两级策略（配额优先离线）：
 * 1) 本地地名库 classpath:data/place-names.json（约 50 条，覆盖广西—越南北部—平陆运河沿线，
 *    其中城市/口岸条目直接取自路网带名节点，吸附距离≈0）；不消耗天地图配额；
 * 2) 冷门地名才调天地图地理编码 API（与瓦片共享每日配额），按 tianditu.keys 密钥池轮转，
 *    结果进内存缓存，同名重复查询只花一次配额。
 * <p>
 * 吸附规则：JGraphT 路网中距输入坐标最近的节点；超过 {@link #SNAP_MAX_KM} 视为覆盖范围外
 * （路网覆盖：广西—越南北部 + 平陆运河沿线），抛 IllegalArgumentException 由全局异常处理转 400。
 * 坐标系全程 WGS-84（天地图坐标），与路网一致，不做转换。
 */
@Service
public class GeocodeService {

    private static final Logger log = LoggerFactory.getLogger(GeocodeService.class);

    /** 吸附半径上限（km）：最近路网节点超过此距离提示覆盖范围外 */
    public static final double SNAP_MAX_KM = 5.0;

    private static final String GEOCODER_URL = "http://api.tianditu.gov.cn/geocoder?ds=%s&tk=%s";

    private final RouteService routeService;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 密钥池：与瓦片代理共用同一配置，轮转避免单 key 限流 */
    private final List<String> keys;
    private int keyCursor = 0;

    /** 本地地名库（启动时一次性加载） */
    private final List<Place> places = new ArrayList<>();

    /** 天地图在线结果缓存：name → 坐标，避免重复花配额 */
    private final Map<String, double[]> onlineCache = new ConcurrentHashMap<>();

    public GeocodeService(RouteService routeService,
                          @Value("${tianditu.keys:}") String keyPool) {
        this.routeService = routeService;
        this.keys = keyPool == null || keyPool.isBlank()
                ? List.of()
                : List.of(keyPool.split(","));
    }

    @PostConstruct
    void loadPlaces() {
        try (InputStream in = new ClassPathResource("data/place-names.json").getInputStream()) {
            List<Place> list = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<Place>>() {}.getType());
            if (list != null) places.addAll(list);
            log.info("本地地名库加载 {} 条", places.size());
        } catch (Exception e) {
            log.error("本地地名库加载失败，地理编码将仅走在线通道", e);
        }
    }

    /** 下拉候选 + 前端离线预匹配用全量地名库 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(places.size());
        for (Place p : places) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("lat", p.lat);
            m.put("lng", p.lng);
            m.put("tag", p.tag);
            out.add(m);
        }
        return out;
    }

    /**
     * 地理编码：先本地库（精确 → 去行政后缀精确 → 包含匹配），未命中再走天地图在线。
     *
     * @return {name, lat, lng, source: local|tianditu}；查无返回 null
     */
    public Map<String, Object> geocode(String name) {
        if (name == null || name.isBlank()) return null;
        String q = name.trim();
        Place hit = matchLocal(q);
        if (hit != null) return result(hit.name, hit.lat, hit.lng, "local");
        double[] cached = onlineCache.get(q);
        if (cached != null) return result(q, cached[0], cached[1], "tianditu");
        double[] online = geocodeOnline(q);
        if (online == null) return null;
        onlineCache.put(q, online);
        return result(q, online[0], online[1], "tianditu");
    }

    /** 本地库匹配：精确 → 去行政后缀精确 → 双向包含（取名字最短的命中，避免"南宁"命中"南宁港…"） */
    private Place matchLocal(String q) {
        String nq = stripSuffix(q);
        Place best = null;
        for (Place p : places) {
            if (p.name == null) continue;
            boolean exact = p.name.equals(q) || stripSuffix(p.name).equals(nq);
            boolean contains = p.name.contains(q) || q.contains(p.name);
            if (!exact && !contains) continue;
            if (best == null
                    || (exact && !isExact(best, q))
                    || (exact == isExact(best, q) && p.name.length() < best.name.length())) {
                best = p;
            }
            if (exact && p.name.equals(q)) return p; // 全等最优，直接返回
        }
        return best;
    }

    private boolean isExact(Place p, String q) {
        return p.name.equals(q) || stripSuffix(p.name).equals(stripSuffix(q));
    }

    /** 去掉尾部行政区划后缀，让"南宁市"命中"南宁"、"河内市"命中"河内" */
    private static String stripSuffix(String s) {
        String r = s.replaceAll("(壮族自治区|特别行政区|自治区|市|省|县|区|镇|乡)$", "");
        return r.isEmpty() ? s : r;
    }

    /** 天地图地理编码（在线）：密钥池轮转，全池失败返回 null 不抛异常 */
    private double[] geocodeOnline(String q) {
        if (keys.isEmpty()) return null;
        for (int i = 0; i < keys.size(); i++) {
            String key = nextKey();
            String ds = URLEncoder.encode("{\"keyWord\":\"" + q + "\"}", StandardCharsets.UTF_8);
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(String.format(GEOCODER_URL, ds, key)))
                        .timeout(Duration.ofSeconds(6))
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("天地图地理编码 {} 返回 HTTP {}，换 key 重试", q, resp.statusCode());
                    continue;
                }
                JsonObject body = gson.fromJson(resp.body(), JsonObject.class);
                JsonObject loc = body.has("location") && body.get("location").isJsonObject()
                        ? body.getAsJsonObject("location")
                        : (body.has("result") && body.getAsJsonObject("result").has("location")
                            ? body.getAsJsonObject("result").getAsJsonObject("location") : null);
                if (loc == null || !loc.has("lon") || !loc.has("lat")) continue;
                double lng = loc.get("lon").getAsDouble();
                double lat = loc.get("lat").getAsDouble();
                if (lat == 0 && lng == 0) continue;
                return new double[]{lat, lng};
            } catch (Exception e) {
                log.warn("天地图地理编码请求失败 key={}：{}", key, e.getMessage());
            }
        }
        return null;
    }

    private synchronized String nextKey() {
        String k = keys.get(keyCursor % keys.size());
        keyCursor++;
        return k;
    }

    /**
     * 坐标吸附到最近路网节点。
     *
     * @return {nodeId, nodeName, lat, lng, distanceKm}
     * @throws IllegalArgumentException 最近节点超过 SNAP_MAX_KM（覆盖范围外）
     */
    public Map<String, Object> snap(String label, double lat, double lng) {
        var nodes = routeService.getAllNodes();
        var best = routeService.getNode("NN");
        double bestKm = Double.MAX_VALUE;
        for (var n : nodes) {
            double km = haversineKm(lat, lng, n.getLatitude(), n.getLongitude());
            if (km < bestKm) {
                bestKm = km;
                best = n;
            }
        }
        if (bestKm > SNAP_MAX_KM) {
            throw new IllegalArgumentException("「" + label + "」该区域暂未覆盖路网（距最近节点 "
                    + Math.round(bestKm) + "km），请选择广西—越南北部/平陆运河沿线已覆盖区域");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", best.getId());
        m.put("nodeName", best.getName() != null ? best.getName() : best.getId());
        m.put("lat", best.getLatitude());
        m.put("lng", best.getLongitude());
        m.put("distanceKm", Math.round(bestKm * 100.0) / 100.0);
        return m;
    }

    /** 起终点一次吸附完，返回 {start: snap, end: snap} */
    public Map<String, Object> snapPair(double sLat, double sLng, double eLat, double eLng) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start", snap("起点", sLat, sLng));
        out.put("end", snap("终点", eLat, eLng));
        return out;
    }

    private static Map<String, Object> result(String name, double lat, double lng, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("source", source);
        return m;
    }

    /** Haversine 球面距离（km） */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** place-names.json 条目 */
    private static class Place {
        String name;
        double lat;
        double lng;
        String tag;
        String nodeId;
    }
}
