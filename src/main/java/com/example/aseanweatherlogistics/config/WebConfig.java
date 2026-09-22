package com.example.aseanweatherlogistics.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // 合规要求：赛事 Token 只允许后端持有，且禁止任何第三方站点借用本服务转发上游请求
    // （服务本身同源托管前端，跨域仅在本机开发 / 局域网手机访问 / 打包 WebView 场景下需要）。
    // 若演示时需要放开更多来源，在此追加即可。
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
                        "capacitor://localhost")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    // 本地 OSM 底图：/tiles/** 同时托管两套瓦片目录，Spring 会按顺序逐位置解析请求文件：
    // - D 盘离线 OSM 栅格（*.jpg，EPSG:4326）；
    // - 项目内矢量瓦片 tools/data/tiles（*.pbf，OpenMapTiles schema，供 MapLibre GPU 渲染）。
    // 扩展名不同互不冲突：jpg 请求命中 D 盘、pbf 请求命中项目内目录；
    // 以前是「D 盘存在就只注册 D 盘」二选一，导致 pbf 永远 404。
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

        String configured = System.getProperty("osm.tiles.path");
        Path tilesPath = configured != null && !configured.isBlank()
                ? Paths.get(configured)
                : Paths.get("D:\\", "OpenStreeMap", "EOX Maps - OpenStreetMap background layer by EOX - 4326");
        Path projectTiles = Paths.get(System.getProperty("user.dir"), "tools", "data", "tiles");

        // 瓦片内容不可变（z/x/y 唯一），长缓存减少手机 WebView 重复拉取
        var registration = registry.addResourceHandler("/tiles/**")
                .setCachePeriod(30 * 24 * 3600);
        if (Files.exists(tilesPath)) {
            registration.addResourceLocations(tilesPath.toUri().toString());
        }
        registration.addResourceLocations(projectTiles.toUri().toString());
    }
}
