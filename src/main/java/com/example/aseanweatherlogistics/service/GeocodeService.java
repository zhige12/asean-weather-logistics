package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.SecretCodec;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 天地图 POI 关键字检索（多候选）：输入联想用，mapBound 把搜索偏置到服务走廊。
     * 注意 mapBound 只是偏置不是硬限制（全国同名点会混入），候选另按同一范围二次过滤。 */
    private static final String SEARCH_URL = "https://api.tianditu.gov.cn/v2/search?postStr=%s&type=keyword&tk=%s";
    private static final String SEARCH_MAP_BOUND = "104.0,8.5,110.0,24.5";
    private static final double[] SEARCH_BOUND = {8.5, 24.5, 104.0, 110.0}; // latMin,latMax,lonMin,lonMax

    /**
     * 服务覆盖走廊大致经纬度范围（广西—越南北部 + 平陆运河沿线）：
     * 在线编码返回的坐标落在此窗外时视为同名误命中（如裸"石埠"命中上海），触发城市前缀纠偏。
     */
    private static final double CORR_LAT_MIN = 8.0;
    private static final double CORR_LAT_MAX = 27.0;
    private static final double CORR_LON_MIN = 99.0;
    private static final double CORR_LON_MAX = 110.0;

    private final RouteService routeService;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 密钥池：与瓦片代理共用同一配置，轮转避免单 key 限流 */
    private final List<String> keys;
    private int keyCursor = 0;

    /** 海外地理编码兜底（天地图未命中时启用，主要覆盖越南街道 / POI） */
    private final boolean nominatimEnabled;
    private final String nominatimEndpoint;
    private final String nominatimUserAgent;
    private final int nominatimTimeoutSeconds;
    /** Nominatim 专用客户端：配置了 geocode.nominatim.proxy(host:port) 时经该 HTTP 代理出公网，否则直连 */
    private final HttpClient nominatimClient;
    /** Nominatim 节流：公共服务要求 ≤1 req/s，跨请求串行并保证最小间隔 */
    private final Object nominatimLock = new Object();
    private long nominatimLastAt = 0L;

    /**
     * 天地图密钥为「浏览器端」类型，服务端 REST 调用必须带合规 Referer + 浏览器 UA 才放行
     * （与瓦片代理同一策略，否则返回 301012 权限类型错误 → 中国地名全部无法编码）。
     */
    private final String tiandituReferer;
    private final String tiandituUserAgent;
    /** 裸地名易命中外地同名点：结果落在覆盖窗外时用该城市前缀重试，把坐标拉回服务走廊 */
    private final String regionBias;

    /** 本地地名库（启动时一次性加载） */
    private final List<Place> places = new ArrayList<>();

    /** 在线结果缓存：name → 坐标+来源，避免重复花配额/重复外调 */
    private final Map<String, GeoHit> onlineCache = new ConcurrentHashMap<>();

    /** 输入联想结果缓存：q → 候选列表（同词重复输入不重复花配额） */
    private final Map<String, List<Map<String, Object>>> suggestCache = new ConcurrentHashMap<>();

    public GeocodeService(RouteService routeService,
                          @Value("${tianditu.keys:}") String keyPool,
                          @Value("${tianditu.referer:}") String tiandituReferer,
                          @Value("${tianditu.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}") String tiandituUserAgent,
                          @Value("${geocode.region-bias:南宁市}") String regionBias,
                          @Value("${geocode.nominatim.enabled:false}") boolean nominatimEnabled,
                          @Value("${geocode.nominatim.endpoint:https://nominatim.openstreetmap.org/search}") String nominatimEndpoint,
                          @Value("${geocode.nominatim.user-agent:asean-weather-logistics/1.0}") String nominatimUserAgent,
                          @Value("${geocode.nominatim.timeout-seconds:8}") int nominatimTimeoutSeconds,
                          @Value("${geocode.nominatim.proxy:}") String nominatimProxy) {
        this.routeService = routeService;
        String resolvedKeyPool = SecretCodec.resolve(keyPool);
        this.keys = resolvedKeyPool.isBlank()
                ? List.of()
                : List.of(resolvedKeyPool.split(","));
        this.tiandituReferer = tiandituReferer;
        this.tiandituUserAgent = tiandituUserAgent;
        this.regionBias = regionBias;
        this.nominatimEnabled = nominatimEnabled;
        this.nominatimEndpoint = nominatimEndpoint;
        this.nominatimUserAgent = nominatimUserAgent;
        this.nominatimTimeoutSeconds = nominatimTimeoutSeconds <= 0 ? 8 : nominatimTimeoutSeconds;
        this.nominatimClient = buildNominatimClient(nominatimProxy);
    }

    /** 配置了 host:port 则让 Nominatim 走该 HTTP 代理出公网（演示机需翻墙时），否则直连。 */
    private static HttpClient buildNominatimClient(String proxy) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (proxy != null && !proxy.isBlank()) {
            String p = proxy.trim();
            int colon = p.lastIndexOf(':');
            if (colon > 0) {
                try {
                    String host = p.substring(0, colon);
                    int port = Integer.parseInt(p.substring(colon + 1));
                    b = b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
                } catch (NumberFormatException ignored) {
                    // 代理地址非法：回落直连，不因配置错误让整体地理编码不可用
                }
            }
        }
        return b.build();
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

    /**
     * 下拉候选 + 前端离线预匹配用全量地名库。
     * nodeId 一并下发：带路网内置节点 id 的条目（城市/口岸）前端可直接填，省一次 /snap 请求，
     * 离线演示也不依赖吸附接口。hot 为「常用地点」排序权重（见 place-names.json），无标记不下发。
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(places.size());
        for (Place p : places) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("lat", p.lat);
            m.put("lng", p.lng);
            m.put("tag", p.tag);
            if (p.nodeId != null && !p.nodeId.isBlank()) m.put("nodeId", p.nodeId);
            if (p.hot != null) m.put("hot", p.hot);
            out.add(m);
        }
        return out;
    }

    /**
     * 输入联想（导航软件式）：关键字 → 多候选 {name, lat, lng, tag, source}。
     * 本地库前缀命中优先、包含命中其次（零配额）；不足 6 条且关键字≥ 2 字时
     * 才调天地图 POI 检索补齐（结果进缓存，失败静默降级为仅本地）。
     */
    public List<Map<String, Object>> suggest(String q) {
        if (q == null || q.isBlank()) return List.of();
        String t = q.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String nt = stripSuffix(t);
        List<Place> prefixHits = new ArrayList<>();
        List<Place> containHits = new ArrayList<>();
        for (Place p : places) {
            if (p.name == null) continue;
            if (p.name.startsWith(t) || stripSuffix(p.name).startsWith(nt)) {
                prefixHits.add(p);
            } else if (p.name.contains(t) || t.contains(p.name)) {
                containHits.add(p);
            }
        }
        for (Place p : prefixHits) addSuggest(out, seen, p.name, p.lat, p.lng, p.tag, "local", p.nodeId);
        for (Place p : containHits) addSuggest(out, seen, p.name, p.lat, p.lng, p.tag, "local", p.nodeId);
        if (out.size() < 6 && t.length() >= 2) {
            suggestOnline(t, out, seen, 8);
        }
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    private void addSuggest(List<Map<String, Object>> out, Set<String> seen,
                            String name, double lat, double lng, String tag, String source, String nodeId) {
        if (name == null || !seen.add(name)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("tag", tag == null ? "" : tag);
        m.put("source", source);
        // 本地库条目带路网节点 id 时一并给出，前端点选可跳过吸附
        if (nodeId != null && !nodeId.isBlank()) m.put("nodeId", nodeId);
        out.add(m);
    }

    /** 天地图 POI 检索补候选：同词命中缓存直接复用；失败/无结果静默返回。 */
    private void suggestOnline(String q, List<Map<String, Object>> out, Set<String> seen, int cap) {
        List<Map<String, Object>> cached = suggestCache.get(q);
        if (cached == null) {
            cached = searchTianditu(q);
            if (suggestCache.size() > 400) suggestCache.clear();
            suggestCache.put(q, cached);
        }
        for (Map<String, Object> m : cached) {
            if (out.size() >= cap) return;
            addSuggest(out, seen, (String) m.get("name"),
                    (Double) m.get("lat"), (Double) m.get("lng"),
                    (String) m.get("tag"), "tianditu", null);
        }
    }

    /** 调天地图 v2/search 接口拿 POI 列表（密钥池轮转 + 合规 Referer，与地理编码同策略）。
     * 返回顶层 pois 数组，每项 {name, address, lonlat:"lng,lat"}；全国同名结果靠走廊过滤剔除。 */
    private List<Map<String, Object>> searchTianditu(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (keys.isEmpty()) return out;
        String ref = (tiandituReferer == null || tiandituReferer.isBlank())
                ? "http://localhost/" : tiandituReferer;
        for (int i = 0; i < keys.size(); i++) {
            String key = nextKey();
            String post = "{\"keyWord\":\"" + q.replace("\"", "") + "\",\"mapBound\":\"" + SEARCH_MAP_BOUND
                    + "\",\"level\":10,\"queryType\":\"1\",\"start\":0,\"count\":10}";
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(SEARCH_URL,
                        URLEncoder.encode(post, StandardCharsets.UTF_8), key)))
                        .timeout(Duration.ofSeconds(5))
                        .header("Referer", ref)
                        .header("User-Agent", tiandituUserAgent)
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;
                JsonElement root = JsonParser.parseString(resp.body());
                if (!root.isJsonObject()) continue;
                JsonElement pois = root.getAsJsonObject().get("pois");
                if (pois == null || !pois.isJsonArray()) continue;
                for (JsonElement e : pois.getAsJsonArray()) {
                    JsonObject poi = e.getAsJsonObject();
                    String name = poi.has("name") ? poi.get("name").getAsString() : null;
                    String lonlat = poi.has("lonlat") ? poi.get("lonlat").getAsString() : null;
                    if (name == null || name.isBlank() || lonlat == null || !lonlat.contains(",")) continue;
                    String[] ll = lonlat.split(",");
                    double lng = Double.parseDouble(ll[0].trim());
                    double lat = Double.parseDouble(ll[1].trim());
                    // 双重过滤：先过搜索范围（剔除贵州/云南等窗外同名噪声），再过走廊兼容窗口
                    if (lat < SEARCH_BOUND[0] || lat > SEARCH_BOUND[1]
                            || lng < SEARCH_BOUND[2] || lng > SEARCH_BOUND[3]) continue;
                    if (!isInCorridor(lat, lng)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("lat", lat);
                    m.put("lng", lng);
                    // 地址做副标题：同名异地时用户能分辨（省市区前缀已含在 address 里）
                    m.put("tag", poi.has("address") ? poi.get("address").getAsString() : "在线");
                    out.add(m);
                    if (out.size() >= 8) break;
                }
                return out;
            } catch (Exception e) {
                log.warn("天地图 POI 检索 {} 失败 key={}：{}", q, key, e.getMessage());
            }
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
        GeoHit cached = onlineCache.get(q);
        if (cached != null) return result(q, cached.lat, cached.lng, cached.source);
        double[] online = geocodeOnline(q);
        if (online != null) {
            onlineCache.put(q, new GeoHit(online[0], online[1], "tianditu"));
            return result(q, online[0], online[1], "tianditu");
        }
        // 天地图未命中：兜底走 OSM Nominatim，覆盖越南街道/店名等海外 POI
        double[] osm = geocodeNominatim(q);
        if (osm != null) {
            onlineCache.put(q, new GeoHit(osm[0], osm[1], "osm"));
            return result(q, osm[0], osm[1], "osm");
        }
        return null;
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

    /**
     * 天地图地理编码（在线）：先按原词查，若命中覆盖窗外的同名点（如裸"石埠"→上海），
     * 再带城市前缀重试纠偏。全池失败或纠偏未果返回 null，交给上层继续走 Nominatim。
     */
    private double[] geocodeOnline(String q) {
        double[] direct = geocodeOnlineRaw(q);
        if (direct != null && isInCorridor(direct[0], direct[1])) {
            return direct;
        }
        // 窗外同名误命中：带城市前缀纠偏（已带前缀则不重复拼）
        if (direct != null && regionBias != null && !regionBias.isBlank() && !q.startsWith(regionBias)) {
            double[] biased = geocodeOnlineRaw(regionBias + q);
            if (biased != null && isInCorridor(biased[0], biased[1])) {
                return biased;
            }
        }
        // 无结果，或结果在窗外且纠偏未果：视为不可信，交由 Nominatim 兜底
        return null;
    }

    /** 天地图在线编码单趟：密钥池轮转 + 合规 Referer/UA；全池失败返回 null 不抛异常。 */
    private double[] geocodeOnlineRaw(String q) {
        if (keys.isEmpty()) return null;
        String ref = (tiandituReferer == null || tiandituReferer.isBlank())
                ? "http://localhost/" : tiandituReferer;
        for (int i = 0; i < keys.size(); i++) {
            String key = nextKey();
            String ds = URLEncoder.encode("{\"keyWord\":\"" + q + "\"}", StandardCharsets.UTF_8);
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(String.format(GEOCODER_URL, ds, key)))
                        .timeout(Duration.ofSeconds(6))
                        .header("Referer", ref)
                        .header("User-Agent", tiandituUserAgent)
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

    /** 坐标是否落在服务走廊内（在线编码同名误命中剔除用）。 */
    private static boolean isInCorridor(double lat, double lng) {
        return lat >= CORR_LAT_MIN && lat <= CORR_LAT_MAX
                && lng >= CORR_LON_MIN && lng <= CORR_LON_MAX;
    }

    /**
     * 海外地理编码兜底（OpenStreetMap Nominatim）：天地图未命中时调用，覆盖越南街道/店名等 POI。
     * 遵守公共实例 Usage Policy：自定义 User-Agent + 全局串行并保证 ≥1s 最小间隔。
     * 任何异常/限流/无结果均返回 null，不抛出（由上层转成 400 友好提示）。
     *
     * @return {lat, lng}（WGS-84）或 null
     */
    private double[] geocodeNominatim(String q) {
        if (!nominatimEnabled || nominatimEndpoint == null || nominatimEndpoint.isBlank()) return null;
        // 节流：跨请求串行，保证相邻两次外调间隔 ≥1s
        long waitMs;
        synchronized (nominatimLock) {
            long now = System.currentTimeMillis();
            waitMs = Math.max(0L, 1000L - (now - nominatimLastAt));
            nominatimLastAt = now + waitMs;
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        String url = nominatimEndpoint + "?format=json&limit=1&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(nominatimTimeoutSeconds))
                    .header("User-Agent", nominatimUserAgent)
                    .header("Accept-Language", "vi,zh,en")
                    .GET().build();
            HttpResponse<String> resp = nominatimClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Nominatim 地理编码 {} 返回 HTTP {}", q, resp.statusCode());
                return null;
            }
            JsonElement root = JsonParser.parseString(resp.body());
            if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
            JsonObject first = root.getAsJsonArray().get(0).getAsJsonObject();
            if (!first.has("lat") || !first.has("lon")) return null;
            double lat = first.get("lat").getAsDouble();
            double lng = first.get("lon").getAsDouble();
            if (lat == 0 && lng == 0) return null;
            return new double[]{lat, lng};
        } catch (Exception e) {
            log.warn("Nominatim 地理编码请求失败 q={}：{}", q, e.getMessage());
            return null;
        }
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
        // 只读视图扫描：不再每次拷贝数万节点（getAllNodes 会 new ArrayList）
        var nodes = routeService.allNodesView();
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
        /** 「常用地点」权重（1 最靠前），仅调度大屏输入框下拉用 */
        Integer hot;
    }

    /** 在线地理编码结果缓存条目：坐标 + 来源（tianditu / osm） */
    private static class GeoHit {
        final double lat;
        final double lng;
        final String source;
        GeoHit(double lat, double lng, String source) {
            this.lat = lat;
            this.lng = lng;
            this.source = source;
        }
    }
}
