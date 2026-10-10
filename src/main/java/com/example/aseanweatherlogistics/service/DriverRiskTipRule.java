package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 司机端个性化风险提示（触达侧"按车型/货型分流"判据，纯函数、无状态）。
 * <p>
 * 为什么要它：同一份气象研判，冷链车、高栏车、普通货车该收到的提示并不一样——
 * 冷链车怕的是高温顶不动制冷、高栏/厢式车怕侧风失稳、普通货车怕路面湿滑水滑，
 * 浓雾和结冰则是所有车通用的红线。把"哪个要素命中→哪类车该做什么"集中在这里，
 * {@link TouchAgent} 只负责把结果拼进指令，判据阈值全部复用
 * {@link RoadRiskIndex} / {@link CargoSafetyIndex} 的常量，保证与研判、沙盘同一口径。
 * <p>
 * 缺要素（NaN）不产提示；提示按危害等级从高到低排列（熔断 &gt; 结冰 &gt; 能见度
 * &gt; 侧风 &gt; 冷链高温 &gt; 湿滑），司机一眼看到最要命的那条。
 */
public final class DriverRiskTipRule {

    private DriverRiskTipRule() {
    }

    /**
     * @param tag     风险类别键（fuse/ice/vis/wind/cold/wet/mold），供前端配色/图标
     * @param vehicle 针对的车型/货型（冷链车 / 高栏·厢式车 / 普通货车 / 全体司机）
     * @param level   危害等级（极高/高/中），沿用评分口径
     * @param text    给司机的可执行提示（一句话，执行指令口吻）
     */
    public record Tip(String tag, String vehicle, String level, String text) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", tag);
            m.put("vehicle", vehicle);
            m.put("level", level);
            m.put("text", text);
            return m;
        }
    }

    private static boolean ok(double v) {
        return Double.isFinite(v);
    }

    /**
     * @param rainMm      未来6h累计降雨（mm）
     * @param tempC       气温（℃）
     * @param windKph     风速（km/h）
     * @param visibilityM 能见度（米）
     * @param humidityPct 相对湿度（%）
     * @param coldChain   是否冷链货（决定"冷链车"高温/霉变提示是否出现）
     * @param roadBlocked 公路是否已熔断（命中则置顶一条禁行指令）
     */
    public static List<Tip> evaluate(double rainMm, double tempC, double windKph,
                                     double visibilityM, double humidityPct,
                                     boolean coldChain, boolean roadBlocked) {
        List<Tip> tips = new ArrayList<>();

        // 熔断禁行：最高优先级，覆盖其余一切——车不能再往前开
        if (roadBlocked) {
            tips.add(new Tip("fuse", "全体司机", "极高",
                    "前方路段已熔断禁行，请勿驶入，按调度指令绕行或在安全区待命"));
        }

        // 结冰/暗冰：低温 + 路面有水，所有车通用红线
        boolean wet = (ok(rainMm) && rainMm >= RoadRiskIndex.RAIN_LIGHT_MM)
                || (ok(humidityPct) && humidityPct >= RoadRiskIndex.ICE_HUMIDITY_PCT);
        if (ok(tempC) && tempC < RoadRiskIndex.ICE_TEMP_C && wet) {
            tips.add(new Tip("ice", "全体司机", "高",
                    String.format("气温 %.1f℃ 且有潮湿，警惕结冰/暗冰：高架与背阴路段减速慢行，避免急刹急转", tempC)));
        }

        // 低能见度：所有车通用
        if (ok(visibilityM) && visibilityM < RoadRiskIndex.VIS_FOG_M) {
            tips.add(new Tip("vis", "全体司机",
                    visibilityM < RoadRiskIndex.VIS_DENSE_M ? "高" : "中",
                    String.format("能见度约 %.0fm，开启雾灯/近光、拉大跟车距离，看不清即刻降速", visibilityM)));
        }

        // 侧风：高栏/厢式车最怕，达强风档即提醒
        if (ok(windKph) && windKph >= RoadRiskIndex.WIND_STRONG_KPH) {
            tips.add(new Tip("wind", "高栏·厢式车",
                    windKph >= RoadRiskIndex.WIND_EXTREME_KPH ? "高" : "中",
                    String.format("侧风预警（风速约 %.0fkm/h）：高栏/厢式车减速慢行、握稳方向，横风路段与桥梁接缝处尤其当心",
                            windKph)));
        }

        // 冷链高温：只有冷链货才推，指向制冷设备与缩短延误
        if (coldChain && ok(tempC) && tempC > CargoSafetyIndex.COLD_HEAT_C) {
            tips.add(new Tip("cold", "冷链车",
                    tempC >= 35 ? "高" : "中",
                    String.format("高温预警（约 %.1f℃）：出车前检查制冷设备、途中监控厢温，优先绕行减少在途延误", tempC)));
        }

        // 冷链高湿霉变：生鲜/冷链专属
        if (coldChain && ok(humidityPct) && humidityPct > CargoSafetyIndex.HUMID_MOLD_PCT) {
            tips.add(new Tip("mold", "冷链车", "中",
                    String.format("湿度约 %.0f%%，货厢霉变风险：检查密封与除湿，尽量缩短开箱时间", humidityPct)));
        }

        // 路面湿滑：普通货车的通用提醒，中雨及以上才值得单独说
        if (ok(rainMm) && rainMm >= RoadRiskIndex.RAIN_MODERATE_MM) {
            tips.add(new Tip("wet", "普通货车",
                    rainMm >= RoadRiskIndex.RAIN_HEAVY_MM ? "高" : "中",
                    String.format("路面湿滑（降雨约 %.0fmm）：保持车距、降低车速，防止水滑与侧滑", rainMm)));
        }

        return tips;
    }

    /** 取列表首条（最要命的一条）作为一句话摘要；无提示返回 null。 */
    public static String headline(List<Tip> tips) {
        return tips == null || tips.isEmpty() ? null : tips.get(0).text();
    }
}
