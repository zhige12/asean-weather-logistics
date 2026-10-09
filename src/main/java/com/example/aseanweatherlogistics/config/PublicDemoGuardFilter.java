package com.example.aseanweatherlogistics.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 公网演示护栏：交付期把本机服务通过 Tailscale Funnel 暴露给评委时启用。
 *
 * <p>解决两类只有「公网可达」才会出现的问题：
 * <ol>
 *   <li><b>一击打挂整机</b>：{@code POST /api/routes/osm/start} 会让进程去加载 311MB
 *       OSM PBF（且 {@code osm.pbf.path} 还是 Windows 绝对路径），陌生人点一下即 OOM/异常，
 *       评委看到的就是一条死链接——比根本没有链接更伤。这类接口在演示形态下不该存在，直接 403。</li>
 *   <li><b>并发把演示状态搅乱</b>：复位/熔断/场景注入类接口是「改全局状态」的，
 *       两个评委同时点会互相打回，后打开的那个会看到一个说不通的死局。按客户端 IP 节流。</li>
 * </ol>
 *
 * <p>刻意宽松的三处，都是被现场演示坑过之后定的量：
 * <ul>
 *   <li><b>只拦 /api/，且只拦非 GET</b>：前端一次浏览要发几千个请求（算路轮询、SSE 注册、
 *     地名查询），GET 全放行——节流 GET 等于节流演示本身。</li>
 *   <li><b>普通写接口配额给到很大</b>：{@code /api/agent/register} 每次算路都会发一次，
 *     大屏 15 秒轮询也在发；按「写操作=可疑」一刀切会让评委莫名卡顿。</li>
 *   <li><b>瓦片与静态资源完全不进本过滤器</b>：一次铺图几百张瓦片，
 *     在这里做字符串判断是纯浪费。</li>
 * </ul>
 *
 * <p>客户端 IP 取 {@code X-Forwarded-For} 首值：经隧道/反代后 {@code getRemoteAddr()}
 * 只会看到中继地址，用它限流等于让所有评委共用同一份（或直接失效）。
 */
@Component
public class PublicDemoGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicDemoGuardFilter.class);

    /** 只保护 API；静态资源与瓦片不进过滤器（见类注释）。 */
    private static final String API_PREFIX = "/api/";

    /**
     * 能打死进程的接口。控制器同时映射了 {@code /api/routes} 与 {@code /api/route}
     * 两个前缀（见 RouteController），两个写法都要拦，漏一个等于没拦。
     */
    private static final Set<String> DENY_PATHS = Set.of(
            "/api/routes/osm/start", "/api/routes/osm/stop",
            "/api/route/osm/start", "/api/route/osm/stop");

    /**
     * 改全局状态的写接口：复位、熔断/解除、场景注入、AI 扫描（会真调大模型并注入风险）。
     * 用「包含」而非精确匹配，因为带路径变量与双前缀的形态很多（/scenario/{id}、
     * /risk/{edgeId}、/water/block …），逐个枚举必然漏。
     */
    private static final List<String> STRICT_MARKERS = List.of(
            "/reset", "/block", "/unblock", "/scenario", "/deepseek/scan", "/risk", "/source/");

    /** 固定窗口计数：key = ip|档位|分钟序号，跨分钟自然失效，无需定时清理任务。 */
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private static final int COUNTERS_PRUNE_THRESHOLD = 4096;

    @Value("${demo.guard.enabled:true}")
    private boolean enabled;

    @Value("${demo.guard.deny-osm-loader:true}")
    private boolean denyOsmLoader;

    @Value("${demo.guard.strict-limit-per-minute:20}")
    private int strictLimit;

    @Value("${demo.guard.normal-limit-per-minute:180}")
    private int normalLimit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(API_PREFIX)) {
            return true;
        }
        String m = request.getMethod();
        // GET/HEAD/OPTIONS 放行：SSE 长连接是 GET，节流它会掐断实时推送演示
        return m == null || "GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m)
                || "OPTIONS".equalsIgnoreCase(m);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (denyOsmLoader && DENY_PATHS.contains(path)) {
            // 只在被真实触发时打一行：这类接口被扫描器命中的频率不低，全打会淹掉关键日志
            log.warn("[demo-guard] 拒绝危险接口 {} from {}", path, clientIp(request));
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, Map.of(
                    "ok", false,
                    "code", "endpoint_disabled",
                    "message", "该接口在演示环境已关闭（加载全量 OSM 路网会耗尽内存，导致服务不可用）"));
            return;
        }

        boolean strict = STRICT_MARKERS.stream().anyMatch(path::contains);
        int limit = strict ? strictLimit : normalLimit;
        int used = countUp(clientIp(request), strict ? "strict" : "normal");
        if (used > limit) {
            log.info("[demo-guard] {} 操作超频：{} 次/分钟 > {}，拒绝 {}",
                    strict ? "破坏性" : "写", used, limit, path);
            writeJson(response, 429, Map.of(
                    "ok", false,
                    "code", "rate_limited",
                    "message", "操作过于频繁，请等待 1 分钟后再试",
                    "retryAfterSeconds", 60));
            return;
        }

        chain.doFilter(request, response);
    }

    /** 本分钟计数。跨分钟后 key 自然不再命中，超过阈值时清理旧分钟残留。 */
    private int countUp(String ip, String tier) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (counters.size() > COUNTERS_PRUNE_THRESHOLD) {
            String keepSuffix = "|" + minute;
            String prevSuffix = "|" + (minute - 1);
            counters.keySet().removeIf(k -> !k.endsWith(keepSuffix) && !k.endsWith(prevSuffix));
        }
        return counters.computeIfAbsent(ip + "|" + tier + "|" + minute,
                k -> new AtomicInteger()).incrementAndGet();
    }

    /** 经隧道/反代时的真实访问者 IP。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String addr = request.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }

    /**
     * 不抛给全局异常处理器、不引 Jackson：本过滤器要在任何情况下都能干净回绝，
     * 包括序列化配置出问题的时刻——护栏自己先崩就没意义了。
     */
    private void writeJson(HttpServletResponse response, int status, Map<String, ?> body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        StringBuilder sb = new StringBuilder("{");
        body.forEach((k, v) -> sb.append('"').append(k).append("\":")
                .append(v instanceof Number ? v : '"' + String.valueOf(v).replace("\"", "\\\"") + '"')
                .append(','));
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        response.getWriter().write(sb.append('}').toString());
    }
}
