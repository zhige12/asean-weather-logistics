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
 * 在 AI 调用链中优先级最高：Ollama → 在线 DeepSeek → 缓存池。
 * 连接超时压到 800ms：本地没启动时要在 1 秒内判定「接不上」并切在线（见字段注释）；
 * 连续失败后进入冷却，冷却期内直接返回 null，切换零等待。
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

    // 读超时 60s：14b 模型首次生成需加载权重（10-30s），不能按"接不上"误判。
    // 连接超时 800ms（原 2s）：本地↔在线的降级切换要求 ≤1s，连接阶段必须比 1s 更早判死，
    // 给 DNS/握手抖动留出余量仍在 1s 内落到在线通道；进程未启动时连接被拒本就瞬时失败。
    private static final int TIMEOUT_SECONDS = 60;
    private static final int CONNECT_TIMEOUT_MILLIS = 800;
    private static final int FAIL_THRESHOLD = 2;
    private static final long COOLDOWN_MILLIS = 2 * 60_000L;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile long cooldownUntil = 0L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    /**
     * 一次模型调用的产出：文本 + 真实 token 用量。
     * token 取自 OpenAI 兼容回包的 {@code usage}（部分端点不回该字段，此时为 0，
     * 计量侧会照实记 0 而不是拿字符数估算——估算值混进成本表会被当成实测数据）。
     */
    public record LlmReply(String text, long promptTokens, long completionTokens) {
        static final LlmReply NONE = new LlmReply(null, 0, 0);

        boolean ok() {
            return text != null;
        }
    }

    /** 本地推理；不可用/超时返回 null（调用方负责降级）。保持旧签名，不需要用量的调用方不受影响。 */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        return chatDetailed(systemPrompt, userPrompt, maxTokens).text();
    }

    /** 本地推理 + token 用量（供 {@code /api/ai/metrics} 计量成本）。 */
    public LlmReply chatDetailed(String systemPrompt, String userPrompt, int maxTokens) {
        if (!enabled || System.currentTimeMillis() < cooldownUntil) {
            return LlmReply.NONE;
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
                fail("HTTP " + resp.statusCode());
                return LlmReply.NONE;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? "" : content.asText().trim();
            if (text.isEmpty()) {
                fail("空响应");
                return LlmReply.NONE;
            }
            failures.set(0);
            return new LlmReply(text, usageLong(root, "prompt_tokens"), usageLong(root, "completion_tokens"));
        } catch (Exception e) {
            fail(e.toString());
            return LlmReply.NONE;
        }
    }

    /** 读 {@code usage} 里的一个计数；端点没回就 0，不猜。 */
    private static long usageLong(JsonNode root, String field) {
        JsonNode v = root.path("usage").path(field);
        return v.isNumber() ? v.asLong() : 0L;
    }

    private void fail(String why) {
        int n = failures.incrementAndGet();
        if (n >= FAIL_THRESHOLD) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MILLIS;
            failures.set(0);
            log.warn("本地 Ollama 连续 {} 次失败（{}），冷却 {} 分钟，期间走远程/模板通道",
                    FAIL_THRESHOLD, why, COOLDOWN_MILLIS / 60_000L);
        } else {
            log.info("本地 Ollama 不可用（{}），本次降级", why);
        }
    }
}
