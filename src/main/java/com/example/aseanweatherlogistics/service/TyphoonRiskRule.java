package com.example.aseanweatherlogistics.service;

/**
 * 台风 / 强对流判据（集中一处，供"真实气象"与"沙盘模拟"共用）。
 * <p>
 * 为什么抽出来：台风告警有两条输入路径——
 * <ul>
 *   <li>真实：{@code RouteService.buildRealRisk} 从 CRA40 实况的 PRS 气压 + WIN 风速判定；</li>
 *   <li>模拟：决策沙盘手动给某断面设置气压/风速（演示台风场景）。</li>
 * </ul>
 * 两条路径必须得出同一个结论，否则现场会出现"模拟能熔断、真实数据同样量级却不熔断"
 * 的口径打架。所以阈值与公式只在这里定义一次。
 * <p>
 * 判据要点：气压 + 风速必须<b>同时</b>成立。
 * 只看风速会把雷暴阵风误判成台风；只看气压会在山区/高原（气压天然偏低）误报。
 */
public final class TyphoonRiskRule {

    private TyphoonRiskRule() {
    }

    /** 台风级：海压 ≤990hPa（台风中心/外围典型值）且风速 ≥60km/h（约 8 级） */
    public static final double TYPHOON_PRS_HPA = 990.0;
    public static final double TYPHOON_WIND_KPH = 60.0;
    /** 强对流级：海压 ≤1000hPa（低压槽/台风外围）且风速 ≥40km/h（约 6 级） */
    public static final double CONVECTIVE_PRS_HPA = 1000.0;
    public static final double GALE_WIND_KPH = 40.0;

    /** 台风级惩罚：100 = 等效道路中断（与暴雨熔断同一档） */
    public static final double TYPHOON_PENALTY = 100.0;
    /** 强对流级惩罚：高风险但可通行 */
    public static final double CONVECTIVE_PENALTY = 50.0;

    /**
     * 判定结果。{@code penalty<=0} 表示未触发任何台风/强对流判据。
     */
    public record Verdict(double penalty, String reason) {
        public boolean triggered() {
            return penalty > 0;
        }

        /** 是否达到"等效中断"级别（penalty=100，与熔断同档） */
        public boolean blocking() {
            return penalty >= TYPHOON_PENALTY;
        }
    }

    /** 未触发判据（气压/风速正常） */
    private static final Verdict NONE = new Verdict(0, null);

    /**
     * 按气压与风速判定台风 / 强对流。
     * 压力或风速缺失（NaN）时按"未触发"处理，绝不因为缺数据误熔断。
     */
    public static Verdict evaluate(double pressureHpa, double windKph) {
        if (!Double.isFinite(pressureHpa) || !Double.isFinite(windKph)) {
            return NONE;
        }
        if (pressureHpa <= TYPHOON_PRS_HPA && windKph >= TYPHOON_WIND_KPH) {
            return new Verdict(TYPHOON_PENALTY,
                    String.format("台风(气压%.0fhPa+风速%.0fkm/h)", pressureHpa, windKph));
        }
        if (pressureHpa <= CONVECTIVE_PRS_HPA && windKph >= GALE_WIND_KPH) {
            return new Verdict(CONVECTIVE_PENALTY,
                    String.format("强对流(气压%.0fhPa+风速%.0fkm/h)", pressureHpa, windKph));
        }
        return NONE;
    }

    /** 是否达到熔断级别（供沙盘判定"该断面是否应熔断"） */
    public static boolean isBlocking(double pressureHpa, double windKph) {
        return evaluate(pressureHpa, windKph).blocking();
    }
}
