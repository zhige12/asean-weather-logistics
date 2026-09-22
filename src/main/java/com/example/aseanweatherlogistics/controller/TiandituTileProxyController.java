package com.example.aseanweatherlogistics.controller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;

/**
 * 天地图瓦片同源代理（治本版）：前端只请求同源 {@code /api/tianditu/{layer}/{z}/{x}/{y}.png}，
 * 密钥只存后端，回源带合规 Referer/浏览器 UA；瓦片两级缓存（内存 LRU + 磁盘文件），
 * 热门瓦片全生命周期只回源一次，配额消耗压到最低。
 *
 * <p>多密钥轮询：{@code tianditu.keys} 配置逗号分隔的密钥池，按游标轮转取 key；
 * <b>熔断是 per-key 的</b>（某 key 被限流只冷却它自己 60s，其余 key 继续服务）——
 * 旧版的全局熔断会让一个被限流的 key 拖垮全部图层，是当年弃用代理的主因之一，绝不能复活。
 *
 * <p><b>状态码约定（这是本类最容易踩错的地方）</b>：
 * <ul>
 *   <li>{@code 404} = <b>真的没有这张瓦片</b>（layer 非法、坐标越界、上游明确 404）。</li>
 *   <li>{@code 503} = <b>这张瓦片本应有数据、但当下取不到</b>（全部 key 都在冷却/限流、
 *       超时、密钥缺失）。前端 Leaflet 派发 tileerror → 隐藏破瓦/触发降级链，信号不能丢。</li>
 * </ul>
 * 绝不能把 503 塌缩成 404：404 会让前端以为「无数据」而永不重试，限流缺瓦会变成永久空洞。
 */
@RestController
public class TiandituTileProxyController {

    /** 放行的图层：img_w=影像底图、cia_w=影像注记、vec_w=矢量底图(底图4)、cva_w=矢量注记。 */
    private static final Set<String> ALLOWED_LAYERS = Set.of("img_w", "cia_w", "vec_w", "cva_w");
    private static final int MAX_ZOOM = 20;

    /** 某 key 被上游明确限流（429 / 403+限流文案）后的冷却窗口：只冷却这个 key 自己。 */
    private static final long KEY_THROTTLED_MS = 60_000L;
    /** 某 key 超时/网络抖动的短冷却，避免每张瓦片都各等满 timeout。 */
    private static final long KEY_TRANSIENT_MS = 8_000L;
    /**
     * 同时回源的上游请求上限。实测单张冷瓦回源约 0.5s：手机进导航一屏 40~80 张瓦片，
     * 闸门过窄会把铺图时间拉到十几秒，还会因等不到许可而 503 → 触发司机端降级链误降级。
     * 16 并发配 5-key 轮转，均摊到每个 key 的频率与旧直连时代相当，WAF 风险可控。
     */
    private static final int MAX_UPSTREAM_INFLIGHT = 16;
    /**
     * 等回源许可的时长：宁可排队等到（最多 ~5s 铺完一屏），不可提前 503——
     * 503 会被前端计入 tileerror 预算，连续几张就把底图降级到 OSM/离线，反而更慢。
     * 同瓦去重（inflight）保证等待者不会重复烧配额。
     */
    private static final long PERMIT_WAIT_MS = 10_000L;
    /** 内存热缓存条数上限（磁盘是主缓存，内存只挡热点）：单张 5~30KB，1024 张最坏约 30MB。 */
    private static final int MEM_CACHE_MAX_ENTRIES = 1024;
    private static final int RETRY_AFTER_SECONDS = 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 回源并发闸门。 */
    private final Semaphore upstream = new Semaphore(MAX_UPSTREAM_INFLIGHT);

    /** 内存热缓存（LRU）：accessOrder=true 的 LinkedHashMap 即 LRU，读多写少且量不大，同步包装即可。 */
    private final Map<String, CachedTile> memCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedTile> eldest) {
                    return size() > MEM_CACHE_MAX_ENTRIES;
                }
            });

    /** 同瓦去重：一屏几十张瓦片里重复坐标/预览预取与运行时撞车时，只放一个回源请求。 */
    private final ConcurrentHashMap<String, CompletableFuture<CachedTile>> inflight = new ConcurrentHashMap<>();

    /** 每个 key 的冷却截止时刻（epoch millis），与 keys 平行下标；0 表示未冷却。 */
    private long[] keyCooldownUntil = new long[0];
    /** key 轮转游标。 */
    private final AtomicInteger keyCursor = new AtomicInteger();

    private record CachedTile(byte[] body, String contentType) {
    }

    /** 回源结果：tile!=null 成功；notFound=true 上游明确无此瓦片（不重试其他 key）。 */
    private record FetchResult(CachedTile tile, boolean notFound) {
    }

    /** 后端密钥池（唯一存放点）：tianditu.keys 逗号分隔；旧 tianditu.tk 作为池首兼容保留。 */
    @Value("${tianditu.tk:}")
    private String configuredTk;

    @Value("${tianditu.keys:}")
    private String configuredKeys;

    /** 兜底 Referer：需与密钥授权域名一致；前端同源请求的 Referer 会优先透传。 */
    @Value("${tianditu.referer:}")
    private String configuredReferer;

    @Value("${tianditu.timeout-seconds:6}")
    private long timeoutSeconds;

    /** 磁盘缓存根目录：瓦片内容按 layer/z/x/y 不可变，落盘后重启不丢、配额只花一次。 */
    @Value("${tianditu.cache-dir:data/tile-cache}")
    private String cacheDir;

    /**
     * 回源 UA。天地图「浏览器端」密钥会校验请求方是不是浏览器，
     * 返回过 {@code 301012 权限类型错误 / Key权限类型为:浏览器端，请使用浏览器访问}，
     * 所以这里必须像一个真实浏览器，而不是自定义标识。
     */
    @Value("${tianditu.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}")
    private String upstreamUserAgent;

    private List<String> keys = List.of();

    @PostConstruct
    void initKeys() {
        List<String> merged = new ArrayList<>();
        if (configuredTk != null && !configuredTk.isBlank()) merged.add(configuredTk.trim());
        if (configuredKeys != null && !configuredKeys.isBlank()) {
            Arrays.stream(configuredKeys.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).forEach(k -> { if (!merged.contains(k)) merged.add(k); });
        }
        keys = List.copyOf(merged);
        keyCooldownUntil = new long[keys.size()];
        if (keys.isEmpty()) {
            System.err.println("[tianditu-proxy] 未配置任何密钥（tianditu.keys / tianditu.tk），瓦片将全部 503");
        } else {
            System.out.println("[tianditu-proxy] 密钥池 " + keys.size() + " 个 key，磁盘缓存目录 " + cacheDir);
        }
    }

    @GetMapping("/api/tianditu/{layer}/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> tile(@PathVariable String layer,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestHeader(value = "Referer", required = false) String referer) {
        // 1) 参数与白名单校验：这类是「确实没有这张瓦片」，返回 404 让前端干净跳过
        if (!ALLOWED_LAYERS.contains(layer) || z < 0 || z > MAX_ZOOM) {
            return notFound();
        }
        int max = 1 << z; // 2^z，该缩放级别下的瓦片行列上限
        if (x < 0 || y < 0 || x >= max || y >= max) {
            return notFound();
        }
        if (keys.isEmpty()) {
            // 没密钥不是「没数据」：必须让前端感知到并降级，否则底图会永久停在模糊补位上
            return unavailable();
        }

        String ck = layer + '/' + z + '/' + x + '/' + y;

        // 2) 内存热缓存
        CachedTile hit = memCache.get(ck);
        if (hit != null) {
            return ok(hit);
        }
        // 3) 磁盘缓存：命中即回填内存，全程不打上游、不烧配额
        CachedTile disk = readDisk(layer, z, x, y);
        if (disk != null) {
            memCache.put(ck, disk);
            return ok(disk);
        }

        // 4) 同瓦去重：已有请求在回源就等它的结果，避免冷启动瞬间重复烧配额
        CompletableFuture<CachedTile> mine = new CompletableFuture<>();
        CompletableFuture<CachedTile> prev = inflight.putIfAbsent(ck, mine);
        if (prev != null) {
            try {
                CachedTile shared = prev.get(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
                return shared != null ? ok(shared) : unavailable();
            } catch (Exception e) {
                return unavailable();
            }
        }
        boolean acquired;
        try {
            acquired = upstream.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inflight.remove(ck);
            return unavailable();
        }
        try {
            if (!acquired) {
                mine.complete(null);
                return unavailable();
            }
            FetchResult fr = fetchWithKeyRotation(layer, z, x, y, referer);
            if (fr.notFound()) {
                mine.complete(null);
                return notFound(); // 上游明确没有这张瓦片，交给前端父/子级补位
            }
            if (fr.tile() == null) {
                mine.complete(null);
                return unavailable(); // 全部 key 都在冷却/失败：503 让前端降级链收到信号
            }
            memCache.put(ck, fr.tile());
            writeDisk(layer, z, x, y, fr.tile());
            mine.complete(fr.tile());
            return ok(fr.tile());
        } finally {
            if (acquired) upstream.release();
            inflight.remove(ck);
        }
    }

    /**
     * 轮转取 key 回源：从游标位置扫一圈密钥池，跳过冷却中的 key；
     * 某 key 被限流只冷却它自己（per-key 熔断），立刻换下一个 key 重试同一张瓦片。
     */
    private FetchResult fetchWithKeyRotation(String layer, int z, int x, int y, String referer) {
        int n = keys.size();
        long now = System.currentTimeMillis();
        for (int attempt = 0; attempt < n; attempt++) {
            int i = Math.floorMod(keyCursor.getAndIncrement(), n);
            if (keyCooldownUntil[i] > now) continue;
            FetchResult fr = fetchOne(layer, z, x, y, keys.get(i), referer);
            if (fr.notFound()) return fr;
            if (fr.tile() != null) return fr;
            // fetchOne 内部已按失败类型给该 key 记冷却；继续试下一个 key
            now = System.currentTimeMillis();
        }
        return new FetchResult(null, false);
    }

    private FetchResult fetchOne(String layer, int z, int x, int y, String key, String referer) {
        // 组装上游 URL：沿用已验证可用的 DataServer XYZ 风格端点（_w 球面墨卡托）。
        // t0~t7 子域按 (x+y) 取模稳定映射，保证同一瓦片命中同一上游节点便于其缓存。
        int sub = Math.floorMod(x + y, 8);
        String url = "https://t" + sub + ".tianditu.gov.cn/DataServer?T=" + layer
                + "&x=" + x + "&y=" + y + "&l=" + z + "&tk=" + key;

        // Referer 透传：优先用发起页的真实 Referer（密钥的域名授权以此为准），缺省回落配置值
        String ref = (referer != null && !referer.isBlank()) ? referer
                : (configuredReferer == null || configuredReferer.isBlank() ? "http://localhost/" : configuredReferer);

        int idx = keys.indexOf(key);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Referer", ref)
                    .header("User-Agent", upstreamUserAgent)
                    .header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            byte[] body = resp.body();
            String ct = resp.headers().firstValue("Content-Type").orElse("image/png");

            if (status == 404) {
                return new FetchResult(null, true);
            }
            // 天地图会用 200/403/429 + JSON 回「权限类型错误」「该tk已限流」，
            // 这种响应不是图片，缓存下来就是一个永久空洞，必须按取不到处理并冷却该 key。
            boolean throttled = status == 429 || status == 403 || status >= 500
                    || status != 200 || body == null || body.length == 0 || !ct.startsWith("image/");
            if (throttled) {
                cooldown(idx, KEY_THROTTLED_MS);
                return new FetchResult(null, false);
            }
            return new FetchResult(new CachedTile(body, layerContentType(layer)), false);
        } catch (Exception e) {
            // 超时/网络异常：短冷却该 key，换 key 重试
            cooldown(idx, KEY_TRANSIENT_MS);
            return new FetchResult(null, false);
        }
    }

    private void cooldown(int idx, long ms) {
        if (idx >= 0 && idx < keyCooldownUntil.length) {
            keyCooldownUntil[idx] = System.currentTimeMillis() + ms;
        }
    }

    // ---------- 磁盘缓存：{cacheDir}/{layer}/{z}/{x}/{y}.{ext}，内容不可变、重启不丢 ----------

    private Path diskPath(String layer, int z, int x, int y) {
        String ext = "img_w".equals(layer) ? "jpg" : "png";
        return Paths.get(cacheDir, layer, String.valueOf(z), String.valueOf(x), y + "." + ext);
    }

    private CachedTile readDisk(String layer, int z, int x, int y) {
        try {
            Path p = diskPath(layer, z, x, y);
            if (!Files.isRegularFile(p)) return null;
            byte[] body = Files.readAllBytes(p);
            return body.length == 0 ? null : new CachedTile(body, layerContentType(layer));
        } catch (IOException e) {
            return null; // 磁盘抖动当未命中，回源兜底
        }
    }

    private void writeDisk(String layer, int z, int x, int y, CachedTile tile) {
        try {
            Path p = diskPath(layer, z, x, y);
            Files.createDirectories(p.getParent());
            Files.write(p, tile.body());
        } catch (IOException e) {
            // 磁盘写失败不影响本次响应，下次未命中再回源即可
        }
    }

    /** 上游 Content-Type 不标准（img_w 回 image/jpg），按图层归一成标准类型再回吐。 */
    private String layerContentType(String layer) {
        return "img_w".equals(layer) ? "image/jpeg" : "image/png";
    }

    private ResponseEntity<byte[]> ok(CachedTile tile) {
        return ResponseEntity.ok()
                .contentType(safeMediaType(tile.contentType()))
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                // 瓦片内容不可变（layer/z/x/y 唯一），长缓存让浏览器/WebView 与预取都命中本地
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(tile.body());
    }

    /** 上游 Content-Type 畸形时不能让 parseMediaType 抛成 500，回落到 png 即可。 */
    private MediaType safeMediaType(String ct) {
        try {
            return MediaType.parseMediaType(ct);
        } catch (Exception e) {
            return MediaType.IMAGE_PNG;
        }
    }

    /** 确实没有这张瓦片：错误响应不能被 WebView 缓存住。 */
    private ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /** 本应有数据但当下取不到：前端据此隐藏破瓦/触发降级链。 */
    private ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
