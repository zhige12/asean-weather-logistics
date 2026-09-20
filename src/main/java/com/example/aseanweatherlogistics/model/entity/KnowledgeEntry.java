package com.example.aseanweatherlogistics.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 行业知识库条目（计划书 3.4），供 RAG 检索增强使用。
 */
@Getter
@Setter
public class KnowledgeEntry {
    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("category")
    private String category;

    @JsonProperty("keywords")
    private List<String> keywords;

    @JsonProperty("content")
    private String content;
}
