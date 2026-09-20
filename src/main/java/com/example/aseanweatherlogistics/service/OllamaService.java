package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 本地大模型通道（演示第七幕 · 拔网线）：
 * Ollama 本地推理（OpenAI 兼容接口），默认 http://localhost:11434，模型 qwen2.5:7b。
 * <p>
 * 在 AI 调用链中优先级最高：Ollama → 公司网关 → 在线 DeepSeek → 规则模板。
 * 断网/未启动 Ollama 时短超时（4s）快速失败并降级，不阻塞演示。
 * 熔断：连续失败 2 次冷却 2 分钟，避免每次请求都白等超时。
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    @Value("${ollama.enabled:false}")
    private boolean enabled;

    @Value("${ollama.url:http://localhost:11434/v1/chat/completions}")
    private String url;

    @Value("${ollama.model:qwen2.5:7b}")
    private String model;

    // 读超时 60s：14b 模型首次生成需加载权重（10-30s），不能按"接不上"误判；
    // 连接超时 2s 不变——Ollama 没启动时才是真正要快速失败的场景
    private static final int TIMEOUT_SECONDS = 60;
    private static final int FAIL_THRESHOLD = 2;
    private static final long COOLDOWN_MILLIS = 2 * 60_000L;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile long cooldownUntil = 0L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    /** 本地推理；不可用/超时返回 null（调用方负责降级）。 */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        if (!enabled || System.currentTimeMillis() < cooldownUntil) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        payload.put("messages", messages);
        payload.put("stream", false);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", 0.4);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return fail("HTTP " + resp.statusCode());
            }
            JsonNode content = mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? "" : content.asText().trim();
            if (text.isEmpty()) {
                return fail("空响应");
            }
            failures.set(0);
            return text;
        } catch (Exception e) {
            return fail(e.toString());
        }
    }

    private String fail(String why) {
        int n = failures.incrementAndGet();
        if (n >= FAIL_THRESHOLD) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MILLIS;
            failures.set(0);
            log.warn("本地 Ollama 连续 {} 次失败（{}），冷却 {} 分钟，期间走远程/模板通道",
                    FAIL_THRESHOLD, why, COOLDOWN_MILLIS / 60_000L);
        } else {
            log.info("本地 Ollama 不可用（{}），本次降级", why);
        }
        return null;
    }
}
