package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.example.aseanweatherlogistics.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 行业知识库服务（计划书 3.4）。
 * 轻量 RAG：关键词加权打分检索，标题 > 关键词 > 正文，供大模型检索增强与离线降级使用。
 */
@Service
public class KnowledgeBaseService {

    private final List<KnowledgeEntry> entries;
    private final List<String> categories = new ArrayList<>();

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.entries = repository.loadAll();
        for (KnowledgeEntry e : entries) {
            if (!categories.contains(e.getCategory())) {
                categories.add(e.getCategory());
            }
        }
    }

    public List<KnowledgeEntry> all() {
        return entries;
    }

    public List<String> categories() {
        return categories;
    }

    /** 关键词检索，返回按相关度降序的前 N 条。 */
    public List<KnowledgeEntry> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(entries.subList(0, Math.min(limit, entries.size())));
        }
        String q = query.toLowerCase();
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeEntry e : entries) {
            int score = 0;
            String title = nullSafe(e.getTitle());
            String content = nullSafe(e.getContent());
            String keywords = String.join(" ", nullSafeList(e.getKeywords()));
            if (title.toLowerCase().contains(q)) {
                score += 3;
            }
            if (keywords.toLowerCase().contains(q)) {
                score += 2;
            }
            if (content.toLowerCase().contains(q)) {
                score += 1;
            }
            if (score > 0) {
                scored.add(new Scored(e, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(limit).map(Scored::entry).toList();
    }

    public List<KnowledgeEntry> search(String query) {
        return search(query, 3);
    }

    /** 按分类检索（用于风险→知识映射）。 */
    public List<KnowledgeEntry> byCategory(String category) {
        return entries.stream().filter(e -> category.equalsIgnoreCase(e.getCategory())).toList();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String nullSafeList(List<String> list) {
        if (list == null) {
            return "";
        }
        return String.join(" ", list);
    }

    private record Scored(KnowledgeEntry entry, int score) {
    }
}
