package com.example.aseanweatherlogistics.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
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
 * 浏览器要求同源或带 CORS 头——天地图在线瓦片既无 CORS 头、又有防盗链 Referer 校验
 * （无/错误 Referer 返回 418），前端直连必然加载失败。
 *
 * <p>本代理让前端只请求<b>同源</b>的 {@code /api/tianditu/...}：后端持有密钥、带上
 * 合规 Referer 去取上游瓦片，再以 {@code Access-Control-Allow-Origin} + 长缓存回吐。
 * 打包进 Capacitor 的 WebView 由后端同源托管（capacitor server.url 指向本服务），
 * 开发态由 Vite 的 {@code /api} 代理转发，两种场景都命中这里，无需额外跨域配置。
 *
 * <p>降级约定：密钥缺失、layer 非法、坐标越界、上游非 200/空——一律返回 <b>404 空响应</b>。
 * MapLibre 把 404 当作「该瓦片无数据」干净跳过，前端底图降级链据此从天地图 → OSM → 本地矢量。
 * 绝不能返回 500+JSON，否则 MapLibre 会报 "Unable to parse the tile" 刷屏。
 */
@RestController
public class TiandituTileProxyController {

    /** 仅放行这两个图层：img_w=影像底图，cia_w=影像注记（地名/边界，透明 PNG）。 */
    private static final Set<String> ALLOWED_LAYERS = Set.of("img_w", "cia_w");
    private static final int MAX_ZOOM = 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 后端兜底密钥（可选）：优先用前端传来的 tk，缺省时回落到这里。 */
    @Value("${tianditu.tk:}")
    private String configuredTk;

    /** 兜底 Referer：转发失败或前端 Worker 请求不带 Referer 时使用，需与密钥授权域名一致。 */
    @Value("${tianditu.referer:}")
    private String configuredReferer;

    @Value("${tianditu.timeout-seconds:6}")
    private long timeoutSeconds;

    @GetMapping("/api/tianditu/{layer}/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> tile(@PathVariable String layer,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(required = false) String tk,
                                       @RequestHeader(value = "Referer", required = false) String referer) {
        // 1) 参数与白名单校验：非法一律 404（MapLibre 视为无数据，触发前端降级）
        if (!ALLOWED_LAYERS.contains(layer) || z < 0 || z > MAX_ZOOM) {
            return notFound();
        }
        int max = 1 << z; // 2^z，该缩放级别下的瓦片行列上限
        if (x < 0 || y < 0 || x >= max || y >= max) {
            return notFound();
        }
        String key = (tk != null && !tk.isBlank()) ? tk.trim() : (configuredTk == null ? "" : configuredTk.trim());
        if (key.isEmpty()) {
            // 未配置密钥：直接 404，让前端降级到下一底图源，而不是发无谓的上游请求
            return notFound();
        }

        // 2) 组装上游 URL：沿用前端已验证可用的 DataServer XYZ 风格端点（_w 球面墨卡托）。
        //    t0~t7 轮询分摊压力；子域按 (x+y) 取模稳定映射，保证同一瓦片命中同一节点便于上游缓存。
        int sub = Math.floorMod(x + y, 8);
        // 参数顺序与命名刻意与 tileSources.js 中已验证可用的在线源保持一致（T/x/y/l/tk）
        String url = "https://t" + sub + ".tianditu.gov.cn/DataServer?T=" + layer
                + "&x=" + x + "&y=" + y + "&l=" + z + "&tk=" + key;

        // 3) Referer 透传：优先用发起页的真实 Referer（密钥的域名授权以此为准），
        //    前端 Web Worker 拉瓦片可能不带 Referer，则回落到配置值 / localhost。
        String ref = (referer != null && !referer.isBlank()) ? referer
                : (configuredReferer.isBlank() ? "http://localhost/" : configuredReferer);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Referer", ref)
                    .header("User-Agent", "Mozilla/5.0 (asean-weather-logistics tile proxy)")
                    .header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = resp.body();
            if (resp.statusCode() != 200 || body == null || body.length == 0) {
                return notFound();
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("image/png");

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(ct))
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    // 瓦片内容不可变（layer/z/x/y 唯一），长缓存减少手机 WebView 与代理重复回源
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=2592000, immutable")
                    .body(body);
        } catch (Exception e) {
            // 上游超时/异常：404 而非 500，避免 MapLibre 解析错误刷屏并阻断降级
            return notFound();
        }
    }

    private ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
