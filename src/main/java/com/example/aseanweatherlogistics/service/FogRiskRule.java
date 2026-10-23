package com.example.aseanweatherlogistics.service;

/**
 * 浓雾 / 低能见度判据（集中一处，供"真实气象"与"沙盘模拟"共用），与 {@link TyphoonRiskRule} 对称。
 * <p>
 * 为什么补这条：公路侧此前只看「降雨 + 台风」两个硬判据，完全不看能见度；而水运侧却用
 * "能见度&lt;1000m"硬禁航——同一个系统里两条线的"能见度红线"口径不对称。现实中友谊关 / 芒街
 * 一带秋冬多浓雾，高速因团雾封路、多车追尾是真实高发的重大风险，危险度不低于暴雨，故补一条
 * "浓雾封路"硬熔断，与降雨 / 台风并列。
 * <p>
 * 判据不能只看能见度：暴雨雨幕、霾、沙尘都会压低能见度，一律按雾熔断会误判。雾的气象本质是
 * "低能见度 + 高湿 / 低云 + 静稳（风小，雾未被吹散）"，因此四要素<b>联动</b>：
 * <ul>
 *   <li>能见度：主判据，分级对齐中国大雾预警与高速封路常识
 *       （&lt;1000 大雾 / &lt;500 浓雾 / &lt;200 强浓雾=封路线 / &lt;50 特强浓雾）；</li>
 *   <li>相对湿度 / 总云量：确认"是雾"（RH≥90% 或 云量≥80%），干燥的低能见度不当雾封；</li>
 *   <li>风速：雾发生在静稳天气，风&gt;35km/h 会吹散雾，此时低能见度不归因于雾（交由降雨 / 台风判据）。</li>
 * </ul>
 * 安全侧兜底：能见度&lt;50m 属特强浓雾，无论成因与湿度一律熔断（任何成因的&lt;50m 都不可行车）。
 * 缺要素（NaN）一律不误熔断。
 */
public final class FogRiskRule {

    private FogRiskRule() {
    }

    /** 能见度分级（米）：对齐中国大雾预警——<1000 大雾 / <500 浓雾 / <200 强浓雾(封路线) / <50 特强浓雾 */
    public static final double VIS_DENSE_M = 1000.0;
    public static final double VIS_THICK_M = 500.0;
    public static final double VIS_STRONG_M = 200.0;
    public static final double VIS_EXTREME_M = 50.0;
    /** 雾的湿度 / 云量特征：RH≥90% 或 总云量≥80% 才认定为雾（而非霾 / 雨幕 / 沙尘） */
    public static final double FOG_HUMIDITY_PCT = 90.0;
    public static final double FOG_CLOUD_PCT = 80.0;
    /** 静稳上限：风速>35km/h 会吹散雾，此时低能见度不按"雾"熔断 */
    public static final double FOG_CALM_WIND_KPH = 35.0;

    /** 熔断级惩罚：100 = 等效道路中断（与暴雨 / 台风熔断同一档） */
    public static final double FOG_BLOCK_PENALTY = 100.0;
    /** 浓雾：高风险限速，不熔断 */
    public static final double THICK_PENALTY = 50.0;
    /** 大雾：提示级 */
    public static final double DENSE_PENALTY = 20.0;

    /**
     * 判定结果。{@code penalty<=0} 表示未触发任何浓雾判据。
     */
    public record Verdict(double penalty, String reason) {
        public boolean triggered() {
            return penalty > 0;
        }

        /** 是否达到"等效中断"级别（penalty=100，与熔断同档） */
        public boolean blocking() {
            return penalty >= FOG_BLOCK_PENALTY;
        }
    }

    /** 未触发判据（能见度正常） */
    private static final Verdict NONE = new Verdict(0, null);

    /**
     * 按能见度 + 湿度 + 云量 + 风速联动判定浓雾封路。
     * 能见度缺失（NaN）时按"未触发"处理，绝不因为缺数据误熔断。
     */
    public static Verdict evaluate(double visibilityM, double humidityPct, double cloudPct, double windKph) {
        if (!Double.isFinite(visibilityM)) {
            return NONE;
        }
        boolean foggy = (Double.isFinite(humidityPct) && humidityPct >= FOG_HUMIDITY_PCT)
                || (Double.isFinite(cloudPct) && cloudPct >= FOG_CLOUD_PCT);
        // 风缺失时不因风否定雾；有风且超静稳上限则不认定为"雾封路"
        boolean calm = !Double.isFinite(windKph) || windKph <= FOG_CALM_WIND_KPH;
        // 特强浓雾：任何成因 <50m 都不可行车，无条件熔断
        if (visibilityM < VIS_EXTREME_M) {
            return new Verdict(FOG_BLOCK_PENALTY,
                    "特强浓雾(能见度" + Math.round(visibilityM) + "m)");
        }
        // 强浓雾封路：需具备雾特征且静稳
        if (visibilityM < VIS_STRONG_M && foggy && calm) {
            return new Verdict(FOG_BLOCK_PENALTY,
                    "浓雾封路(能见度" + Math.round(visibilityM) + "m"
                            + (Double.isFinite(humidityPct) ? "·湿度" + Math.round(humidityPct) + "%" : "")
                            + (Double.isFinite(windKph) ? "·风" + Math.round(windKph) + "km/h" : "") + ")");
        }
        // 浓雾：高风险限速（不熔断）
        if (visibilityM < VIS_THICK_M && foggy) {
            return new Verdict(THICK_PENALTY, "浓雾(能见度" + Math.round(visibilityM) + "m)");
        }
        // 大雾：提示
        if (visibilityM < VIS_DENSE_M && foggy) {
            return new Verdict(DENSE_PENALTY, "大雾(能见度" + Math.round(visibilityM) + "m)");
        }
        return NONE;
    }

    /** 是否达到熔断级别（供沙盘判定"该断面是否应因雾熔断"） */
    public static boolean isBlocking(double visibilityM, double humidityPct, double cloudPct, double windKph) {
        return evaluate(visibilityM, humidityPct, cloudPct, windKph).blocking();
    }
}
