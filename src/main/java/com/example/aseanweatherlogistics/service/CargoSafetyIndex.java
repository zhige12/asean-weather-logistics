package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.service.DemoConfigService.CargoType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链 / 货物安全指数（货损侧"多要素融合"判据，纯函数、无状态）。
 * <p>
 * 现有 {@link CargoType#damageRate} 只按"延误时长"算货损，货损是被动结果；
 * 而真实货损还受<b>运输途中环境温度、湿度</b>直接影响——冷链火龙果高温制冷跟不上会加速腐损，
 * 高湿会霉变，电子元件高湿会受潮。本引擎把 温度 + 湿度 + 延误 合成 0-100 的"货损风险指数"，
 * 并折算一个货损金额估计，让沙盘/智能体能提示"建议优先安排绕行、减少延误""检查制冷设备"。
 * <p>
 * 冷链货（coldChain）对高温更敏感、权重更高；普货只作次要影响。缺要素（NaN）不贡献分。
 */
public final class CargoSafetyIndex {

    private CargoSafetyIndex() {
    }

    /** 冷链货高温起算点（℃）：超过后制冷负荷与货损随温度上升 */
    public static final double COLD_HEAT_C = 30.0;
    /** 普货高温影响起点（℃） */
    public static final double GEN_HEAT_C = 38.0;
    /** 高湿霉变/受潮起算点（%） */
    public static final double HUMID_MOLD_PCT = 85.0;

    /** 每超 1℃（冷链）累加的货损风险分 */
    private static final double COLD_HEAT_PER_C = 6.0;
    /** 每超 1℃（普货）累加的风险分 */
    private static final double GEN_HEAT_PER_C = 3.0;
    /** 高湿霉变固定风险分 */
    private static final double MOLD_SCORE = 15.0;
    /** 延误每超起算 1 小时累加的风险分（封顶 60） */
    private static final double DELAY_PER_H = 8.0;
    private static final double DELAY_SCORE_CAP = 60.0;
    /** 环境风险满格（100）对应的货值损失率上限（经验值，霉变+高温综合） */
    private static final double ENV_LOSS_RATE_AT_100 = 0.30;

    /**
     * @param score             0-100 货损风险指数
     * @param level             低 / 中 / 高 / 极高
     * @param colorKey          green / yellow / orange / red
     * @param advice            处置建议（一句话）
     * @param factors           命中因子明细
     * @param cargoName         货物名（回显）
     * @param coldChain         是否冷链货
     * @param delayHours        参与计算的延误时长
     * @param estimatedLossYuan 折算货损金额估计（元）
     */
    public record Result(int score, String level, String colorKey, String advice, List<String> factors,
                         String cargoName, boolean coldChain, double delayHours, double estimatedLossYuan) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", score);
            m.put("level", level);
            m.put("colorKey", colorKey);
            m.put("advice", advice);
            m.put("factors", factors);
            m.put("cargoName", cargoName);
            m.put("coldChain", coldChain);
            m.put("delayHours", delayHours);
            m.put("estimatedLossYuan", Math.round(estimatedLossYuan));
            return m;
        }
    }

    private static boolean ok(double v) {
        return Double.isFinite(v);
    }

    /**
     * @param tempC       气温（℃）
     * @param humidityPct 相对湿度（%）
     * @param delayHours  预计延误时长（h），沙盘静态态可传 0，改线方案阶段由 extraHours 传入
     * @param cargo       货物类型（含冷链标记、货值、延误起算与货损率）
     */
    public static Result evaluate(double tempC, double humidityPct, double delayHours, CargoType cargo) {
        List<String> factors = new ArrayList<>();
        double risk = 0;
        boolean cold = cargo != null && cargo.coldChain();

        // 高温：冷链货制冷负荷/腐损随温度陡升，普货影响较小
        if (ok(tempC)) {
            if (cold && tempC > COLD_HEAT_C) {
                risk += (tempC - COLD_HEAT_C) * COLD_HEAT_PER_C;
                factors.add(String.format("气温 %.1f℃ 超冷链阈值 %.0f℃，制冷负荷与货损上升", tempC, COLD_HEAT_C));
            } else if (!cold && tempC > GEN_HEAT_C) {
                risk += (tempC - GEN_HEAT_C) * GEN_HEAT_PER_C;
                factors.add(String.format("高温 %.1f℃，普货受热变形/变质风险", tempC));
            }
        }

        // 高湿：霉变（生鲜）/ 受潮（电子）
        if (ok(humidityPct) && humidityPct > HUMID_MOLD_PCT) {
            risk += MOLD_SCORE;
            factors.add(String.format("湿度 %.0f%% 超 %.0f%%，%s风险",
                    humidityPct, HUMID_MOLD_PCT, cold ? "霉变" : "受潮"));
        }

        // 延误：超过货损起算时间后逐小时累积（沿用 CargoType 模型口径）
        double delay = Math.max(0.0, delayHours);
        if (cargo != null && delay > cargo.damageStartHours()) {
            double over = delay - cargo.damageStartHours();
            risk += Math.min(DELAY_SCORE_CAP, over * DELAY_PER_H);
            factors.add(String.format("预计延误 %.1fh 超起算 %.1fh，货损逐时累积",
                    delay, cargo.damageStartHours()));
        }

        risk = Math.min(100.0, risk);
        int score = (int) Math.round(risk);

        // 折算货损金额：延误既有货损率 + 环境风险对应附加损失率
        double delayLossRate = cargo == null ? 0 : cargo.damageRate(delay);
        double envLossRate = risk / 100.0 * ENV_LOSS_RATE_AT_100;
        double totalLossRate = Math.min(0.6, delayLossRate + envLossRate);
        double lossYuan = cargo == null ? 0 : cargo.cargoValueYuan() * totalLossRate;

        return level(score, lossYuan, factors, cargo, cold, delay);
    }

    private static Result level(int score, double lossYuan, List<String> factors,
                                CargoType cargo, boolean cold, double delay) {
        String name = cargo == null ? "货物" : cargo.name();
        String color;
        String level;
        String advice;
        if (score >= 75) {
            level = "极高";
            color = "red";
            advice = cold ? "货损极高，建议优先安排绕行、立即检查制冷设备" : "货损极高，建议优先绕行减少延误";
        } else if (score >= 50) {
            level = "高";
            color = "orange";
            advice = "货损风险高，建议优先安排绕行、减少延误";
        } else if (score >= 30) {
            level = "中";
            color = "yellow";
            advice = cold ? "注意温控，尽量缩短在途延误" : "注意防潮/防晒，合理安排时效";
        } else {
            level = "低";
            color = "green";
            advice = factors.isEmpty() ? "货物在途安全" : "轻微影响，正常运输即可";
        }
        return new Result(score, level, color, advice, factors, name, cold, delay, lossYuan);
    }
}
