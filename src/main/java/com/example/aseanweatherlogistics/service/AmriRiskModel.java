package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMRI —— ASEAN Meteorological Route Index（东盟气象·路网风险指数）。
 *
 * <p>把「多要素实时气象 → 单一可解释风险指数」显式建模，替代散落在算路里的裸阈值判断，
 * 让调度员/评委能看懂「这条路的红/黄是从哪些气象量、按什么公式、怎么合成出来的」。
 *
 * <p>模型形态（三段式，全部公开可核验）：
 * <ol>
 *   <li><b>隶属度（membership）</b>：每个灾种用一个 S 型（logistic）隶属函数把物理量映射到 0~1 的
 *       「超限程度」，参数只有两个——中心点 {@code x0}（风险半程点）与陡峭度 {@code k}：
 *       {@code m = 1 / (1 + e^(-k·(x - x0))}（能见度是「越小越险」，故取 {@code x0 - x}）。
 *       用 S 型而非阶跃，是为了让风险随降雨/风速<b>连续</b>爬升，消除「49.9mm 绿、50.0mm 红」的悬崖。</li>
 *   <li><b>加权概率或融合</b>：多灾种并存时按「至少一种成灾」的概率合成，而非取最大值——
 *       {@code index = 100 · [1 - Π(1 - wᵢ·mᵢ)]}。权重 {@code wᵢ} 体现各灾种对本走廊的相对影响
 *       （本区以强降雨致涝/致滑为主，故降雨权重最高）。相比 {@code max}，融合能刻画「雨大 + 风高 + 雾浓」
 *       叠加时风险高于任一单项的真实语义。</li>
 *   <li><b>严重度分档</b>：连续 index 按标定阈值切成 LOW/MEDIUM/HIGH/CRITICAL，档位边界与算路侧的
 *       运营阈值（暴雨 50mm、大风 60km/h、大雾 100m 等）对齐，保证「模型口径」与「算路口径」一致。</li>
 * </ol>
 *
 * <p>标定说明：各 {@code x0}/{@code k}/{@code w} 依据业务经验阈值反推（使 index 在旧硬阈值处恰好落入对应
 * 严重度档），非黑箱拟合；所有参数经 {@link #describe()} 原样公开，界面据此绘制标定曲线。
 *
 * <p>定位：AMRI 是「气象 → 风险」的可解释模型化表达，用于风险面板、标定曲线、短临预报(nowcasting)与简报；
 * 算路侧的动态边权沿用其离散运营档位（见 {@code RouteService.buildRealRisk}），二者共享同一套阈值口径。
 */
public final class AmriRiskModel {

    private AmriRiskModel() {
    }

    /** 模型名与一句话定义（供界面标题）。 */
    public static final String NAME = "AMRI";
    public static final String FULL_NAME = "东盟气象·路网风险指数 (ASEAN Meteorological Route Index)";
    public static final String FORMULA = "index = 100 · [1 − Π(1 − wᵢ·mᵢ)]，mᵢ = 1/(1+e^(−kᵢ·(xᵢ−x0ᵢ)))";

    /** 单个灾种的隶属函数参数与权重。direction=up 表示值越大越险，down 反之（能见度）。 */
    public record Hazard(String id, String label, String unit, double x0, double k, double weight, String direction) {
        /** 物理量 → 0~1 超限隶属度。缺测（非有限值）按 0（无风险）处理，绝不因缺数据误判。 */
        public double membership(double value) {
            if (!Double.isFinite(value)) {
                return 0.0;
            }
            double z = "down".equals(direction) ? k * (x0 - value) : k * (value - x0);
            return 1.0 / (1.0 + Math.exp(-z));
        }
    }

    /** 灾种参数表（标定值）。x0=风险半程点，k=陡峭度，weight=融合权重。 */
    public static final List<Hazard> HAZARDS = List.of(
            new Hazard("rain", "降雨", "mm/h", 32.0, 0.15, 1.00, "up"),
            new Hazard("wind", "风速", "km/h", 50.0, 0.10, 0.70, "up"),
            new Hazard("vis", "能见度", "m", 300.0, 0.010, 0.60, "down"),
            new Hazard("temp", "气温", "°C", 36.0, 0.45, 0.25, "up")
    );

    /** 严重度分档阈值（连续 index → 档位），与算路运营阈值对齐。 */
    public static final double BAND_CRITICAL = 80.0;
    public static final double BAND_HIGH = 45.0;
    public static final double BAND_MEDIUM = 18.0;

    /** 一次评估的结果：连续指数 + 档位 + 各灾种贡献明细。 */
    public record Result(double index, String severity, List<Map<String, Object>> contributions) {
    }

    /** 取一个灾种参数。 */
    private static Hazard hazard(String id) {
        for (Hazard h : HAZARDS) {
            if (h.id().equals(id)) {
                return h;
            }
        }
        throw new IllegalArgumentException("unknown hazard: " + id);
    }

    /**
     * 评估综合风险指数。入参为物理量，缺测传 {@code Double.NaN}（按无风险处理）。
     *
     * @param rainMmH  降水强度 mm/h
     * @param windKph  风速 km/h
     * @param visM     能见度 m
     * @param tempC    气温 °C
     */
    public static Result evaluate(double rainMmH, double windKph, double visM, double tempC) {
        double keep = 1.0; // Π(1 - wᵢ·mᵢ)
        List<Map<String, Object>> contrib = new ArrayList<>();
        double[] values = {rainMmH, windKph, visM, tempC};
        for (int i = 0; i < HAZARDS.size(); i++) {
            Hazard h = HAZARDS.get(i);
            double m = h.membership(values[i]);
            double part = h.weight() * m;
            keep *= (1.0 - part);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", h.id());
            c.put("label", h.label());
            c.put("unit", h.unit());
            c.put("value", finiteOrNull(values[i]));
            c.put("membership", round(m, 3));
            c.put("weight", h.weight());
            c.put("impact", round(part * 100, 1)); // 该灾种对满量程的贡献上限
            contrib.add(c);
        }
        double index = clamp(100.0 * (1.0 - keep), 0.0, 100.0);
        return new Result(round(index, 1), severity(index), contrib);
    }

    /** 连续指数 → 严重度档位。 */
    public static String severity(double index) {
        if (index >= BAND_CRITICAL) {
            return "CRITICAL";
        }
        if (index >= BAND_HIGH) {
            return "HIGH";
        }
        if (index >= BAND_MEDIUM) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** 单灾种物理量 → 该灾种隶属度对应的「单项指数」（其余灾种视为正常），供标定曲线绘制。 */
    public static double singleHazardIndex(String hazardId, double value) {
        Hazard h = hazard(hazardId);
        return round(100.0 * h.weight() * h.membership(value), 1);
    }

    /**
     * 标定曲线：某灾种在给定取值序列上的单项风险指数（其余灾种正常），用于界面画「物理量—风险」响应曲线。
     *
     * @param hazardId 灾种 id（rain/wind/vis/temp）
     * @param from     起始值
     * @param to       结束值
     * @param steps    采样点数
     */
    public static List<double[]> calibrationCurve(String hazardId, double from, double to, int steps) {
        List<double[]> pts = new ArrayList<>();
        if (steps < 2) {
            steps = 2;
        }
        double d = (to - from) / (steps - 1);
        for (int i = 0; i < steps; i++) {
            double x = from + d * i;
            pts.add(new double[]{round(x, 2), singleHazardIndex(hazardId, x)});
        }
        return pts;
    }

    /** 完整模型规格（供 /api/risk/model 下发界面：名称、公式、参数表、分档、标定曲线）。 */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", NAME);
        m.put("fullName", FULL_NAME);
        m.put("formula", FORMULA);
        m.put("fusion", "加权概率或：index = 100·[1−Π(1−wᵢ·mᵢ)]（刻画多灾种叠加，优于取最大值）");
        m.put("membership", "logistic S 型隶属度：m = 1/(1+e^(−k·(x−x0)))；能见度取 (x0−x) 反向");
        m.put("calibration", "x0/k/w 依据业务经验阈值反推标定，使 index 在旧硬阈值处落入对应严重度档；非黑箱拟合");
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Hazard h : HAZARDS) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", h.id());
            one.put("label", h.label());
            one.put("unit", h.unit());
            one.put("x0", h.x0());
            one.put("k", h.k());
            one.put("weight", h.weight());
            one.put("direction", h.direction());
            hs.add(one);
        }
        m.put("hazards", hs);
        Map<String, Object> bands = new LinkedHashMap<>();
        bands.put("CRITICAL", BAND_CRITICAL);
        bands.put("HIGH", BAND_HIGH);
        bands.put("MEDIUM", BAND_MEDIUM);
        bands.put("LOW", 0);
        m.put("bands", bands);
        // 标定曲线（降雨为主曲线 + 各灾种响应），界面直接绘制，无需前端重算公式
        Map<String, Object> curves = new LinkedHashMap<>();
        curves.put("rain", calibrationCurve("rain", 0, 80, 33));
        curves.put("wind", calibrationCurve("wind", 0, 90, 31));
        curves.put("vis", calibrationCurve("vis", 0, 1000, 26));
        curves.put("temp", calibrationCurve("temp", 15, 45, 31));
        m.put("curves", curves);
        return m;
    }

    private static Object finiteOrNull(double v) {
        return Double.isFinite(v) ? round(v, 1) : null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
