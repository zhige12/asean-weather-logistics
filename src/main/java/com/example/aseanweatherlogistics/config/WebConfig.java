package com.example.aseanweatherlogistics.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 本地离线栅格瓦片目录（EPSG:4326 的 *.jpg）。
     * 写成 Spring 属性而不是 System.getProperty：这样 .env.local、命令行
     * --osm.tiles.path=... 与 -Dosm.tiles.path=... 三种方式都能生效（Spring 环境已包含系统属性）。
     * 留空时按候选目录探测，取第一个存在的；都不存在则该层不参与解析，
     * 前端底图降级链自然跳到下一个源，不影响其余功能。
     */
    @Value("${osm.tiles.path:}")
    private String tilesPathConfig;

    // 合规要求：赛事 Token 只允许后端持有，且禁止任何第三方站点借用本服务转发上游请求
    // （服务本身同源托管前端，跨域仅在本机开发 / 局域网手机访问 / 打包 WebView 场景下需要）。
    // 若演示时需要放开更多来源，在此追加即可。
    //
    // 公网演示来源必须显式列入：前端与 API 同源部署在 https://8.138.229.14:8443，但浏览器对
    // 同源 POST 也会带 Origin 头，而经 nginx 反代后后端把 scheme/端口看丢了（Host 用 $host 不含端口、
    // 又没转发 X-Forwarded-Proto），Spring 便把这请求当成跨域走 CORS 校验；此源不在白名单时
    // 直接 403「Invalid CORS request」——表现为评委用 IP 链接打开时所有写操作（拉取GOWFS/拖滑块/
    // 接单算路/支流…）全挂，只有只读 GET 能用。放行本部署自身的公网 IP 与域名即可根治。
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://192.168.*.*:*",
                        "http://172.*.*.*:*",
                        "http://10.*.*.*:*",
                        "https://localhost",
                        "capacitor://localhost",
                        // 公网演示：裸 IP（含/不含端口、http/https 都放行，兼容 :8080 直连与 :8443 反代）
                        "https://8.138.229.14:*",
                        "http://8.138.229.14:*",
                        // 域名：端口必须写通配符。nginx 只对外开了 8443，浏览器 Origin 是
                        // https://zhi-ning.cn:8443，若只列 https://zhi-ning.cn（无端口）会匹配失败，
                        // 表现为「用域名打开时拉取 GOWFS / 拖滑块 / 接单算路全部 403 Invalid CORS request，
                        // 换成裸 IP 又正常」——IP 那条带 :* 所以没事。无端口的写法保留，兼容将来备案生效后走 443。
                        "https://zhi-ning.cn",
                        "https://zhi-ning.cn:*",
                        "https://www.zhi-ning.cn",
                        "https://www.zhi-ning.cn:*",
                        "http://zhi-ning.cn:*",
                        "http://www.zhi-ning.cn:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    // 静态资源托管：前端构建产物（frontend/dist）与本地离线瓦片（/tiles/**）。
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 前端构建产物的缓存策略（手机 App 直连本服务加载 driver.html，非安全上下文无法注册 SW，
        // 只能靠 HTTP 缓存头控制新旧）：
        // - 入口 HTML / 清单 / sw.js 无 hash，必须每次回源校验（no-cache 允许 304），
        //   否则 WebView 启发式缓存会让手机一直跑旧 JS；
        // - /assets/** 文件名带内容 hash，内容不可变，长缓存减少手机重复拉取。
        String distUri = Paths.get(System.getProperty("user.dir"), "frontend", "dist").toUri().toString();
        registry.addResourceHandler(
                        "/driver.html", "/index.html", "/sw.js",
                        "/manifest.json", "/manifest.webmanifest")
                .addResourceLocations(distUri)
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(distUri + "assets/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS));

        // /tiles/** 可托管多个瓦片目录，Spring 按顺序逐位置解析请求文件：
        // 离线栅格瓦片（*.jpg，EPSG:4326）与项目内 tools/data/tiles（*.pbf，历史矢量瓦片实验），
        // 扩展名不同互不冲突。离线栅格目录由 osm.tiles.path 指定，未指定时按候选位置探测，
        // 不再写死某台开发机的绝对路径（新机器把瓦片放进 data/osm-tiles 即可生效）。
        Path tilesPath = tilesPathConfig != null && !tilesPathConfig.isBlank()
                ? Paths.get(tilesPathConfig)
                : firstExisting(
                        Paths.get(System.getProperty("user.dir"), "data", "osm-tiles"),
                        // 开发机上的历史目录（目录名就是这么拼的，保留原样以免本机失效）；
                        // 新机不需要它，把瓦片放进 data/osm-tiles 或用 osm.tiles.path 指定即可。
                        Paths.get("D:/", "OpenStreeMap",
                                "EOX Maps - OpenStreetMap background layer by EOX - 4326"));
        Path projectTiles = Paths.get(System.getProperty("user.dir"), "tools", "data", "tiles");

        // 瓦片内容不可变（z/x/y 唯一），长缓存减少手机 WebView 重复拉取
        var registration = registry.addResourceHandler("/tiles/**")
                .setCachePeriod(30 * 24 * 3600);
        if (tilesPath != null) {
            registration.addResourceLocations(tilesPath.toUri().toString());
        }
        registration.addResourceLocations(projectTiles.toUri().toString());
    }

    /** 返回第一个真实存在的目录；都不存在时返回 null（不把不存在的路径注册进去）。 */
    private static Path firstExisting(Path... candidates) {
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }
}
