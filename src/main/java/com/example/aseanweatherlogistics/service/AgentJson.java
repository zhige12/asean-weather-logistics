package com.example.aseanweatherlogistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 智能体输出解析小工具：从大模型文本中提取 JSON 对象（容错 ```json 包裹与前后杂文本）。 */
final class AgentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentJson() {
    }

    /** 提取文本中的第一个 JSON 对象并解析；失败返回 null。 */
    static JsonNode extractObject(String text) {
        String json = extract(text);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String extract(String s) {
        if (s == null) {
            return null;
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return null;
    }

    /** 从数组中组装 JSON 字符串数组文本（供 prompt 展示用）。 */
    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }
}
