package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天气趋势预警（"提前 1-2 小时看天预判转雨"，纯函数、无状态）。
 * <p>
 * 现有沙盘都是"雨已经下大才熔断"的<b>事后</b>判定；而降雨前通常有可观测的前兆组合：
 * <b>气压下降 + 湿度上升 + 云量增多</b>。本引擎把这三项作为转雨前兆打分，
 * 命中两项及以上即给出"未来 1-2 小时可能转雨、建议提前调度"的<b>事前</b>预警，
 * 让决策从"被动熔断"前移到"主动预防"。
 * <p>
 * 两种输入：① 只有当前值（沙盘滑杆）——按绝对阈值判前兆；② 已知基线值（实况时序）——
 * 用相对下降/上升幅度判"趋势"，更准。缺要素（NaN）不计入命中，绝不因缺数据误报。
 */
public final class WeatherTrendRule {

    private WeatherTrendRule() {
    }

    /** 气压前兆阈值（hPa）：低于此值视为低压系统逼近 */
    public static final double PRS_LOW_HPA = 1005.0;
    /** 气压下降幅度前兆（hPa）：相对基线下降超过此值视为趋势性降压 */
    public static final double PRS_DROP_HPA = 3.0;
    /** 湿度前兆阈值（%） */
    public static final double RH_HIGH_PCT = 80.0;
    /** 云量前兆阈值（%，总云量 TCC） */
    public static final double TCC_HIGH_PCT = 70.0;

    /**
     * @param triggered 是否给出转雨预警
     * @param hits      命中的前兆项数（0-3）
     * @param leadHours 建议提前量（小时）：命中越多越逼近，2 项≈2h、3 项≈1h
     * @param text      预警文案（供智能体/界面直接引用）
     * @param factors   命中前兆明细
     */
    public record Result(boolean triggered, int hits, int leadHours, String text, List<String> factors) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("triggered", triggered);
            m.put("hits", hits);
            m.put("leadHours", leadHours);
            m.put("text", text);
            m.put("factors", factors);
            return m;
        }
    }

    private static boolean ok(double v) {
        return Double.isFinite(v);
    }

    /** 仅当前值版（沙盘滑杆）：按绝对阈值判前兆。 */
    public static Result evaluate(double pressureHpa, double humidityPct, double cloudPct) {
        return evaluate(pressureHpa, humidityPct, cloudPct, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * 带基线版（实况时序）：传基线值则用"趋势幅度"判前兆，更贴近"持续下降/上升"的原意。
     * 基线传 NaN 表示未知，退回绝对阈值判定。
     *
     * @param basePressureHpa 基线气压（更早时刻），气压下降 = base - now
     * @param baseHumidityPct 基线湿度，湿度上升 = now - base
     * @param baseCloudPct    基线云量，云量增加 = now - base
     */
    public static Result evaluate(double pressureHpa, double humidityPct, double cloudPct,
                                  double basePressureHpa, double baseHumidityPct, double baseCloudPct) {
        List<String> factors = new ArrayList<>();
        int hits = 0;

        // 气压：有基线看下降幅度，否则看绝对低压
        if (ok(pressureHpa)) {
            if (ok(basePressureHpa) && (basePressureHpa - pressureHpa) >= PRS_DROP_HPA) {
                hits++;
                factors.add(String.format("气压持续下降（%.0f→%.0fhPa，降 %.0fhPa）",
                        basePressureHpa, pressureHpa, basePressureHpa - pressureHpa));
            } else if (!ok(basePressureHpa) && pressureHpa <= PRS_LOW_HPA) {
                hits++;
                factors.add(String.format("气压偏低 %.0fhPa（≤%.0fhPa 低压系统）", pressureHpa, PRS_LOW_HPA));
            }
        }

        // 湿度：有基线看上升，否则看绝对高湿
        if (ok(humidityPct)) {
            if (ok(baseHumidityPct) && (humidityPct - baseHumidityPct) >= 8.0) {
                hits++;
                factors.add(String.format("湿度上升（%.0f%%→%.0f%%）", baseHumidityPct, humidityPct));
            } else if (humidityPct >= RH_HIGH_PCT) {
                hits++;
                factors.add(String.format("湿度偏高 %.0f%%（≥%.0f%%）", humidityPct, RH_HIGH_PCT));
            }
        }

        // 云量：有基线看增加，否则看绝对高云量
        if (ok(cloudPct)) {
            if (ok(baseCloudPct) && (cloudPct - baseCloudPct) >= 20.0) {
                hits++;
                factors.add(String.format("云量增多（%.0f%%→%.0f%%）", baseCloudPct, cloudPct));
            } else if (cloudPct >= TCC_HIGH_PCT) {
                hits++;
                factors.add(String.format("云量偏多 %.0f%%（≥%.0f%%）", cloudPct, TCC_HIGH_PCT));
            }
        }

        boolean triggered = hits >= 2;
        int leadHours = hits >= 3 ? 1 : 2;
        String text = triggered
                ? String.format("未来约 %d 小时有转雨/强降雨可能（%s），建议提前调度、预留绕行",
                        leadHours, String.join("、", factors))
                : null;
        return new Result(triggered, hits, leadHours, text, factors);
    }
}
