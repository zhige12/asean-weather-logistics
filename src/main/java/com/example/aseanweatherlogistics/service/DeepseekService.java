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
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI Agent 适配器（OpenAI 兼容格式），主备双通道自动切换：
 * <ol>
 *   <li>主通道：公司内网 new-api 网关（deepseek.api.*，需连公司 VPN）。短超时（8s），
 *       一旦接不上 / 超时 / 401 / 未填令牌 → 立即切备用通道。</li>
 *   <li>备用通道：在线 DeepSeek 官方 API（deepseek.fallback.*，公网直连，无需 VPN）。</li>
 * </ol>
 * 熔断：主通道连续失败 2 次进入 5 分钟冷却期，期间直接走备用通道（不干等）；
 * 冷却结束后自动恢复试探主通道，主通道恢复后不再走备用。
 * payload 使用 messages 数组，响应从 choices[0].message.content 解析。
 * 所有对外方法均不抛 checked 异常：网络失败 / key 无效时返回 null 或空列表，由调用方降级。
 */
@Service
public class DeepseekService {

    private static final Logger log = LoggerFactory.getLogger(DeepseekService.class);

    // ---- 主通道：公司网关 ----
    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.model}")
    private String model;

    // ---- 备用通道：在线 DeepSeek ----
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

    // 主通道短超时：接不上要"马上"切备用，不能干等
    private static final int PRIMARY_TIMEOUT_SECONDS = 8;
    private static final int FALLBACK_TIMEOUT_SECONDS = 60;
    // 熔断：连续失败 2 次 → 冷却 5 分钟
    private static final int PRIMARY_FAIL_THRESHOLD = 2;
    private static final long PRIMARY_COOLDOWN_MILLIS = 5 * 60_000L;

    private final AtomicInteger primaryFailures = new AtomicInteger();
    private volatile long primaryCooldownUntil = 0L;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** key 是否真正可用（未配置或仍是占位符视为未启用） */
    private boolean keyConfigured(String key) {
        return key != null && !key.isBlank() && !key.contains("<");
    }

    /**
     * OpenAI 兼容 chat completions 调用（主备自动切换）。
     *
     * @return 模型回复文本；两条通道都失败 / 无 key 时返回 null（调用方负责降级）
     */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        // 本地 Ollama 优先：拔网线离线演示时所有 AI 功能仍可用，数据不出境
        if (ollamaService != null && ollamaService.enabled()) {
            String local = ollamaService.chat(systemPrompt, userPrompt, maxTokens);
            if (local != null) {
                return local;
            }
        }
        boolean primaryReady = keyConfigured(apiKey) && System.currentTimeMillis() >= primaryCooldownUntil;
        if (primaryReady) {
            String text = chatRemote(apiUrl, model, apiKey, systemPrompt, userPrompt, maxTokens, PRIMARY_TIMEOUT_SECONDS);
            if (text != null) {
                primaryFailures.set(0);
                return text;
            }
            int fails = primaryFailures.incrementAndGet();
            if (fails >= PRIMARY_FAIL_THRESHOLD) {
                primaryCooldownUntil = System.currentTimeMillis() + PRIMARY_COOLDOWN_MILLIS;
                primaryFailures.set(0);
                log.warn("公司 AI 网关连续 {} 次失败，进入 {} 分钟冷却，期间直连在线 DeepSeek",
                        PRIMARY_FAIL_THRESHOLD, PRIMARY_COOLDOWN_MILLIS / 60_000L);
            }
            log.warn("公司 AI 网关({})调用失败，切换到在线 DeepSeek 备用通道", apiUrl);
        } else if (!keyConfigured(apiKey)) {
            log.info("未配置公司网关令牌（deepseek.api.key 为空或占位符），直接使用在线 DeepSeek");
        } else {
            log.info("公司 AI 网关处于冷却期，直接使用在线 DeepSeek");
        }
        return chatRemote(fallbackUrl, fallbackModel, fallbackKey,
                systemPrompt, userPrompt, maxTokens, FALLBACK_TIMEOUT_SECONDS);
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
