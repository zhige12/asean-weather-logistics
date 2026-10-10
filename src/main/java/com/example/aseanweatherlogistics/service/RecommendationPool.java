package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 推荐语缓存池：AI 文案降级的最后一环。
 * <p>
 * 调度大屏「AI 决策分析」的推荐语走三级链路：<b>本地 Ollama → 在线 DeepSeek → 本缓存池</b>。
 * 每次本地或在线成功生成一句推荐语，都会按「情境签名」存进这里并落盘；
 * 一旦断网（两条模型通道都不通），就从对应情境的历史文案里<b>轮换取用</b>——
 * 每次给的和上一次不一样，而不是回落到一句写死的模板。这样"接了 AI 却每句话都一模一样"
 * 的硬编码观感被消除：在线时由模型现写、句句不同；离线时在历史生成物里换着说。
 * <p>
 * 情境签名（见 {@code SolutionAgent}）把模式 / 推荐方案 / 熔断成因编进 key，
 * 保证离线兜底取到的文案与当前局势一致，不会把"绕行"的话术配到"常态"场景上。
 * 每个 key 只保留最近若干条，去重后循环轮换。
 */
@Service
public class RecommendationPool {

    private static final Logger log = LoggerFactory.getLogger(RecommendationPool.class);

    /** 每个情境最多留几条文案：够轮换、又不至于无限膨胀。 */
    private static final int MAX_PER_KEY = 8;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${decision.cache.dir:decision-cache}")
    private String cacheDir;

    // key -> 该情境下的历史文案列表（按生成先后排列，超过上限丢最旧）
    private final Map<String, List<String>> pool = new LinkedHashMap<>();
    // key -> 上次取用到的下标，pick 时从它的下一条开始，保证连续两次不重样
    private final Map<String, Integer> cursor = new LinkedHashMap<>();
    private boolean loaded = false;

    private Path file() {
        return Path.of(cacheDir, "recommendation-pool.json");
    }

    /** 记录一句模型生成的推荐语；与已有文案完全相同则不重复存。 */
    public synchronized void record(String key, String text) {
        ensureLoaded();
        if (key == null || text == null || text.isBlank()) {
            return;
        }
        String t = text.trim();
        List<String> list = pool.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.contains(t)) {
            return;
        }
        list.add(t);
        while (list.size() > MAX_PER_KEY) {
            list.remove(0);
        }
        persist();
    }

    /**
     * 断网兜底：按情境轮换取一条历史文案（与上次给的不同）；池为空返回 null（调用方再落模板）。
     */
    public synchronized String pick(String key) {
        ensureLoaded();
        List<String> list = pool.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int last = cursor.getOrDefault(key, -1);
        int next = (last + 1) % list.size();
        cursor.put(key, next);
        return list.get(next);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (Files.exists(f)) {
                Map<String, List<String>> m = mapper.readValue(Files.readString(f),
                        mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, List.class));
                pool.putAll(m);
                log.info("推荐语缓存池已加载：{} 个情境", pool.size());
            }
        } catch (Exception e) {
            log.warn("读取推荐语缓存池失败：{}", e.toString());
        }
    }

    private void persist() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pool));
        } catch (Exception e) {
            log.warn("写入推荐语缓存池失败：{}", e.toString());
        }
    }
}
