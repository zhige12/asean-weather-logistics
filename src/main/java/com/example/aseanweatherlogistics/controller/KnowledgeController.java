package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.example.aseanweatherlogistics.service.KnowledgeBaseService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业知识库接口（计划书 3.4）。
 * 提供知识条目列表与关键词检索，供大模型检索增强与前端查询。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService service;

    public KnowledgeController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeEntry> all() {
        return service.all();
    }

    @GetMapping("/search")
    public List<KnowledgeEntry> search(@RequestParam(required = false, defaultValue = "") String q,
                                       @RequestParam(required = false, defaultValue = "5") int limit) {
        return service.search(q, Math.max(1, Math.min(limit, 20)));
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.categories();
    }
}
