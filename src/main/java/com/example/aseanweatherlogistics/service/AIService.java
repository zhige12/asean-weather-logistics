package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 预警文案生成服务（接入 DeepSeek）。
 * 所有生成方法均内置降级模板：DeepSeek 不可用（无网络 / key 失效）时
 * 返回本地模板文案，保证"拔掉网线演示"依然可用。
 */
@Service
public class AIService {
    private static final String SYSTEM_PROMPT =
            "你是东盟跨境物流气象预警专家，精通中越双语，熟悉《国际汽车运输行车许可证》(TIR) "
            + "及跨境冷链运输操作规范。回答专业、简洁、可执行。"
            + "\n\n输出格式要求："
            + "\n1. 中文部分使用口语化表达，适合语音播报（避免 →/~ 等符号，用「至」「到」代替；"
            + "温度用「摄氏度」而非「°C」；时间用口语化表达如「9月8日15点30分」）"
            + "\n2. 越南语部分使用标准越南语拼写，保留所有声调符号（àáảãạâầấẩẫậ等），"
            + "使用专业物流术语：tuyến đường thay thế（替代路线）、cửa khẩu（口岸）、"
            + "hàng đông lạnh（冷链货物）、tự động ngắt mạch（自动熔断）、"
            + "giấy phép TIR（TIR许可证）、thời gian thông quan（通关时间）"
            + "\n3. 输出格式：【中文】段落后紧跟【Tiếng Việt】段落，两部分之间有明确分隔";

    private final DeepseekService deepseekService;
    private final RouteService routeService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final WeatherSimulator weatherSimulator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AIService(DeepseekService deepseekService, RouteService routeService,
                     KnowledgeBaseService knowledgeBaseService, WeatherSimulator weatherSimulator) {
        this.deepseekService = deepseekService;
        this.routeService = routeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.weatherSimulator = weatherSimulator;
    }

    /** 单语（中文）预警：plan-with-weather 等前端接口使用 */
    public String buildWarning(RouteResponse routeResponse) {
        String userPrompt = "你是跨境物流气象预警专家。请基于以下路径信息生成一段中文预警文案（60字以内），"
                + "包含：风险路段、影响程度与行驶建议。\n"
                + "当前路径=" + routeResponse.getPathNodeIds()
                + ", 总时长(小时)=" + routeResponse.getEstimatedHours()
                + ", 是否已绕行=" + routeResponse.isRerouted()
                + ", 风险段=" + describeRisks(routeResponse)
                + "\n\n行业知识库参考（用于支撑建议）：\n" + knowledgeContext(routeResponse);
        String text = deepseekService.chat(SYSTEM_PROMPT, userPrompt, 500);
        return text != null ? text : fallbackWarning(routeResponse);
    }

    /** 中越双语预警：演示流程步骤4（AI 生成双语专业预警） */
    public String buildBilingualWarning(RouteResponse routeResponse) {
        String pathNames = routeResponse.getPathNodeIds().stream()
                .map(id -> {
                    RoadNode n = routeService.getNode(id);
                    return n == null ? id : n.getName();
                })
                .collect(Collectors.joining(" → "));
        String userPrompt = "你是东盟跨境物流气象预警专家。请基于以下实时信息生成一段中越双语预警文案，"
                + "文案需同时适合屏幕显示和语音播报（TTS）：\n\n"
                + "【中文部分要求】\n"
                + "1. 300字以内，包含：灾害类型、影响路段、建议改道路线、预计延误时间、货损风险提示\n"
                + "2. 避免使用特殊符号（→、~、°C等），改用文字表述（至、到、摄氏度）\n"
                + "3. 日期时间使用口语化格式（如「9月8日15点30分」而非「09/08 15:30」）\n"
                + "4. 数字尽量使用中文读法（如「百分之五十」而非「50%」）\n\n"
                + "【越南语部分要求 Tiếng Việt】\n"
                + "1. 使用专业跨境物流术语：\n"
                + "   - 自动熔断 = tự động ngắt mạch\n"
                + "   - 绕行路线 = lộ trình thay thế\n"
                + "   - 冷链货损 = hư hỏng hàng đông lạnh\n"
                + "   - 通关口岸 = cửa khẩu thông quan\n"
                + "   - 能见度不足 = tầm nhìn hạn chế\n"
                + "   - 侧翻风险 = nguy cơ lật xe\n"
                + "2. 适配越南司机阅读习惯，语言简洁直接\n"
                + "3. 使用完整越南语标音符号（dấu thanh điệu đầy đủ）\n\n"
                + "【结尾要求】\n"
                + "附一句《国际汽车运输行车许可证》(TIR) 合规提示，中越双语。\n\n"
                + "当前风险：" + describeRisks(routeResponse) + "\n"
                + "系统是否已自动熔断并绕行：" + (routeResponse.isRerouted() ? "是" : "否") + "\n"
                + "建议路线：" + pathNames + "\n"
                + "总耗时(小时)：" + routeResponse.getEstimatedHours() + "\n"
                + "基准耗时(小时)：" + routeResponse.getBaselineHours() + "\n"
                + "因熔断额外延误(小时)：" + routeResponse.getExtraHours()
                + "\n\n行业知识库依据（检索增强，必须以此为准绳给出建议）：\n" + knowledgeContext(routeResponse);
        String text = deepseekService.chat(SYSTEM_PROMPT, userPrompt, 1500);
        return text != null ? text : fallbackBilingual(routeResponse);
    }

    /**
     * AI + 实时气象灾害预测（核心闭环）：
     * 1) 拉取沿线实时气象原始数据（温度/降水/风速/能见度）；
     * 2) 把实时气象数据喂给 DeepSeek，让它预测未来 6-12 小时可能发生的自然灾害；
     * 3) 解析预测结果并注入 WeatherSimulator（触发 agent 实时重算 + SSE 推送新路线）；
     * 4) DeepSeek 不可用/解析失败时降级为本地规则引擎（基于实时气象阈值）。
     */
    public Map<String, Object> predictWeatherHazards(String originId, String destinationId) {
        // 1) 实时气象原始数据（沿线节点）
        List<Map<String, Object>> weather = routeService.syncRealWeatherForRoute(false, originId, destinationId);
        if (weather == null) {
            weather = new ArrayList<>();
        }
        // 2) DeepSeek 预测
        List<Map<String, Object>> predictions = new ArrayList<>();
        String source = "rule";
        try {
            String userPrompt = "你是东盟跨境物流自然灾害预测专家。请基于以下【实时气象实测数据】预测未来6-12小时内"
                    + "沿线各城市可能发生的自然灾害（如暴雨引发的山洪/泥石流、大风封路、大雾低能见度、高温冷链货损等）。\n"
                    + "严格只返回 JSON，不要任何其他文字，格式：\n"
                    + "{\"hazards\":[{\"nodeId\":\"...\",\"nodeName\":\"...\",\"hazardType\":\"灾害类型\","
                    + "\"severity\":\"HIGH|MEDIUM|CRITICAL\",\"reason\":\"预测依据\",\"advice\":\"处置建议\",\"probability\":0-1}]}\n\n"
                    + "实时气象数据（城市 温度°C 降水mm/h 风速km/h 能见度m）：\n" + formatWeather(weather)
                    + "\n\n注意：nodeId/nodeName 必须严格取自上面给定的数据，只预测有较高发生可能的灾害，"
                    + "最多返回5条。";
            String text = deepseekService.chat("You are a disaster forecaster. Always respond with valid JSON only.",
                    userPrompt, 800);
            predictions = parseHazards(text);
            if (!predictions.isEmpty()) {
                source = "deepseek";
            }
        } catch (Exception ignored) {
            // 本地规则引擎降级
        }
        if (predictions.isEmpty()) {
            predictions = ruleBasedHazards(weather);
        }
        // 3) 注入风险 → 触发 agent 重算 + SSE 推送
        int injected = 0;
        List<String> injectedEdges = new ArrayList<>();
        for (Map<String, Object> h : predictions) {
            injected += injectHazard(h, injectedEdges);
        }
        // 4) 组装返回
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("weather", weather);
        out.put("hazards", predictions);
        out.put("injected", injected);
        out.put("injectedEdges", injectedEdges);
        out.put("ts", System.currentTimeMillis());
        out.put("warning", summaryText(predictions, source, injected));
        return out;
    }

    /** 实时气象点格式化为 prompt 文本 */
    private String formatWeather(List<Map<String, Object>> weather) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> w : weather) {
            sb.append("- ").append(w.get("nodeName")).append(" (nodeId=").append(w.get("nodeId")).append(")")
                    .append(" 温度").append(w.get("temperatureC")).append("°C")
                    .append(" 降水").append(w.get("precipitationMm")).append("mm/h")
                    .append(" 风速").append(w.get("windKph")).append("km/h")
                    .append(" 能见度").append(w.get("visibilityM")).append("m\n");
        }
        return sb.toString();
    }

    /** 解析 DeepSeek 返回的灾害 JSON */
    private List<Map<String, Object>> parseHazards(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart < 0 || objEnd <= objStart) {
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(objStart, objEnd + 1));
            JsonNode arr = root.has("hazards") ? root.get("hazards") : root;
            if (arr == null || !arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                if (!n.isObject()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", n.path("nodeId").asText(""));
                item.put("nodeName", n.path("nodeName").asText(""));
                item.put("hazardType", n.path("hazardType").asText("气象异常"));
                item.put("severity", normalizeSeverity(n.path("severity").asText("MEDIUM")));
                item.put("reason", n.path("reason").asText(""));
                item.put("advice", n.path("advice").asText(""));
                item.put("probability", n.path("probability").asDouble(0.6));
                if (!item.get("nodeId").toString().isBlank()) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
            // 解析失败 → 调用方降级规则引擎
        }
        return out;
    }

    /** 本地规则引擎：基于实时气象阈值预测灾害（DeepSeek 不可用时的降级路径） */
    private List<Map<String, Object>> ruleBasedHazards(List<Map<String, Object>> weather) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : weather) {
            String nodeId = String.valueOf(w.get("nodeId"));
            String nodeName = String.valueOf(w.get("nodeName"));
            double precip = ((Number) w.getOrDefault("precipitationMm", 0)).doubleValue();
            double wind = ((Number) w.getOrDefault("windKph", 0)).doubleValue();
            double vis = ((Number) w.getOrDefault("visibilityM", 0)).doubleValue();
            double temp = ((Number) w.getOrDefault("temperatureC", 0)).doubleValue();
            if (precip >= 50) {
                out.add(hazard(nodeId, nodeName, "暴雨/山洪", "CRITICAL",
                        "实时降水" + precip + "mm/h，山区段易发山洪与泥石流",
                        "避开山区低洼路段，就近避险", 0.85));
            } else if (precip >= 20) {
                out.add(hazard(nodeId, nodeName, "大雨", "HIGH",
                        "实时降水" + precip + "mm/h，道路湿滑且局部积水",
                        "减速慢行，拉大车距", 0.7));
            }
            if (wind >= 60) {
                out.add(hazard(nodeId, nodeName, "大风", "HIGH",
                        "实时风速" + wind + "km/h，集装箱车侧翻风险升高",
                        "减速并避开桥梁、高架路段", 0.75));
            } else if (wind >= 40) {
                out.add(hazard(nodeId, nodeName, "阵风", "MEDIUM",
                        "实时风速" + wind + "km/h，注意横风",
                        "握稳方向盘，避免急打方向", 0.55));
            }
            if (vis < 100) {
                out.add(hazard(nodeId, nodeName, "大雾", "CRITICAL",
                        "能见度不足100m，口岸通关与行车严重受阻",
                        "开启雾灯，必要时就近停靠等待", 0.9));
            } else if (vis < 500) {
                out.add(hazard(nodeId, nodeName, "轻雾", "MEDIUM",
                        "能见度" + (int) vis + "m，注意观察",
                        "开启示廓灯，控制车速", 0.5));
            }
            if (temp >= 35) {
                out.add(hazard(nodeId, nodeName, "高温冷链", "MEDIUM",
                        "实时温度" + temp + "°C，冷链货损风险上升",
                        "检查制冷机组，避开正午时段", 0.65));
            }
        }
        return out;
    }

    private Map<String, Object> hazard(String nodeId, String nodeName, String type,
                                       String severity, String reason, String advice, double probability) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId);
        m.put("nodeName", nodeName);
        m.put("hazardType", type);
        m.put("severity", normalizeSeverity(severity));
        m.put("reason", reason);
        m.put("advice", advice);
        m.put("probability", probability);
        return m;
    }

    private String normalizeSeverity(String s) {
        if (s == null) {
            return "MEDIUM";
        }
        String u = s.trim().toUpperCase();
        if (u.contains("CRIT") || u.equals("HIGH")) {
            return u.contains("CRIT") ? "CRITICAL" : "HIGH";
        }
        return "MEDIUM";
    }

    /** 将单个灾害预测注入到受影响节点关联的边，返回注入边数 */
    private int injectHazard(Map<String, Object> h, List<String> injectedEdges) {
        String nodeId = String.valueOf(h.get("nodeId"));
        if (nodeId == null || nodeId.isBlank() || "null".equals(nodeId)) {
            return 0;
        }
        String severity = String.valueOf(h.getOrDefault("severity", "MEDIUM"));
        double penalty = switch (severity) {
            case "CRITICAL" -> 100;
            case "HIGH" -> 30;
            default -> 8;
        };
        String reason = "AI预测:" + h.get("hazardType") + "(" + h.get("reason") + ")";
        int count = 0;
        for (RoadEdge e : routeService.getAllEdges()) {
            if (nodeId.equals(e.getFromNodeId()) || nodeId.equals(e.getToNodeId())) {
                RiskSegment rs = new RiskSegment(e.getId(), reason, severity, penalty);
                weatherSimulator.injectRisk(rs);
                injectedEdges.add(e.getId());
                count++;
            }
        }
        return count;
    }

    // ==================== 路线级灾害概率预测（司机选路 / 途中实时预测）====================

    /**
     * 预测"某条具体路线"未来发生灾害的概率（0-100 整数），供司机在出发前比较各候选路线。
     * 纯预测、不注入风险（区别于 predictWeatherHazards 会注入路网触发绕行）。
     *
     * <p>概率合成：把沿线各项灾害视为独立事件，P = 1 - Π(1 - pᵢ)；
     * 若该路线已经命中正在发生的风险段，则视为"已发生"，概率直接抬到 ≥90 并置 occurred=true。</p>
     *
     * @param deep true 时调用 DeepSeek 做深度预测（较慢，供途中实时预测）；false 走实时气象规则引擎（毫秒级，供选路列表）
     */
    public Map<String, Object> predictRouteRisk(String originId, String destinationId,
                                                List<String> pathNodeIds, List<String> pathEdgeIds,
                                                boolean deep) {
        // 1) 沿线实时气象（带 TTL 缓存，毫秒级）
        List<Map<String, Object>> weather;
        try {
            weather = (pathNodeIds != null && !pathNodeIds.isEmpty())
                    ? routeService.syncRealWeather(false, pathNodeIds)
                    : routeService.syncRealWeatherForRoute(false, originId, destinationId);
        } catch (Exception e) {
            weather = new ArrayList<>();
        }
        if (weather == null) {
            weather = new ArrayList<>();
        }

        // 2) 灾害预测：DeepSeek（deep=true）→ 失败降级规则引擎
        List<Map<String, Object>> hazards = new ArrayList<>();
        String source = "rule";
        if (deep) {
            try {
                String userPrompt = "你是东盟跨境物流自然灾害预测专家。请基于以下【实时气象实测数据】预测未来6-12小时内"
                        + "该路线沿线可能发生的自然灾害。\n严格只返回 JSON：\n"
                        + "{\"hazards\":[{\"nodeId\":\"...\",\"nodeName\":\"...\",\"hazardType\":\"灾害类型\","
                        + "\"severity\":\"HIGH|MEDIUM|CRITICAL\",\"reason\":\"预测依据\",\"advice\":\"处置建议\",\"probability\":0-1}]}\n\n"
                        + "实时气象数据（城市 温度°C 降水mm/h 风速km/h 能见度m）：\n" + formatWeather(weather)
                        + "\n\n注意：nodeId 必须取自上面数据，最多返回5条。";
                hazards = parseHazards(deepseekService.chat(
                        "You are a disaster forecaster. Always respond with valid JSON only.", userPrompt, 800));
                if (!hazards.isEmpty()) {
                    source = "deepseek";
                }
            } catch (Exception ignored) {
                // 降级规则引擎
            }
        }
        if (hazards.isEmpty()) {
            hazards = ruleBasedHazards(weather);
        }

        // 3) 是否已经发生：该路线是否命中当前正在生效的风险段
        boolean occurred = false;
        List<String> occurredReasons = new ArrayList<>();
        if (pathEdgeIds != null && !pathEdgeIds.isEmpty()) {
            Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
            for (String eid : pathEdgeIds) {
                RiskSegment rs = riskMap == null ? null : riskMap.get(eid);
                if (rs != null) {
                    occurred = true;
                    if (rs.getReason() != null && occurredReasons.size() < 3) {
                        occurredReasons.add(rs.getReason());
                    }
                }
            }
        }

        // 4) 逐灾种分项概率（司机端"查看详情"逐项展示，如暴雨 42% / 大雾 8% …）
        List<Map<String, Object>> breakdown =
                buildBreakdown(weather, hazards, occurredReasons);

        // 5) 综合概率合成：把各灾种视为独立事件，P = 1 - Π(1 - pᵢ)
        double pNone = 1.0;
        List<String> types = new ArrayList<>();
        for (Map<String, Object> b : breakdown) {
            double p = ((Number) b.getOrDefault("probability", 0)).doubleValue() / 100.0;
            p = Math.max(0.0, Math.min(1.0, p));
            pNone *= (1.0 - p);
            if (Boolean.TRUE.equals(b.get("occurred")) || p >= 0.3) {
                types.add(String.valueOf(b.get("label")));
            }
        }
        int pct = (int) Math.round((1.0 - pNone) * 100);
        if (!weather.isEmpty()) {
            pct = Math.max(pct, baselineFromWeather(weather));
        } else if (hazards.isEmpty()) {
            pct = 8; // 完全无气象数据时保守基线
        }
        if (occurred) {
            pct = Math.max(pct, 90); // 已经发生 → 高概率
        }
        pct = Math.max(0, Math.min(100, pct));

        // 5) 组装
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", pct);
        out.put("high", pct >= HAZARD_HIGH_PCT || occurred);
        out.put("level", (pct >= HAZARD_HIGH_PCT || occurred) ? "高风险" : "正常");
        out.put("hazardTypes", types);
        out.put("breakdown", breakdown); // 逐灾种概率（查看详情用）
        out.put("hasWeather", !weather.isEmpty());
        out.put("occurred", occurred);
        out.put("occurredReasons", occurredReasons);
        out.put("source", source);
        out.put("weatherPoints", weather.size());
        out.put("reason", buildRiskReason(hazards, occurred, occurredReasons, weather));
        out.put("advice", hazards.isEmpty()
                ? (occurred ? "沿线已有风险生效，建议改走其他路线" : "沿线气象平稳，可按此路线行驶")
                : String.valueOf(hazards.get(0).getOrDefault("advice", "谨慎驾驶，实时关注气象")));
        out.put("ts", System.currentTimeMillis());
        return out;
    }

    /** 选路场景的快捷重载（不调 DeepSeek，保证候选列表毫秒级返回） */
    public Map<String, Object> predictRouteRisk(String originId, String destinationId, List<String> pathNodeIds) {
        return predictRouteRisk(originId, destinationId, pathNodeIds, null, false);
    }

    /** 高风险阈值（%）：达到即标红；未达一律标绿 */
    private static final int HAZARD_HIGH_PCT = 50;

    /** 常见灾害类型清单（司机端"查看详情"逐项展示，顺序即展示顺序） */
    private static final String[][] HAZARD_CATALOG = {
            {"RAIN", "暴雨/山洪", "暴雨积水、山区路段山洪与低洼路段淹没"},
            {"WIND", "大风/台风", "横风导致集装箱车侧翻、桥梁与高架段封闭"},
            {"FOG", "大雾/低能见度", "能见度不足导致追尾风险与口岸通关受阻"},
            {"HEAT", "高温/冷链货损", "车厢温度失控导致冷链货物损耗"},
            {"LANDSLIDE", "泥石流/地质灾害", "持续降水后山区边坡失稳、落石与泥石流"}
    };

    /** 各灾种默认处置建议 */
    private static String adviceFor(String key) {
        return switch (key) {
            case "RAIN" -> "避开山区与低洼路段，积水超 30cm 禁止通行";
            case "WIND" -> "减速慢行，避开桥梁、高架与广告牌路段";
            case "FOG" -> "开启雾灯与示廓灯，能见度<50m 就近停靠等待";
            case "HEAT" -> "检查制冷机组，避开正午时段并缩短开门时间";
            case "LANDSLIDE" -> "绕行山区走廊，雨后 24h 内勿靠边坡行驶";
            default -> "谨慎驾驶，实时关注气象预警";
        };
    }

    /**
     * 逐灾种概率明细：融合 ①实时气象实测阈值 ②AI(DeepSeek)预测 ③已生效风险 三路信号，
     * 同一灾种取三者最大值（任一信号认为危险就以高的为准）。
     */
    private List<Map<String, Object>> buildBreakdown(List<Map<String, Object>> weather,
                                                     List<Map<String, Object>> aiHazards,
                                                     List<String> occurredReasons) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] cat : HAZARD_CATALOG) {
            String key = cat[0];
            String label = cat[1];
            String desc = cat[2];

            // ① 实时气象实测（取沿线最"恶劣"的那个点）
            double p = weather.isEmpty() ? 0.02 : ruleProbForType(key, weather);

            // ② AI 预测：按灾害类型关键词归并到标准灾种，取最大值
            for (Map<String, Object> h : aiHazards) {
                if (typeMatches(key, String.valueOf(h.getOrDefault("hazardType", "")))) {
                    double hp = ((Number) h.getOrDefault("probability", 0.0)).doubleValue();
                    p = Math.max(p, Math.min(1.0, hp));
                }
            }

            // ③ 已生效风险：关键词命中即视为该灾种"已发生"
            boolean occurred = false;
            for (String r : occurredReasons) {
                if (r != null && typeMatches(key, r)) {
                    occurred = true;
                    break;
                }
            }
            if (occurred) {
                p = Math.max(p, 0.92);
            }

            int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, p)) * 100);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("label", label);
            item.put("probability", pct);
            // 两档制：达到阈值即"高风险"标红，未达阈值一律"正常"标绿（不做中间黄档，避免视觉噪音）
            item.put("high", pct >= HAZARD_HIGH_PCT || occurred);
            item.put("level", (pct >= HAZARD_HIGH_PCT || occurred) ? "高风险" : "正常");
            item.put("desc", desc);
            item.put("advice", adviceFor(key));
            item.put("occurred", occurred);
            item.put("reason", reasonForType(key, weather, occurred));
            out.add(item);
        }
        return out;
    }

    /** 灾害类型关键词归并（AI 文案/风险原因 → 标准灾种） */
    private boolean typeMatches(String key, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        // 注意：中文"风险"也含"风"字，必须先剔除再判风类，否则泥石流的"次生灾害风险"会被误判为台风
        String noRisk = t.replace("风险", "");
        return switch (key) {
            case "RAIN" -> t.contains("雨") || t.contains("rain") || t.contains("山洪") || t.contains("积水");
            case "WIND" -> noRisk.contains("风") || t.contains("wind") || t.contains("台风")
                    || t.contains("typhoon") || t.contains("阵风");
            case "FOG" -> t.contains("雾") || t.contains("fog") || t.contains("能见度");
            case "HEAT" -> t.contains("高温") || t.contains("heat") || t.contains("冷链") || t.contains("温度");
            case "LANDSLIDE" -> t.contains("泥石流") || t.contains("滑坡") || t.contains("塌方") || t.contains("landslide") || t.contains("地质");
            default -> false;
        };
    }

    /** 按实时气象实测值推算某灾种概率（均值+最恶劣加权，不同路线有区分度） */
    private double ruleProbForType(String key, List<Map<String, Object>> weather) {
        if (weather.isEmpty()) return 0.02;
        double sum = 0.0;
        double worst = 0.0;
        int count = 0;
        for (Map<String, Object> w : weather) {
            double precip = num(w, "precipitationMm");
            double wind = num(w, "windKph");
            double vis = num(w, "visibilityM");
            double temp = num(w, "temperatureC");
            double p = switch (key) {
                case "RAIN" -> precip >= 50 ? 0.85 : precip >= 20 ? 0.70 : precip >= 8 ? 0.45
                        : precip >= 2 ? 0.25 : precip >= 1 ? 0.12 : precip > 0 ? 0.05 : 0.02;
                case "WIND" -> wind >= 60 ? 0.75 : wind >= 40 ? 0.55 : wind >= 25 ? 0.30 : 0.02;
                case "FOG" -> vis <= 0 ? 0.02 : vis < 100 ? 0.90 : vis < 500 ? 0.50 : vis < 1000 ? 0.30 : vis < 2000 ? 0.15 : 0.02;
                case "HEAT" -> temp >= 35 ? 0.65 : temp >= 32 ? 0.45 : temp >= 30 ? 0.30 : 0.02;
                case "LANDSLIDE" -> precip >= 35 ? 0.60 : precip >= 20 ? 0.35 : precip >= 8 ? 0.15 : 0.01;
                default -> 0.02;
            };
            sum += p;
            worst = Math.max(worst, p);
            count++;
        }
        // 均值(60%) + 最恶劣(40%) 混合，使不同路线有区分度
        return sum / count * 0.6 + worst * 0.4;
    }

    /** 某灾种的依据说明（实测值 → 司机看得懂的话） */
    private String reasonForType(String key, List<Map<String, Object>> weather, boolean occurred) {
        if (occurred) {
            return "该灾害已在沿线生效，属于进行中的风险";
        }
        if (weather.isEmpty()) {
            return "暂无实时气象数据，按历史基线估算";
        }
        double precip = 0, wind = 0, temp = 0, vis = Double.MAX_VALUE;
        for (Map<String, Object> w : weather) {
            precip = Math.max(precip, num(w, "precipitationMm"));
            wind = Math.max(wind, num(w, "windKph"));
            temp = Math.max(temp, num(w, "temperatureC"));
            double v = num(w, "visibilityM");
            if (v > 0) {
                vis = Math.min(vis, v);
            }
        }
        return switch (key) {
            case "RAIN" -> "沿线最大降水 " + round1(precip) + "mm/h（暴雨阈值 50mm/h）";
            case "WIND" -> "沿线最大风速 " + round1(wind) + "km/h（大风阈值 60km/h）";
            case "FOG" -> vis == Double.MAX_VALUE ? "沿线能见度数据缺失"
                    : "沿线最低能见度 " + (int) vis + "m（大雾阈值 500m）";
            case "HEAT" -> "沿线最高气温 " + round1(temp) + "°C（冷链风险阈值 30°C）";
            case "LANDSLIDE" -> "沿线最大降水 " + round1(precip) + "mm/h，山区段失稳阈值 35mm/h";
            default -> "";
        };

    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * 气象压力基线：把沿线实测值相对灾害阈值的"接近程度"折算成 2%~29% 的基线概率。
     * 用于无明确灾害预测时给出一个诚实的非零值（越接近阈值概率越高）。
     */
    private int baselineFromWeather(List<Map<String, Object>> weather) {
        double stress = 0.0;
        for (Map<String, Object> w : weather) {
            double precip = num(w, "precipitationMm");
            double wind = num(w, "windKph");
            double vis = num(w, "visibilityM");
            double temp = num(w, "temperatureC");
            stress = Math.max(stress, Math.min(1.0, precip / 50.0));
            stress = Math.max(stress, Math.min(1.0, wind / 60.0) * 0.8);
            if (vis > 0) {
                stress = Math.max(stress, Math.max(0.0, Math.min(1.0, (2000.0 - vis) / 2000.0)) * 0.7);
            }
            if (temp >= 30) {
                stress = Math.max(stress, Math.min(1.0, (temp - 30.0) / 10.0) * 0.6);
            }
        }
        return Math.min(29, Math.max(2, (int) Math.round(stress * 28)));
    }

    private double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** 概率说明文案：把各项灾害与依据拼成一句司机看得懂的话 */
    private String buildRiskReason(List<Map<String, Object>> hazards, boolean occurred,
                                   List<String> occurredReasons, List<Map<String, Object>> weather) {
        StringBuilder sb = new StringBuilder();
        if (occurred) {
            sb.append("该路线已有风险生效：").append(String.join("；", occurredReasons)).append("。");
        }
        if (!hazards.isEmpty()) {
            sb.append("未来6-12小时沿线可能出现：");
            int i = 0;
            for (Map<String, Object> h : hazards) {
                if (i++ >= 3) {
                    break;
                }
                sb.append(h.get("hazardType")).append("(").append(h.get("nodeName")).append(")");
                if (i < Math.min(3, hazards.size())) {
                    sb.append("、");
                }
            }
            sb.append("。");
        } else if (!occurred) {
            sb.append("沿线 ").append(weather.size()).append(" 个气象点实测平稳，未达灾害阈值。");
        }
        return sb.toString();
    }

    /** 生成预测摘要文案 */
    private String summaryText(List<Map<String, Object>> hazards, String source, int injected) {
        if (hazards.isEmpty()) {
            return "当前沿线实时气象未发现明显灾害风险，道路通行正常。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(source.equals("deepseek") ? "AI 灾害预测（DeepSeek + 实时气象）" : "灾害预测（实时气象规则引擎）")
                .append("：未来 6-12 小时沿线存在 ").append(hazards.size()).append(" 项风险");
        if (injected > 0) {
            sb.append("，已注入 ").append(injected).append(" 条受影响路段并实时重算路线");
        }
        sb.append("。");
        int i = 0;
        for (Map<String, Object> h : hazards) {
            if (i++ >= 5) {
                break;
            }
            sb.append("\n· ").append(h.get("nodeName")).append("：").append(h.get("hazardType"))
                    .append("（").append(h.get("severity")).append("，概率").append(h.get("probability")).append("）")
                    .append(" ").append(h.get("advice"));
        }
        return sb.toString();
    }

    /**
     * RAG 检索增强：按风险原因（如"暴雨·友谊关"）拆词检索行业知识库，
     * 将命中的知识条目作为大模型生成依据，离线降级时直接展示。
     */
    private String knowledgeContext(RouteResponse routeResponse) {
        List<String> queries = new ArrayList<>();
        if (routeResponse.getRiskSegments() != null) {
            for (RiskSegment rs : routeResponse.getRiskSegments()) {
                if (rs.getReason() == null) {
                    continue;
                }
                for (String part : rs.getReason().split("[·、,，;；\\s]+")) {
                    String token = part.trim();
                    if (!token.isEmpty()) {
                        queries.add(token);
                    }
                }
            }
        }
        if (queries.isEmpty()) {
            return "当前无气象风险，暂无行业知识依据。";
        }
        List<KnowledgeEntry> matched = new ArrayList<>();
        for (String q : queries) {
            for (KnowledgeEntry e : knowledgeBaseService.search(q, 1)) {
                if (!matched.contains(e)) {
                    matched.add(e);
                }
                if (matched.size() >= 2) {
                    break;
                }
            }
            if (matched.size() >= 2) {
                break;
            }
        }
        if (matched.isEmpty()) {
            return "行业知识库暂未命中相关条目。";
        }
        return matched.stream()
                .map(e -> "[" + e.getId() + " " + e.getTitle() + "] " + e.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String describeRisks(RouteResponse routeResponse) {
        if (routeResponse.getRiskSegments() == null || routeResponse.getRiskSegments().isEmpty()) {
            return "无";
        }
        return routeResponse.getRiskSegments().stream()
                .map(rs -> edgeName(rs.getEdgeId()) + "(" + rs.getReason() + ")")
                .collect(Collectors.joining("；"));
    }

    private String edgeName(String edgeId) {
        for (RoadEdge e : routeService.getAllEdges()) {
            if (e.getId().equals(edgeId)) {
                return e.getName() == null ? edgeId : e.getName();
            }
        }
        return edgeId;
    }

    // ---- 离线降级模板（保证拔网线演示可用） ----

    private String fallbackWarning(RouteResponse route) {
        String riskDesc = describeRisks(route);
        if (route.isRerouted()) {
            return "气象预警：" + riskDesc + "，系统已自动熔断高风险路段并切换绕行路线，"
                    + "预计增加 " + route.getExtraHours() + " 小时，请按新路线行驶、注意安全。";
        }
        if (route.getRiskSegments() == null || route.getRiskSegments().isEmpty()) {
            return "当前路线通行正常，无气象风险，请保持安全车距。";
        }
        return "气象预警：" + riskDesc + "，风险等级较高，请谨慎驾驶并留意最新通知。";
    }

    private String fallbackBilingual(RouteResponse route) {
        String riskDesc = describeRisks(route);
        String pathNames = route.getPathNodeIds().stream()
                .map(id -> {
                    RoadNode n = routeService.getNode(id);
                    return n == null ? id : n.getName();
                })
                .collect(Collectors.joining(" → "));
        String zh = "【中文】\n"
                + "气象预警：" + riskDesc + "。\n"
                + "影响路段：高风险路段已" + (route.isRerouted() ? "自动熔断" : "处于监控中") + "，"
                + "系统已" + (route.isRerouted() ? "重新规划路线" : "保持原路线") + "。\n"
                + "建议路线：" + pathNames + "，总耗时约 " + route.getEstimatedHours() + " 小时"
                + (route.getExtraHours() > 0 ? "（较基准增加 " + route.getExtraHours() + " 小时）" : "") + "。\n"
                + "货损风险提示：请提前检查货物包装与温控设备，冷链货物注意保温。\n"
                + "请驾驶员随车携带《国际汽车运输行车许可证》(TIR) 备查，通关时主动出示。\n"
                + "行业知识库依据：\n" + knowledgeContext(route);
        String vi = "【Tiếng Việt】\n"
                + "CẢNH BÁO THỜI TIẾT VẬN TẢI: " + vietnameseRiskDesc(route) + ".\n"
                + "Đoạn đường nguy hiểm đã "
                + (route.isRerouted()
                    ? "được tự động ngắt mạch và chuyển hướng."
                    : "được đưa vào giám sát chặt chẽ.") + "\n"
                + "Hệ thống đã " + (route.isRerouted() ? "tính toán lại lộ trình thay thế" : "giữ nguyên lộ trình hiện tại") + ".\n"
                + "Lộ trình khuyến nghị: " + pathNames + ".\n"
                + "Tổng thời gian hành trình dự kiến: khoảng " + route.getEstimatedHours() + " giờ"
                + (route.getExtraHours() > 0 ? " (tăng thêm " + route.getExtraHours() + " giờ so với lộ trình gốc)" : "") + ".\n"
                + "CẢNH BÁO HÀNG HÓA: Vui lòng kiểm tra bao bì và thiết bị kiểm soát nhiệt độ trước khi khởi hành. "
                + (route.getExtraHours() > 4
                    ? "Nguy cơ hư hỏng hàng đông lạnh do chậm trễ kéo dài. "
                    : "")
                + "Hàng đông lạnh cần duy trì nhiệt độ ổn định trong suốt hành trình.\n"
                + "THỦ TỤC HẢI QUAN: Tài xế vui lòng mang theo Giấy phép Vận tải Quốc tế (TIR) "
                + "và xuất trình tại cửa khẩu. Đảm bảo chứng từ hải quan đầy đủ và hợp lệ.\n"
                + "KHUYẾN CÁO: Tuân thủ tốc độ an toàn, theo dõi cập nhật thời tiết thường xuyên.\n"
                + "Căn cứ kiến thức chuyên ngành:\n" + knowledgeContext(route);
        return zh + "\n\n" + vi;
    }

    /** 将风险描述转换为越南语（用于 fallback 模板） */
    private String vietnameseRiskDesc(RouteResponse route) {
        if (route.getRiskSegments() == null || route.getRiskSegments().isEmpty()) {
            return "Không có rủi ro thời tiết";
        }
        return route.getRiskSegments().stream()
                .map(rs -> edgeName(rs.getEdgeId()) + "(" + vietnameseHazardType(rs.getReason()) + ")")
                .collect(Collectors.joining("; "));
    }

    /** 将中文灾害类型转换为越南语 */
    private String vietnameseHazardType(String reason) {
        if (reason == null) return "rủi ro thời tiết";
        String r = reason;
        if (r.contains("暴雨") || r.contains("强降雨") || r.contains("特大暴雨")) return "mưa lớn";
        if (r.contains("大雨")) return "mưa to";
        if (r.contains("塌方") || r.contains("滑坡") || r.contains("泥石流")) return "sạt lở đất";
        if (r.contains("台风") || r.contains("风暴")) return "bão";
        if (r.contains("大风") || r.contains("阵风")) return "gió mạnh";
        if (r.contains("大雾") || r.contains("低能见度") || r.contains("能见度")) return "sương mù dày đặc";
        if (r.contains("高温") || r.contains("热浪")) return "nhiệt độ cao";
        if (r.contains("山洪") || r.contains("积水")) return "lũ quét";
        return "rủi ro thời tiết";
    }
}
