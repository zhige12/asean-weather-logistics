package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 多维指标计算器：把一条路线方案折算成可横向对比的六维指标。
 * 耗时 / 风险 / 货损 / 油耗 / 综合费用 / 碳排放。
 * <p>
 * 全部为确定性规则计算（AI 只做分析与展示，数字可信、可复现、拔网线可用）：
 * <ul>
 *   <li>油耗 = 距离 × 百公里油耗（按车型/重量）</li>
 *   <li>碳排放 = 油耗 × 柴油碳排放因子（水运段按更低系数）</li>
 *   <li>货损 = 延误 × 货物延误敏感度模型（{@link DemoConfigService.CargoType}）</li>
 * </ul>
 */
@Service
public class CostCalculator {

    /** 18 吨冷藏半挂百公里油耗（L/100km） */
    public static final double TRUCK_L_PER_100KM = 32.0;
    /** 柴油碳排放因子（kg CO₂ / L） */
    public static final double DIESEL_KG_CO2_PER_L = 2.68;
    /** 水运段碳排放相对公路的折减系数（水运更低碳） */
    public static final double WATER_CARBON_FACTOR = 0.35;
    /** 油价（元/L，演示标定） */
    public static final double DIESEL_PRICE_YUAN = 7.5;

    /** 六维指标（不可变，直接序列化给前端对比表）。 */
    public record RouteMetrics(
            double extraHours,      // 额外耗时（h，相对基准）
            double totalHours,      // 总耗时（h）
            String risk,            // 风险等级 高/中/低
            double cargoLossPct,    // 货损率（%）
            double cargoLossYuan,   // 货损金额（元）
            double fuelL,           // 油耗（L，相对基准的增量）
            double costYuan,        // 综合费用（元，相对基准增量，可为负=节省）
            double carbonKg,        // 碳排放（kg，相对基准增量）
            String carbonLevel,     // 碳排放等级 高/中/低（展示用）
            double absFuelL,        // 全程油耗（L，绝对值：完成整趟运输实际消耗）
            double absCostYuan,     // 全程综合费用（元，绝对值）
            double absCarbonKg      // 全程碳排放（kg，绝对值）
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("extraHours", extraHours);
            m.put("totalHours", totalHours);
            m.put("risk", risk);
            m.put("cargoLossPct", cargoLossPct);
            m.put("cargoLossYuan", cargoLossYuan);
            m.put("fuelL", fuelL);
            m.put("costYuan", costYuan);
            m.put("carbonKg", carbonKg);
            m.put("carbonLevel", carbonLevel);
            m.put("absFuelL", absFuelL);
            m.put("absCostYuan", absCostYuan);
            m.put("absCarbonKg", absCarbonKg);
            return m;
        }
    }

    /** 按里程折算油耗（L）。 */
    public double fuelLiters(double km) {
        return km * TRUCK_L_PER_100KM / 100.0;
    }

    /** 按里程折算公路碳排放（kg）。 */
    public double carbonKgForRoad(double km) {
        return fuelLiters(km) * DIESEL_KG_CO2_PER_L;
    }

    /** 按里程折算水运碳排放（kg）。18吨货船近洋+内河 ≈0.74 kg/km，远低于公路 8.576 kg/km。 */
    public double carbonKgForWater(double km) {
        return km * 2.1 * WATER_CARBON_FACTOR;
    }

    /** 每公里公路碳排放（kg/km），供增量计算。 */
    public double roadCarbonPerKm() {
        return TRUCK_L_PER_100KM / 100.0 * DIESEL_KG_CO2_PER_L;
    }

    /** 碳排放等级（展示用，按增量里程对应的典型区间标定）。 */
    public String carbonLevel(double carbonKg) {
        if (carbonKg >= 60) return "高";
        if (carbonKg >= 25) return "中";
        return "低";
    }

    /**
     * 组装一个方案的六维指标。
     *
     * @param extraHours    额外耗时（h）
     * @param totalHours    总耗时（h）
     * @param risk          风险等级
     * @param cargo         货物画像
     * @param delayHours    用于货损计算的延误时长（h）
     * @param fuelL         油耗增量（L）
     * @param costYuan      综合费用增量（元，负=节省）
     * @param carbonKg      碳排放增量（kg）
     * @param absFuelL      全程油耗绝对值（L）
     * @param absCostYuan   全程综合费用绝对值（元）
     * @param absCarbonKg   全程碳排放绝对值（kg）
     */
    public RouteMetrics build(double extraHours, double totalHours, String risk,
                              DemoConfigService.CargoType cargo, double delayHours,
                              double fuelL, double costYuan, double carbonKg,
                              double absFuelL, double absCostYuan, double absCarbonKg) {
        double lossPct = round1(cargo.damageRate(delayHours) * 100);
        double lossYuan = round0(cargo.damageLossYuan(delayHours));
        return new RouteMetrics(
                round1(extraHours), round1(totalHours), risk,
                lossPct, lossYuan,
                round1(fuelL), round0(costYuan), round1(carbonKg),
                carbonLevel(carbonKg),
                round1(absFuelL), round0(absCostYuan), round1(absCarbonKg));
    }

    /** 全程碳排放等级（展示用，按整趟运输的绝对排放量标定）。 */
    public String absCarbonLevel(double absCarbonKg) {
        if (absCarbonKg >= 450) return "高";
        if (absCarbonKg >= 300) return "中";
        return "低";
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round0(double v) {
        return Math.round(v);
    }
}
