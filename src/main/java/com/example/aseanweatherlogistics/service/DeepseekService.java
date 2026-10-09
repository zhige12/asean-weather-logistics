package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI Agent 适配器（OpenAI 兼容格式），两级通道自动降级：
 * <ol>
 *   <li>本地通道：Ollama（离线演示主力，拔网线可用，数据不出境，优先级最高）。</li>
 *   <li>在线通道：DeepSeek 官方 API（deepseek.fallback.*，公网直连）。</li>
 * </ol>
 * payload 使用 messages 数组，响应从 choices[0].message.content 解析。
 * 所有对外方法均不抛 checked 异常：网络失败 / key 无效时返回 null 或空列表，由调用方降级。
 * <p>
 * <b>通道溯源（provenance）</b>：两条通道返回的内容在业务上等价，但在<b>展示上不等价</b>——
 * 「本地大模型」意味着数据不出境，「在线 API」意味着提示词里的路线、货值、口岸状态
 * 已经离开本机。旧版只向调用方回传一个裸 String，上层无从分辨，界面把任何一次成功
 * 调用都标成「本地大模型」；Ollama 一抖（超时/显存被占/冷却 2 分钟）就会静默切到在线
 * 通道，标注当场失真。故新增 {@link #chatWithChannel}：把真实通道一并交出去，
 * 由上层如实标注。{@link #chat} 保留原签名供不需要溯源的调用方使用。
 */
@Service
public class DeepseekService {

    private static final Logger log = LoggerFactory.getLogger(DeepseekService.class);

    // ---- 在线通道：DeepSeek 官方 API ----
    @Value("${deepseek.fallback.url:}")
    private String fallbackUrl;

    @Value("${deepseek.fallback.key:}")
    private String fallbackKey;

    @Value("${deepseek.fallback.model:}")
    private String fallbackModel;

    // ---- 本地通道：Ollama（离线演示主力，拔网线可用；优先级最高）----
    private final OllamaService ollamaService;

    public DeepseekService(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    /** 在线通道超时：公网链路较长，给足时间但也不能无限等 */
    private static final int ONLINE_TIMEOUT_SECONDS = 60;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** key 是否真正可用（未配置或仍是占位符视为未启用） */
    private boolean keyConfigured(String key) {
        return key != null && !key.isBlank() && !key.contains("<");
    }

    /**
     * 一次 AI 回复 + 它的真实来源。channel 取值见 {@link #CHANNEL_LOCAL} 等常量，
     * 两条通道全部失败时为 null（调用方须按「非模型生成」处理）。
     */
    public record AiReply(String text, String channel, String model) {
        /** 给上层落库/透传用的紧凑标记：channel[:model]，无通道时为 null。 */
        public String tag() {
            if (channel == null) {
                return null;
            }
            return (model == null || model.isBlank()) ? channel : channel + ":" + model;
        }
    }

    /** 本地 Ollama 推理，数据不出境。 */
    public static final String CHANNEL_LOCAL = "local";
    /** 公网 DeepSeek，提示词离开本机。 */
    public static final String CHANNEL_ONLINE = "online";

    /**
     * OpenAI 兼容 chat completions 调用（本地优先，在线降级），并回报<b>真实命中通道</b>。
     *
     * @return 回复文本与来源通道；两条通道都失败 / 无 key 时 text 与 channel 均为 null
     */
    public AiReply chatWithChannel(String systemPrompt, String userPrompt, int maxTokens) {
        // 本地 Ollama 优先：拔网线离线演示时所有 AI 功能仍可用，数据不出境
        if (ollamaService != null && ollamaService.enabled()) {
            String local = ollamaService.chat(systemPrompt, userPrompt, maxTokens);
            if (local != null) {
                return new AiReply(local, CHANNEL_LOCAL, ollamaService.model());
            }
        }
        if (!keyConfigured(fallbackKey)) {
            log.info("未配置在线 DeepSeek 令牌（deepseek.fallback.key 为空或占位符），降级到规则模板");
            return new AiReply(null, null, null);
        }
        String online = chatRemote(fallbackUrl, fallbackModel, fallbackKey,
                systemPrompt, userPrompt, maxTokens, ONLINE_TIMEOUT_SECONDS);
        // 只有真拿到内容才算命中在线通道，否则通道为 null（降级到规则模板）
        return new AiReply(online, online != null ? CHANNEL_ONLINE : null, fallbackModel);
    }

    /**
     * OpenAI 兼容 chat completions 调用（本地优先，在线降级）。
     *
     * @return 模型回复文本；两条通道都失败 / 无 key 时返回 null（调用方负责降级）
     */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        return chatWithChannel(systemPrompt, userPrompt, maxTokens).text();
    }

    /** 向 OpenAI 兼容端点发一次 chat 请求（携带 Bearer 鉴权） */
    private String chatRemote(String url, String mdl, String key,
                              String systemPrompt, String userPrompt, int maxTokens, int timeoutSeconds) {
        if (url == null || url.isBlank() || mdl == null || mdl.isBlank()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", mdl);
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        payload.put("messages", messages);
        payload.put("stream", false);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", 0.7);

        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("AI 端点 {} 返回 HTTP {}，按失败处理", url, resp.statusCode());
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                return null;
            }
            String text = content.asText().trim();
            return text.isEmpty() ? null : text;
        } catch (IOException | InterruptedException e) {
            log.warn("AI 端点 {} 请求异常：{}", url, e.toString());
            return null;
        }
    }

    /**
     * Query Deepseek for forecasts at the provided points (lat, lon).
     * Returns a list of maps with keys: lat, lon, precipitation_mm, wind_kph
     */
    public List<Map<String, Object>> queryForecastForPoints(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("Return JSON only. Provide an object with key 'forecasts' which is an array. ");
        prompt.append("Each forecast item should include numeric fields: lat, lon, precipitation_mm, wind_kph.\n");
        prompt.append("Return example: {\"forecasts\":[{\"lat\":1.2,\"lon\":103.4,\"precipitation_mm\":10,\"wind_kph\":25}]}\n");
        prompt.append("Points:\n");
        for (double[] p : points) {
            prompt.append(String.format("- %.6f, %.6f%n", p[0], p[1]));
        }

        String text = chat("You are a weather data assistant. Always respond with valid JSON only.",
                prompt.toString(), 800);
        if (text == null) {
            return List.of();
        }
        String json = extractJson(text);
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode forecasts = root.get("forecasts");
            List<Map<String, Object>> out = new ArrayList<>();
            if (forecasts != null && forecasts.isArray()) {
                for (JsonNode n : forecasts) {
                    double lat = n.path("lat").asDouble();
                    double lon = n.path("lon").asDouble();
                    double precipitation = n.path("precipitation_mm").asDouble(0.0);
                    double wind = n.path("wind_kph").asDouble(0.0);
                    Map<String, Object> item = new HashMap<>();
                    item.put("lat", lat);
                    item.put("lon", lon);
                    item.put("precipitation_mm", precipitation);
                    item.put("wind_kph", wind);
                    out.add(item);
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String extractJson(String s) {
        if (s == null) {
            return null;
        }
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        if (objStart >= 0) {
            int objEnd = s.lastIndexOf('}');
            if (objEnd > objStart) {
                return s.substring(objStart, objEnd + 1);
            }
        }
        if (arrStart >= 0) {
            int arrEnd = s.lastIndexOf(']');
            if (arrEnd > arrStart) {
                return s.substring(arrStart, arrEnd + 1);
            }
        }
        return null;
    }

    /**
     * Map precipitation and wind to a penalty multiplier.
     */
    public double mapToPenalty(double precipitationMm, double windKph) {
        double penalty = 0.0;
        if (precipitationMm >= 50) {
            penalty += 200.0;
        } else if (precipitationMm >= 20) {
            penalty += 50.0;
        } else if (precipitationMm >= 5) {
            penalty += 10.0;
        }
        if (windKph >= 80) {
            penalty += 50.0;
        } else if (windKph >= 40) {
            penalty += 10.0;
        }
        return Math.max(penalty, 0.0);
    }
}
