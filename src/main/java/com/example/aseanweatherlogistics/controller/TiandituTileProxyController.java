package com.example.aseanweatherlogistics.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 天地图瓦片同源代理。
 *
 * <p>为什么需要它：司机端要把底图从 Leaflet 的 CPU 栅格(&lt;img&gt; 逐块平移) 换成
 * MapLibre 的 GPU 合成渲染。而 MapLibre 用 WebGL 上屏时，栅格瓦片要作为纹理上传，
 * 浏览器要求同源或带 CORS 头——天地图在线瓦片的 CORS 与防盗链 Referer 校验不稳定
 * （无/错误 Referer 返回 418），前端直连时好时坏。
 *
 * <p>本代理让前端只请求<b>同源</b>的 {@code /api/tianditu/...}：后端持有密钥、带上
 * 合规 Referer 去取上游瓦片，再以 {@code Access-Control-Allow-Origin} + 长缓存回吐。
 * 打包进 Capacitor 的 WebView 由后端同源托管（capacitor server.url 指向本服务），
 * 开发态由 Vite 的 {@code /api} 代理转发，两种场景都命中这里，无需额外跨域配置。
 *
 * <p><b>状态码约定（这是本类最容易踩错的地方）</b>：
 * <ul>
 *   <li>{@code 404} = <b>真的没有这张瓦片</b>（layer 非法、坐标越界、上游明确 404）。
 *       MapLibre 视其为「无数据」，干净跳过并继续向父/子级取瓦片补位。</li>
 *   <li>{@code 503} = <b>这张瓦片本应有数据、但当下取不到</b>（上游限流 429、超时、
 *       密钥缺失、上游用 200+JSON 回「权限类型错误 / 该tk已限流」）。</li>
 * </ul>
 *
 * <p>绝不能把 503 也塌缩成 404。MapLibre 的 {@code SourceCache#_loadTile} 对
 * {@code status === 404} 的错误<b>既不派发 error 事件、也永不再重试</b>
 * （{@code SourceCache#reload} 会跳过 {@code state === 'errored'} 的瓦片），
 * 于是限流造成的缺瓦会被父级瓦片拉伸补位，在地图上留下一片「跟着地图一起移动」的
 * 模糊块，而前端的天地图→OSM→本地矢量降级链收不到任何信号、不会切换。
 */
@RestController
public class TiandituTileProxyController {

    /** 放行的图层：img_w=影像底图、cia_w=影像注记、vec_w=矢量底图(底图4)、cva_w=矢量注记。 */
    private static final Set<String> ALLOWED_LAYERS = Set.of("img_w", "cia_w", "vec_w", "cva_w");
    private static final int MAX_ZOOM = 20;

    /**
     * 上游明确限流（429 / tk 已限流 / 权限类型错误）后的熔断窗口。
     * 这段时间内不再回源：继续打只会加深限流并白烧配额，直接回 503 让前端降级。
     */
    private static final long BREAKER_THROTTLED_MS = 20_000L;
    /** 超时/网络抖动的短熔断，避免每张瓦片都各等满 timeout。 */
    private static final long BREAKER_TRANSIENT_MS = 8_000L;
    /** 同时回源的上游请求上限：手机一屏要 40~80 张瓦片，全直冲天地图必然撞上按秒限流。 */
    private static final int MAX_UPSTREAM_INFLIGHT = 4;
    /** 等不到回源许可就快速失败（503 可重试），不把 Tomcat 工作线程堆在队列里。 */
    private static final long PERMIT_WAIT_MS = 2_000L;
    /** 成功瓦片的内存缓存条数上限：单张 png 约 5~30KB，4096 张最坏约 120MB。 */
    private static final int CACHE_MAX_ENTRIES = 4096;
    private static final int RETRY_AFTER_SECONDS = 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 回源并发闸门。 */
    private final Semaphore upstream = new Semaphore(MAX_UPSTREAM_INFLIGHT);
    /** 熔断截止时刻（epoch millis）；0 表示未熔断。 */
    private final AtomicLong breakerUntil = new AtomicLong();

    /**
     * 成功瓦片的 LRU 缓存。瓦片内容按 layer/z/x/y 不可变，回过一次的就不该再打上游——
     * 司机来回拖动地图时，这是把请求量压在天地图配额之下的主要手段。
     * accessOrder=true 的 LinkedHashMap 即 LRU；用同步包装即可，读多写少且量不大。
     */
    private final Map<String, CachedTile> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedTile> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private record CachedTile(byte[] body, String contentType) {
    }

    /** 后端兜底密钥（可选）：优先用前端传来的 tk，缺省时回落到这里。 */
    @Value("${tianditu.tk:}")
    private String configuredTk;

    /** 兜底 Referer：转发失败或前端 Worker 请求不带 Referer 时使用，需与密钥授权域名一致。 */
    @Value("${tianditu.referer:}")
    private String configuredReferer;

    @Value("${tianditu.timeout-seconds:6}")
    private long timeoutSeconds;

    /**
     * 回源 UA。天地图「浏览器端」密钥会校验请求方是不是浏览器，
     * 返回过 {@code 301012 权限类型错误 / Key权限类型为:浏览器端，请使用浏览器访问}，
     * 所以这里必须像一个真实浏览器，而不是自定义标识。
     */
    @Value("${tianditu.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}")
    private String upstreamUserAgent;

    @GetMapping("/api/tianditu/{layer}/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> tile(@PathVariable String layer,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(required = false) String tk,
                                       @RequestHeader(value = "Referer", required = false) String referer) {
        // 1) 参数与白名单校验：这类是「确实没有这张瓦片」，返回 404 让 MapLibre 干净跳过
        if (!ALLOWED_LAYERS.contains(layer) || z < 0 || z > MAX_ZOOM) {
            return notFound();
        }
        int max = 1 << z; // 2^z，该缩放级别下的瓦片行列上限
        if (x < 0 || y < 0 || x >= max || y >= max) {
            return notFound();
        }
        String key = (tk != null && !tk.isBlank()) ? tk.trim() : (configuredTk == null ? "" : configuredTk.trim());
        if (key.isEmpty()) {
            // 没密钥不是「没数据」：必须让前端感知到并降级，否则底图会永久停在模糊补位上
            return unavailable();
        }

        String ck = layer + '/' + z + '/' + x + '/' + y;
        CachedTile hit = cache.get(ck);
        if (hit != null) {
            return ResponseEntity.ok()
                    .contentType(safeMediaType(hit.contentType()))
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                    .body(hit.body());
        }

        // 2) 熔断窗口内直接拒绝回源：把上游的限流信号原样转成 503 交给前端处理
        if (breakerUntil.get() > System.currentTimeMillis()) {
            return unavailable();
        }

        // 3) 并发闸门：一屏几十张瓦片同时打上游必然触发按秒限流，超出的快速失败
        boolean acquired;
        try {
            acquired = upstream.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable();
        }
        if (!acquired) {
            return unavailable();
        }
        try {
            return fetch(layer, z, x, y, ck, key, referer);
        } finally {
            upstream.release();
        }
    }

    private ResponseEntity<byte[]> fetch(String layer, int z, int x, int y, String ck, String key, String referer) {
        // 组装上游 URL：沿用前端已验证可用的 DataServer XYZ 风格端点（_w 球面墨卡托）。
        // t0~t7 轮询分摊压力；子域按 (x+y) 取模稳定映射，保证同一瓦片命中同一节点便于上游缓存。
        int sub = Math.floorMod(x + y, 8);
        // 参数顺序与命名刻意与 tileSources.js 中已验证可用的在线源保持一致（T/x/y/l/tk）
        String url = "https://t" + sub + ".tianditu.gov.cn/DataServer?T=" + layer
                + "&x=" + x + "&y=" + y + "&l=" + z + "&tk=" + key;

        // Referer 透传：优先用发起页的真实 Referer（密钥的域名授权以此为准），
        // 前端 Web Worker 拉瓦片可能不带 Referer，则回落到配置值 / localhost。
        String ref = (referer != null && !referer.isBlank()) ? referer
                : (configuredReferer.isBlank() ? "http://localhost/" : configuredReferer);

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
                return notFound(); // 上游明确没有这张瓦片，交给 MapLibre 向父/子级补位
            }
            if (status == 429 || status >= 500) {
                breakerUntil.set(System.currentTimeMillis() + BREAKER_THROTTLED_MS);
                return unavailable();
            }
            // 天地图会用 200/403 + JSON 回「权限类型错误」「该tk已限流」，
            // 这种响应不是图片，缓存下来就是一个永久空洞，必须按取不到处理。
            if (status != 200 || body == null || body.length == 0 || !ct.startsWith("image/")) {
                if (status == 403 || !ct.startsWith("image/")) {
                    breakerUntil.set(System.currentTimeMillis() + BREAKER_THROTTLED_MS);
                }
                return unavailable();
            }

            cache.put(ck, new CachedTile(body, ct));
            return ResponseEntity.ok()
                    .contentType(safeMediaType(ct))
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    // 瓦片内容不可变（layer/z/x/y 唯一），长缓存减少手机 WebView 与代理重复回源
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                    .body(body);
        } catch (Exception e) {
            // 上游超时/网络异常：503 而非 404——让前端底图降级链收到信号，
            // 同时短熔断避免每张瓦片都各等满一次 timeout。
            breakerUntil.set(System.currentTimeMillis() + BREAKER_TRANSIENT_MS);
            return unavailable();
        }
    }

    /** 上游 Content-Type 畸形时不能让 parseMediaType 抛成 500，回落到 png 即可。 */
    private MediaType safeMediaType(String ct) {
        try {
            return MediaType.parseMediaType(ct);
        } catch (Exception e) {
            return MediaType.IMAGE_PNG;
        }
    }

    /** 确实没有这张瓦片：MapLibre 会干净跳过，但错误响应不能被 WebView 缓存住。 */
    private ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /** 本应有数据但当下取不到：MapLibre 会派发 error 事件，前端据此重挂底图或降级。 */
    private ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
