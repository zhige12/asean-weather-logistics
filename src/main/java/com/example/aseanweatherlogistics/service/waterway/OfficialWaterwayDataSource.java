package com.example.aseanweatherlogistics.service.waterway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「广西水运江河海一体化调度平台」（"智慧运河"）官方数据源——预留适配层。
 * <p>
 * 边界声明（答辩口径）：官方平台当前没有面向普通开发者的自助 API 申请入口，
 * 接口对接经项目招标或合作协议实现。本类把取数与解析骨架就位：
 * 拿到正式接口地址与凭证后，在 application.properties 配
 * {@code waterway.source=official} + {@code waterway.official.api-base/token}
 * 即可启用，核心禁航判定与 canal-block 链路零改动。
 * <p>
 * 感知网构成（数据字段依据）：11 座专用水文站（流速/水位，15 分钟上报）、
 * 127 座航标 + 33 座 AIS 基站 + 雷达 + 视频监控 + 气象监测站组成的全航道感知网
 * （浪高/风力/能见度），经 262 公里光纤专网 + 5G 边缘计算实时回传。
 */
@Component
public class OfficialWaterwayDataSource implements WaterwayDataSource {

    private final String apiBase;
    private final String token;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OfficialWaterwayDataSource(@Value("${waterway.official.api-base:}") String apiBase,
                                      @Value("${waterway.official.token:}") String token,
                                      @Value("${waterway.official.timeout-seconds:15}") int timeoutSeconds) {
        this.apiBase = apiBase == null ? "" : apiBase.trim();
        this.token = token == null ? "" : token.trim();
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "official";
    }

    /** 是否已具备真实回源条件（未配接口地址时只读状态展示，不发起请求）。 */
    public boolean configured() {
        return !apiBase.isEmpty();
    }

    @Override
    public WaterwayObservation fetch() throws Exception {
        if (!configured()) {
            throw new IllegalStateException(
                    "调度平台接口未配置：正式落地时经合作协议获取 api-base/token 并配置 waterway.official.*");
        }
        // TODO(合作协议落地): 以下为按平台通用返回形态写的骨架，
        //   实际 URL 路径、鉴权头名称与 JSON 字段名以官方接口文档为准，逐字段核对映射。
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/waterway/observation/latest"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("调度平台接口返回 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode data = root.has("data") ? root.get("data") : root;
        return new WaterwayObservation(
                optDouble(data, "visibilityM"),      // 能见度（m）：气象监测站/视频监控
                optDouble(data, "currentSpeedMs"),   // 流速（m/s）：专用水文站
                optDouble(data, "waveHeightM"),      // 浪高（m）：航标/雷达
                optDouble(data, "windSpeedMs"),      // 风速（m/s）：气象监测站
                data.path("station").asText("全航道"),
                "official",
                System.currentTimeMillis());
    }

    private static Double optDouble(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asDouble();
    }
}
