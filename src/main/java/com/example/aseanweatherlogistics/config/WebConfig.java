package com.example.aseanweatherlogistics.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Configuration;
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
