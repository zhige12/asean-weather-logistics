package com.example.aseanweatherlogistics.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 通关时效「真实抓取」服务。
 *
 * <p>与包内静态基线（{@code data/customs-efficiency.json}，人工整理）不同，本服务真的发起一次
 * 出站 HTTP 请求，抓取政府口岸主管部门发布的公开通报页，用正则从正文里抽出官方口径的
 * 「进口 / 出口整体通关时间」（小时），连同<b>出处</b>（来源 URL、发布日期、命中的原句、抓取时刻）
 * 一起返回，供界面做可核验的溯源展示。
 *
 * <p>合规与稳健性沿用实时气象那套纪律（见 {@link RealWeatherService} / {@link ContestForecastService}）：
 * 通报是月/季度级低频更新，故默认最小回源间隔 24h；窗口内一律回上次缓存，绝不反复打上游；
 * 连续失败打开熔断静默；<b>抓不到就如实回 {@code ok=false} + 错误原因，由上层降级到包内基线</b>，
 * 绝不把「没抓到」伪装成「抓到了」，也不静默用假数冒充实时值。
 *
 * <p>口径边界：公告里的「进口整体通关时间」是全区口岸平均（含报关、查验、放行全链条，量级以小时计），
 * 与包内按口岸的「车辆过口时效」（{@code RoadEdge.customsDelayHours}，量级 1~3h）是两个不同指标，
 * 因此本服务的数值只作<b>可溯源的官方参考</b>呈现，不直接覆盖算路边权，避免把两种口径混为一谈。
 */
@Service
public class CustomsBulletinFetcher {

    /** 正文里「进口(整体)通关时间…N 小时」的抽取式；数字与关键词之间允许少量非数字连接词（如“缩减至”）。 */
    private static final Pattern IMPORT_P =
            Pattern.compile("进口(?:整体)?通关时间[^0-9。]{0,20}(\\d+(?:\\.\\d+)?)\\s*小时");
    private static final Pattern EXPORT_P =
            Pattern.compile("出口(?:整体)?通关时间[^0-9。]{0,20}(\\d+(?:\\.\\d+)?)\\s*小时");
    /** 正文中的发布日期（形如 2025-06-18 / 2025/06/18）。 */
    private static final Pattern DATE_P = Pattern.compile("(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})");

    private static final String USER_AGENT = "asean-weather-logistics/1.0 (customs-bulletin-fetcher)";

    @Value("${customs.bulletin.enabled:true}")
    private boolean enabled;
    @Value("${customs.bulletin.url:http://wsb.gxzf.gov.cn/yhjw_48207/gxydm_48209/t21212362.shtml}")
    private String bulletinUrl;
    @Value("${customs.bulletin.timeout-seconds:15}")
    private long timeoutSeconds;
    /** 最小回源间隔（秒）：通报低频，默认一天一次；窗口内直接回缓存。 */
    @Value("${customs.bulletin.min-request-interval-seconds:86400}")
    private long minIntervalSeconds;
    /** 手动刷新也要有硬地板（秒），防止刷新按钮被点成变相暴力访问。 */
    @Value("${customs.bulletin.refresh-floor-seconds:30}")
    private long refreshFloorSeconds;
    @Value("${customs.bulletin.circuit-failure-threshold:3}")
    private long circuitFailureThreshold;
    @Value("${customs.bulletin.circuit-open-seconds:3600}")
    private long circuitOpenSeconds;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 最近一次成功抓取（含出处与数值）；未成功过时为 null。 */
    private volatile Bulletin last;
    /** 最近一次失败原因（成功时清空）。 */
    private volatile String lastError;
    private volatile long lastRequestAtMs;
    private volatile long circuitUntilMs;
    private long consecutiveFailures;
    // 审计计数：供界面/探针核验「真的回过源、节流生效」
    private volatile long cntUpstream;
    private volatile long cntCacheHit;
    private volatile long cntThrottled;
    private volatile long cntCircuitOpen;

    /** 一次成功抓取的产物（不可变）。 */
    public record Bulletin(Double importHours, Double exportHours, String sourceUrl,
                           String publishDate, String quote, long fetchedAt) {
    }

    /**
     * 取得通关时效公告。
     *
     * @param forceRefresh 手动刷新：可越过常规 24h 窗口，但仍受 {@code refreshFloorSeconds} 硬地板与熔断静默约束
     */
    public synchronized Map<String, Object> bulletin(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!enabled) {
            return assemble(false, "抓取未启用（customs.bulletin.enabled=false）", false, now);
        }
        // 1) 熔断静默期：一律不回源，回上次缓存或错误
        if (now < circuitUntilMs) {
            cntThrottled++;
            cntCircuitOpen++;
            return assemble(last != null, "上游连续失败，熔断静默中（"
                    + Math.max(1, (circuitUntilMs - now) / 1000) + "s 后恢复）", false, now);
        }
        // 2) 最小回源间隔：非强刷且未到窗口 → 回缓存；强刷也只允许越过窗口、不越过硬地板
        long floor = forceRefresh ? Math.min(minIntervalSeconds, Math.max(1, refreshFloorSeconds)) : minIntervalSeconds;
        if (lastRequestAtMs != 0 && now - lastRequestAtMs < floor * 1000L) {
            cntThrottled++;
            // 已有缓存则视为命中；首次还没回过源时不拦，放行去建基线
            if (last != null) {
                cntCacheHit++;
                return assemble(true, null, true, now);
            }
        }
        lastRequestAtMs = now;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(bulletinUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            cntUpstream++;
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                registerFailure(now);
                lastError = "上游返回 HTTP " + code;
                return assemble(last != null, lastError, false, now);
            }
            String text = stripHtml(decode(resp));
            Double imp = firstNumber(IMPORT_P, text);
            Double exp = firstNumber(EXPORT_P, text);
            if (imp == null && exp == null) {
                // 抓到了页但抽不到数字：口径可能已变，如实报错并降级，不编数字
                registerFailure(now);
                lastError = "已在正文中抽取通关时长数字，但本页未命中（口径可能调整）";
                return assemble(last != null, lastError, false, now);
            }
            consecutiveFailures = 0;
            circuitUntilMs = 0;
            lastError = null;
            last = new Bulletin(imp, exp, bulletinUrl, publishDate(text),
                    quoteAround(text, imp != null ? IMPORT_P : EXPORT_P), now);
            return assemble(true, null, false, now);
        } catch (Exception e) {
            registerFailure(now);
            lastError = "回源失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ("（" + e.getMessage() + "）"));
            return assemble(last != null, lastError, false, now);
        }
    }

    private void registerFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= Math.max(1, circuitFailureThreshold)) {
            circuitUntilMs = now + circuitOpenSeconds * 1000L;
            consecutiveFailures = 0;
        }
    }

    private Map<String, Object> assemble(boolean ok, String error, boolean fromCache, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("ok", ok && last != null);
        m.put("fromCache", fromCache);
        m.put("source", "官方公告抓取");
        m.put("metric", "全区口岸整体通关时间（小时）·官方口径");
        if (last != null) {
            m.put("importHours", last.importHours());
            m.put("exportHours", last.exportHours());
            m.put("sourceUrl", last.sourceUrl());
            m.put("publishDate", last.publishDate());
            m.put("quote", last.quote());
            m.put("fetchedAt", last.fetchedAt());
            m.put("fetchedAtText", formatTs(last.fetchedAt()));
            m.put("ageSeconds", Math.max(0, (now - last.fetchedAt()) / 1000));
        }
        if (error != null) {
            m.put("error", error);
        }
        m.put("circuitOpenSeconds", Math.max(0, (circuitUntilMs - now) / 1000));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("upstreamRequests", cntUpstream);
        stats.put("cacheHits", cntCacheHit);
        stats.put("throttled", cntThrottled);
        stats.put("circuitOpens", cntCircuitOpen);
        stats.put("minIntervalSeconds", minIntervalSeconds);
        m.put("stats", stats);
        return m;
    }

    /** 抓字节流后按 Content-Type 声明的字符集解码；无声明则先试 UTF-8，抽不到中文关键词再退 GBK（政府站混用）。 */
    private String decode(HttpResponse<byte[]> resp) {
        byte[] bytes = resp.body();
        String ct = resp.headers().firstValue("content-type").orElse("");
        Charset declared = charsetFromContentType(ct);
        if (declared != null) {
            return new String(bytes, declared);
        }
        String utf = new String(bytes, StandardCharsets.UTF_8);
        if (utf.contains("通关")) {
            return utf;
        }
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception ignore) {
            return utf;
        }
    }

    private Charset charsetFromContentType(String ct) {
        if (ct == null || ct.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
        if (!m.find()) {
            return null;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 去标签 + 还原常见实体 + 折叠空白，得到可比对的正文。 */
    private String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ");
    }

    private Double firstNumber(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? parseDouble(m.group(1)) : null;
    }

    private Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 命中数字所在的那句（按句号裁剪），作为可核验出处原句。 */
    private String quoteAround(String text, Pattern p) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        // 以“数字”为中心取窗口，保证关键数值一定落在原句里（溯源的意义在于能对上号），
        // 再尽量向两侧吸附到句号边界，取不到就用固定窗口。
        int numStart = m.start(1);   // 数字本身起点
        int numEnd = m.end(1);
        int start = text.lastIndexOf('。', m.start());
        start = start < 0 || numStart - start > 80 ? Math.max(0, numStart - 40) : start + 1;
        int stop = text.indexOf('。', numEnd);
        stop = stop < 0 || stop - numEnd > 60 ? Math.min(text.length(), numEnd + 30) : stop + 1;
        String q = text.substring(start, stop).trim();
        boolean cutFront = start > 0;
        boolean cutBack = stop < text.length();
        if (q.length() > 160) {
            int anchor = q.indexOf(text.substring(numStart, numEnd));
            int from = Math.max(0, anchor - 60);
            q = q.substring(from, Math.min(q.length(), from + 160));
            cutFront = cutFront || from > 0;
            cutBack = cutBack || (from + 160) < text.length();
        }
        return (cutFront ? "…" : "") + q + (cutBack ? "…" : "");
    }

    private String publishDate(String text) {
        Matcher m = DATE_P.matcher(text);
        if (m.find()) {
            try {
                LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                return d.toString();
            } catch (Exception ignore) {
                return m.group();
            }
        }
        return null;
    }

    private String formatTs(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }
}
