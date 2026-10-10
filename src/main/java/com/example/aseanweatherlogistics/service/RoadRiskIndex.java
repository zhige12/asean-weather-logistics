package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路面综合风险评分（公路侧"多要素融合"判据，纯函数、无状态）。
 * <p>
 * 为什么要它：现有熔断只看"未来6h累计降雨"这一个量，而真实公路风险是
 * 降雨 + 温度（结冰）+ 能见度（大雾/暴雨）+ 风速（侧风）+ 高温（爆胎/软化）
 * 共同决定的。本引擎把这些官方基础气象要素压成一个 0-100 的"公路风险指数"，
 * 让沙盘与智能体能说清"这条路现在到底有多危险、危险在哪"，而不是只会报"下没下暴雨"。
 * <p>
 * 与 {@link TyphoonRiskRule} 一样集中在此定义阈值，真实模式（GOWFS 全要素）与
 * 模拟模式（滑杆全要素）走同一套加权，保证口径一致。缺要素（NaN）不贡献分、绝不误升级。
 */
public final class RoadRiskIndex {

    private RoadRiskIndex() {
    }

    // 降雨分档（未来6h累计 mm）——与熔断阈值同一量级：≥80 已是熔断级，给满权重
    public static final double RAIN_FUSE_MM = 80.0;
    public static final double RAIN_HEAVY_MM = 40.0;
    public static final double RAIN_MODERATE_MM = 20.0;
    public static final double RAIN_LIGHT_MM = 10.0;
    // 结冰：气温低于此值且路面有水（降雨或高湿）即风险升级
    public static final double ICE_TEMP_C = 5.0;
    public static final double ICE_HUMIDITY_PCT = 80.0;
    // 高温：达到此值路面软化/爆胎风险，给次要权重
    public static final double HEAT_TEMP_C = 38.0;
    // 能见度分档（米）
    public static final double VIS_DENSE_M = 500.0;
    public static final double VIS_FOG_M = 1000.0;
    public static final double VIS_MIST_M = 2000.0;
    // 侧风分档（km/h）：≈6/7/8 级，高栏车、厢式车侧风失稳
    public static final double WIND_EXTREME_KPH = 60.0;
    public static final double WIND_STRONG_KPH = 40.0;
    public static final double WIND_GALE_KPH = 30.0;

    /**
     * @param score     0-100 综合评分
     * @param level     低 / 中 / 高 / 极高
     * @param colorKey  green / yellow / orange / red（前端配色键，与文案解耦）
     * @param advice    处置建议（一句话）
     * @param factors   命中的风险因子明细（供智能体解释"危险在哪"）
     */
    public record Result(int score, String level, String colorKey, String advice, List<String> factors) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", score);
            m.put("level", level);
            m.put("colorKey", colorKey);
            m.put("advice", advice);
            m.put("factors", factors);
            return m;
        }
    }

    private static boolean ok(double v) {
        return Double.isFinite(v);
    }

    /**
     * 综合评分。任一要素缺失（NaN）按"不贡献"处理，绝不因缺数据把风险抬高或压低。
     *
     * @param rainMm      未来6h累计降雨（mm）
     * @param tempC       气温（℃）
     * @param visibilityM 能见度（米）
     * @param windKph     风速（km/h）
     * @param humidityPct 相对湿度（%，用于结冰判定，可传 NaN 表示未知）
     */
    public static Result evaluate(double rainMm, double tempC, double visibilityM,
                                  double windKph, double humidityPct) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        // 降雨：主权重，与公路熔断同一量级
        if (ok(rainMm)) {
            if (rainMm >= RAIN_FUSE_MM) {
                score += 50;
                factors.add(String.format("强降雨 %.0fmm（≥%.0fmm 熔断级）", rainMm, RAIN_FUSE_MM));
            } else if (rainMm >= RAIN_HEAVY_MM) {
                score += 30;
                factors.add(String.format("大雨 %.0fmm，路面湿滑积水", rainMm));
            } else if (rainMm >= RAIN_MODERATE_MM) {
                score += 15;
                factors.add(String.format("中雨 %.0fmm，制动距离变长", rainMm));
            } else if (rainMm >= RAIN_LIGHT_MM) {
                score += 8;
                factors.add(String.format("小雨 %.0fmm，路面偏滑", rainMm));
            }
        }

        // 结冰：低温 + 路面有水（降雨或高湿）
        boolean wet = (ok(rainMm) && rainMm >= RAIN_LIGHT_MM)
                || (ok(humidityPct) && humidityPct >= ICE_HUMIDITY_PCT);
        if (ok(tempC) && tempC < ICE_TEMP_C && wet) {
            score += 25;
            factors.add(String.format("气温 %.1f℃ 且路面潮湿，结冰/暗冰风险", tempC));
        }

        // 能见度
        if (ok(visibilityM)) {
            if (visibilityM < VIS_DENSE_M) {
                score += 30;
                factors.add(String.format("能见度 %.0fm，浓雾/暴雨致视线极差", visibilityM));
            } else if (visibilityM < VIS_FOG_M) {
                score += 18;
                factors.add(String.format("能见度 %.0fm，雾天视线受阻", visibilityM));
            } else if (visibilityM < VIS_MIST_M) {
                score += 8;
                factors.add(String.format("能见度 %.0fm，轻雾/扬尘", visibilityM));
            }
        }

        // 侧风（高栏车/厢式车慎行）
        if (ok(windKph)) {
            if (windKph >= WIND_EXTREME_KPH) {
                score += 30;
                factors.add(String.format("风速 %.0fkm/h（≥8级），侧风失稳风险高", windKph));
            } else if (windKph >= WIND_STRONG_KPH) {
                score += 18;
                factors.add(String.format("风速 %.0fkm/h（≈7级），高栏车侧风风险", windKph));
            } else if (windKph >= WIND_GALE_KPH) {
                score += 8;
                factors.add(String.format("风速 %.0fkm/h（≈6级），强侧风注意", windKph));
            }
        }

        // 高温（次要，爆胎/路面软化）
        if (ok(tempC) && tempC >= HEAT_TEMP_C) {
            score += 10;
            factors.add(String.format("高温 %.1f℃，爆胎/路面软化风险", tempC));
        }

        score = Math.min(100, score);
        return level(score, factors);
    }

    /** 按分值定级 + 给配色键与处置建议。 */
    private static Result level(int score, List<String> factors) {
        if (score >= 75) {
            return new Result(score, "极高", "red", "建议熔断该路段，提前调度绕行", factors);
        }
        if (score >= 50) {
            return new Result(score, "高", "orange", "高风险，限速慎行、优先安排绕行替代方案", factors);
        }
        if (score >= 30) {
            return new Result(score, "中", "yellow", "保持关注，加大车距、降低车速", factors);
        }
        return new Result(score, "低", "green", factors.isEmpty() ? "通行条件良好" : "轻微影响，正常通行即可", factors);
    }
}
