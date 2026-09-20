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

    // 本地 OSM 底图：优先使用 D 盘的离线 OSM 文件夹（栅格瓦片），若目录不存在则回退到项目内默认 tiles。
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String configured = System.getProperty("osm.tiles.path");
        Path tilesPath = configured != null && !configured.isBlank()
                ? Paths.get(configured)
                : Paths.get("D:\\", "OpenStreeMap", "EOX Maps - OpenStreetMap background layer by EOX - 4326");

        if (!Files.exists(tilesPath)) {
            tilesPath = Paths.get(System.getProperty("user.dir"), "tools", "data", "tiles");
        }

        registry.addResourceHandler("/tiles/**").addResourceLocations(tilesPath.toUri().toString());
    }
}
