package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.util.HttpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ContestWeatherController {

    private static final String USER_AGENT = "asean-weather-logistics/1.0 (contest-proxy)";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Set<String> PRODUCTS = Set.of("observation", "forecast_3h");
    private static final Set<String> VARIABLES = Set.of(
            "TMP", "PRE", "TMAX", "TMIN", "PRS", "RH", "TCC", "LCC", "VIS", "WIN_U", "WIN_V"
    );

    private record VariableParse(List<String> vars, String errorCode, String message) {
        static VariableParse ok(List<String> vars) {
            return new VariableParse(vars, null, null);
        }

        static VariableParse err(String code, String msg) {
            return new VariableParse(List.of(), code, msg);
        }
    }

    @Value("${weather.real.base-url:}")
    private String baseUrl;
    @Value("${weather.real.token:}")
    private String serverToken;
    @Value("${weather.real.request-timeout-seconds:20}")
    private long timeoutSeconds;
    /** 合规：禁止暴力访问——本代理回源的最小间隔（与 RealWeatherService 同一配置下限） */
    @Value("${weather.real.min-request-interval-seconds:60}")
    private long upstreamMinIntervalSeconds;
    /** 合规：连续失败/401 后熔断静默时长，静默期内一律 429，不重试轰炸 */
    @Value("${weather.real.circuit-open-seconds:300}")
    private long circuitOpenSeconds;
    @Value("${weather.real.circuit-failure-threshold:3}")
    private long circuitFailureThreshold;

    /** 合规：同一查询串在该 TTL 内直接命中缓存，不重复回源 */
    private static final long PROXY_CACHE_TTL_MS = 10 * 60_000L;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ===== 合规守护状态（只限制回源频度，不改变接口语义） =====
    private record CachedProxy(int status, String body, long storedAt) {
    }
    private final Map<String, CachedProxy> queryCache = new ConcurrentHashMap<>();
    /** 最近一次真实回源时刻（最小间隔节流用） */
    private volatile long lastUpstreamAtMs = 0L;
    /** 熔断窗口截止时刻：>now 时一律 429 */
    private volatile long circuitUntilMs = 0L;
    private long consecutiveFailures = 0L;
    // 审计计数：供 /api/v1/rate-stats 查看，频度检查脚本据此断言节流生效
    private volatile long cntUpstream = 0L;      // 真实回源次数
    private volatile long cntCacheHit = 0L;      // 同查询缓存命中
    private volatile long cntThrottled = 0L;     // 节流/熔断拦下的请求
    private volatile long cntCircuitOpen = 0L;   // 熔断打开次数

    @GetMapping("/variables")
    public ResponseEntity<String> variables(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @RequestParam(required = false) String product) {
        ResponseEntity<String> authErr = validateAuth(auth);
        if (authErr != null) {
            return authErr;
        }
        if (product == null || product.isBlank() || !PRODUCTS.contains(product)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "product must be observation or forecast_3h");
        }
        return proxyGet("/api/v1/variables", Map.of("product", product));
    }

    @GetMapping("/observations")
    public ResponseEntity<String> observations(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestParam(required = false) String variables,
                                               @RequestParam(required = false) String time_start,
                                               @RequestParam(required = false) String time_end,
                                               @RequestParam(required = false) String bbox) {
        ResponseEntity<String> authErr = validateAuth(auth);
        if (authErr != null) {
            return authErr;
        }
        VariableParse vp = parseAndValidateVariables(variables);
        if (vp.errorCode() != null) {
            return error(HttpStatus.BAD_REQUEST, vp.errorCode(), vp.message());
        }
        if (!validateTime(time_start) || !validateTime(time_end)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "time_start/time_end must be YYYYMMDDHHMM");
        }
        if (!validateBbox(bbox)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_bbox", "bbox is invalid or out of allowed range");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("variables", String.join(",", vp.vars()));
        query.put("time_start", time_start);
        query.put("time_end", time_end);
        query.put("bbox", bbox);
        return proxyGet("/api/v1/observations", query);
    }

    @GetMapping("/forecasts/3h")
    public ResponseEntity<String> forecasts3h(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @RequestParam(required = false) String variables,
                                              @RequestParam(required = false) String run_time,
                                              @RequestParam(required = false) String forecast_hours,
                                              @RequestParam(required = false) String bbox) {
        ResponseEntity<String> authErr = validateAuth(auth);
        if (authErr != null) {
            return authErr;
        }
        VariableParse vp = parseAndValidateVariables(variables);
        if (vp.errorCode() != null) {
            return error(HttpStatus.BAD_REQUEST, vp.errorCode(), vp.message());
        }
        if (!validateRunTime(run_time)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "run_time must be YYYYMMDDHHMM at 08:00 or 20:00");
        }
        List<Integer> hours = parseAndValidateForecastHours(forecast_hours);
        if (hours == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter",
                    "forecast_hours must be comma separated, unique(first-wins), max 5, and multiples of 3 in [3,168]");
        }
        if (!validateBbox(bbox)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_bbox", "bbox is invalid or out of allowed range");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("variables", String.join(",", vp.vars()));
        query.put("run_time", run_time);
        query.put("forecast_hours", joinInts(hours));
        query.put("bbox", bbox);
        return proxyGet("/api/v1/forecasts/3h", query);
    }

    private ResponseEntity<String> validateAuth(String auth) {
        if (auth == null || auth.isBlank()) {
            return error(HttpStatus.UNAUTHORIZED, "authentication_required", "Authorization header is required");
        }
        if (serverToken == null || serverToken.isBlank()) {
            return error(HttpStatus.UNAUTHORIZED, "authentication_failed", "token not configured on server");
        }
        String expected = "Bearer " + serverToken;
        if (!expected.equals(auth)) {
            return error(HttpStatus.UNAUTHORIZED, "authentication_failed", "token is invalid or expired");
        }
        return null;
    }

    private VariableParse parseAndValidateVariables(String raw) {
        if (raw == null || raw.isBlank()) {
            return VariableParse.err("invalid_parameter", "variables must be comma separated and max 3");
        }
        List<String> vars = new ArrayList<>();
        for (String p : raw.split(",")) {
            String v = p == null ? "" : p.trim().toUpperCase();
            if (v.isBlank()) {
                return VariableParse.err("invalid_parameter", "variables must be comma separated and max 3");
            }
            if (!VARIABLES.contains(v)) {
                return VariableParse.err("unknown_variable", "variables contains unsupported variable");
            }
            vars.add(v);
        }
        if (vars.isEmpty() || vars.size() > 3) {
            return VariableParse.err("invalid_parameter", "variables must be comma separated and max 3");
        }
        return VariableParse.ok(vars);
    }

    private boolean validateTime(String t) {
        if (t == null || !t.matches("\\d{12}")) {
            return false;
        }
        try {
            LocalDateTime.parse(t, TIME_FMT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean validateRunTime(String t) {
        if (!validateTime(t)) {
            return false;
        }
        return t.endsWith("0800") || t.endsWith("2000");
    }

    private List<Integer> parseAndValidateForecastHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LinkedHashSet<Integer> uniq = new LinkedHashSet<>();
        try {
            for (String p : raw.split(",")) {
                String s = p == null ? "" : p.trim();
                if (s.isBlank()) {
                    return null;
                }
                int v = Integer.parseInt(s);
                if (v < 3 || v > 168 || v % 3 != 0) {
                    return null;
                }
                uniq.add(v);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (uniq.isEmpty() || uniq.size() > 5) {
            return null;
        }
        return new ArrayList<>(uniq);
    }

    private boolean validateBbox(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        List<String> parts = Arrays.stream(raw.split(",")).map(String::trim).toList();
        if (parts.size() != 4) {
            return false;
        }
        try {
            double minLon = Double.parseDouble(parts.get(0));
            double minLat = Double.parseDouble(parts.get(1));
            double maxLon = Double.parseDouble(parts.get(2));
            double maxLat = Double.parseDouble(parts.get(3));
            if (maxLon <= minLon || maxLat <= minLat) {
                return false;
            }
            return minLon >= 90.0 && maxLon <= 170.0 && minLat >= -10.0 && maxLat <= 30.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private ResponseEntity<String> proxyGet(String path, Map<String, String> query) {
        if (baseUrl == null || baseUrl.isBlank() || serverToken == null || serverToken.isBlank()) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "server weather config is incomplete");
        }
        String cacheKey = path + "?" + HttpUtil.encodeQuery(query);
        long now = System.currentTimeMillis();
        // 1) 同查询串 10 分钟内命中缓存：不回源
        CachedProxy cached = queryCache.get(cacheKey);
        if (cached != null && now - cached.storedAt() < PROXY_CACHE_TTL_MS) {
            cntCacheHit++;
            return ResponseEntity.status(cached.status()).contentType(MediaType.APPLICATION_JSON).body(cached.body());
        }
        // 2) 熔断静默期内不回源（401/连续失败后严禁重试轰炸）
        if (now < circuitUntilMs) {
            cntThrottled++;
            return tooManyRequests("upstream circuit open", Math.max(1, (circuitUntilMs - now) / 1000));
        }
        // 3) 最小回源间隔未到：让调用方稍后再试，而不是排队打满
        long sinceLast = now - lastUpstreamAtMs;
        if (sinceLast < upstreamMinIntervalMs()) {
            cntThrottled++;
            return tooManyRequests("upstream min interval respected",
                    Math.max(1, (upstreamMinIntervalMs() - sinceLast) / 1000));
        }
        lastUpstreamAtMs = now;
        try {
            String url = HttpUtil.joinUrl(baseUrl, path) + "?" + HttpUtil.encodeQuery(query);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + serverToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            cntUpstream++;
            if (resp.statusCode() == 401) {
                // Token 无效：立即熔断，静默期内不再发起任何回源
                openCircuit(now);
            } else if (resp.statusCode() >= 500) {
                registerFailure(now);
            } else {
                consecutiveFailures = 0;
                circuitUntilMs = 0;
                // 仅缓存成功响应（2xx）；业务错误不缓存，保证参数纠偏后可立即重试（仍受节流）
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    queryCache.put(cacheKey, new CachedProxy(resp.statusCode(), resp.body(), now));
                }
            }
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(resp.body());
        } catch (Exception e) {
            registerFailure(now);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "upstream request failed");
        }
    }

    /** 频度自检端点：只暴露计数与守护状态，不含密钥/Token 信息 */
    @GetMapping("/rate-stats")
    public Map<String, Object> rateStats() {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upstreamRequests", cntUpstream);
        m.put("cacheHits", cntCacheHit);
        m.put("throttled", cntThrottled);
        m.put("circuitOpens", cntCircuitOpen);
        m.put("minIntervalSeconds", upstreamMinIntervalSeconds);
        m.put("cacheTtlMs", PROXY_CACHE_TTL_MS);
        m.put("secondsSinceLastUpstream", lastUpstreamAtMs == 0 ? null : (now - lastUpstreamAtMs) / 1000);
        m.put("circuitOpen", now < circuitUntilMs);
        m.put("consecutiveFailures", consecutiveFailures);
        return m;
    }

    private long upstreamMinIntervalMs() {
        return Math.max(1, upstreamMinIntervalSeconds) * 1000L;
    }

    private synchronized void registerFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= Math.max(1, circuitFailureThreshold)) {
            openCircuit(now);
        }
    }

    private synchronized void openCircuit(long now) {
        circuitUntilMs = now + Math.max(1, circuitOpenSeconds) * 1000L;
        consecutiveFailures = 0;
        cntCircuitOpen++;
    }

    private ResponseEntity<String> tooManyRequests(String reason, long retryAfterSec) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "rate_limited");
        body.put("message", reason + "（合规：禁止暴力访问上游气象接口）");
        try {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfterSec))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"rate_limited\",\"message\":\"" + reason + "\"}");
        }
    }

    private ResponseEntity<String> error(HttpStatus status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("request_id", "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body));
        } catch (Exception e) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
        }
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
