package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 平陆运河支流风险差异化预测（演示第九幕）：
 * 每条支流有独立的特征向量（河床质 / 历史淤积比 / 入汇形态 / 横流流速），
 * 同样的降雨量在不同支流输出不同的风险类型与概率——
 * 「不是用一把尺子量所有支流，输出的是概率，不是简单的熔断/不熔断」。
 * <p>
 * 数据诚实性声明：支流档案（名称/特征向量/基线概率）是演示剧本标定值，
 * 坐标沿钦江—平陆运河一带大致布置的示意位置；但 {@link #liveAssessment()}
 * 的降雨输入是真实气象（按当前数据源：比赛官方 CRA40 / Open-Meteo 实况），
 * 公式把「实时降雨强度 × 6h 窗口」等效为过程降雨量后进概率模型。
 * <p>
 * 模型：P(rain) = base × (0.55 + rain/100)，45mm 时系数为 1.0（即 base 即剧本标称概率），
 * 截断到 [5%, 97%]。base 来自各支流特征向量的专家标定。
 * <p>
 * {@link #mainlineImpact} 把差异化概率聚合为干流顶托流速，供调度大屏支流风险卡
 * 真正参与禁航判定（见 WaterRiskEngine / DecisionSandboxService#syncTributaryRisk）。
 */
@Service
public class TributaryRiskService {

    /** 支流档案：特征向量 + 标定基线概率（45mm 降雨下）+ 支流口示意坐标（按当前气象源拉实时降雨用） */
    public record Tributary(String id, String name, String dominantRisk,
                            Map<String, String> features, double baseProbPct,
                            double lat, double lng) {
    }

    /** 虚拟气象节点 id 前缀：与公路网真实节点区分，不污染路网天气缓存语义 */
    private static final String NODE_PREFIX = "TRIB_";

    public static final List<Tributary> TRIBUTARIES = List.of(
            new Tributary("JIUZHOU", "旧州江支流口", "淤积风险",
                    Map.of("河床质", "沙层河床", "历史淤积比", "84%", "入汇形态", "顺直入汇", "含沙量", "高"),
                    72.0, 21.98, 108.55),
            new Tributary("XINPING", "新坪水支流口", "淤积风险",
                    Map.of("河床质", "沙层河床", "入汇形态", "弯顶入汇", "含沙量", "降雨后显著增大", "历史淤积比", "61%"),
                    58.0, 22.12, 108.08),
            new Tributary("SHAPING", "沙坪河支流口", "洪水冲击风险",
                    Map.of("河床质", "岩质河床", "入汇角", "大（近直角入汇）", "历史淤积比", "12%", "含沙量", "低"),
                    35.0, 22.40, 107.42),
            new Tributary("LAOCUN", "老村河支流口", "横流风险",
                    Map.of("河床质", "砂卵石河床", "横流流速", "降雨后可能超标", "航道宽度", "窄", "弯曲半径", "小"),
                    61.0, 22.05, 108.32)
    );

    /** 干流基准流速（m/s）：无支流顶托时的设计流速 */
    private static final double MAINLINE_BASE_MS = 1.0;
    /** 顶托聚合系数：概率加权和每满 1.0 推高干流流速 0.7m/s（45mm 时≈1.85 不触线，≥≈63mm 越线） */
    private static final double MAINLINE_K = 0.70;
    /** 风险类型→干流顶托权重：横流/洪水冲击是硬水力威胁，淤积主要影响河床 */
    private static final Map<String, Double> CONFLUENCE_WEIGHTS =
            Map.of("横流风险", 1.00, "洪水冲击风险", 0.80, "淤积风险", 0.25);

    /** 实况模式的等效换算窗口：实时降雨强度(mm/h) × 6h ≈ 未来6小时过程降雨量（与卡片口径一致） */
    public static final int HORIZON_HOURS = 6;

    private final RealWeatherService realWeatherService;
    private final WeatherSourceService sourceService;

    public TributaryRiskService(RealWeatherService realWeatherService, WeatherSourceService sourceService) {
        this.realWeatherService = realWeatherService;
        this.sourceService = sourceService;
    }

    /** 某支流在 rainfallMm 降雨下的风险概率（与 predict 同一公式，供两处复用）。 */
    private double probAt(Tributary t, double rainfallMm) {
        return clamp(t.baseProbPct() * (0.55 + rainfallMm / 100.0), 5.0, 97.0);
    }

    /** 各支流在未来 6 小时降雨 rainfallMm 下的风险概率预测。 */
    public List<Map<String, Object>> predict(double rainfallMm) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tributary t : TRIBUTARIES) {
            double prob = probAt(t, rainfallMm);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("features", t.features());
            m.put("riskType", t.dominantRisk());
            m.put("probabilityPct", Math.round(prob));
            m.put("level", prob >= 65 ? "高" : (prob >= 45 ? "中" : "低"));
            m.put("rainfallMm", rainfallMm);
            m.put("explanation", explain(t, rainfallMm, prob));
            out.add(m);
        }
        return out;
    }

    private String explain(Tributary t, double rainfallMm, double prob) {
        String featSummary = String.join("、", t.features().values());
        return String.format("降雨量%.0fmm → %s概率 %.0f%%（%s）",
                rainfallMm, t.dominantRisk(), prob, featSummary);
    }

    /**
     * 真实气象驱动的支流风险评估（实况模式核心）：
     * 按当前气象数据源（比赛官方 CRA40 / Open-Meteo 实况，均经 RealWeatherService
     * 的节流+熔断+缓存保护）拉每条支流口坐标的实时降雨强度，
     * 等效为 6h 过程降雨量后进同一套概率模型与顶托聚合。
     * 失败/无数据时逐支流降级（data=false），不抛异常；是否可联动干流看 dataAvailable。
     */
    public Map<String, Object> liveAssessment() {
        List<RoadNode> nodes = new ArrayList<>();
        for (Tributary t : TRIBUTARIES) {
            RoadNode n = new RoadNode();
            n.setId(NODE_PREFIX + t.id());
            n.setName(t.name());
            n.setLatitude(t.lat());
            n.setLongitude(t.lng());
            nodes.add(n);
        }
        List<RealWeatherService.WeatherPoint> points = realWeatherService.fetchForNodes(nodes);
        Map<String, RealWeatherService.WeatherPoint> byId = new HashMap<>();
        for (RealWeatherService.WeatherPoint p : points) {
            byId.put(p.nodeId(), p);
        }
        String source = realWeatherService.lastSource();
        boolean realtime = WeatherSourceService.CONTEST.equals(source) || WeatherSourceService.OPEN_METEO.equals(source);
        double weightedSum = 0;
        double eqSum = 0;
        int eqCount = 0;
        boolean anyData = false;
        long fetchedAt = 0;
        List<String> highRisk = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Tributary t : TRIBUTARIES) {
            RealWeatherService.WeatherPoint p = byId.get(NODE_PREFIX + t.id());
            Double mmH = p == null ? null : Math.max(0.0, p.precipitationMm());
            boolean hasData = mmH != null;
            anyData |= hasData;
            if (hasData) {
                fetchedAt = Math.max(fetchedAt, p.fetchedAt());
            }
            double eqMm = hasData ? clamp(mmH * HORIZON_HOURS, 0, 100) : 0;
            if (hasData) {
                eqSum += eqMm;
                eqCount++;
            }
            double prob = probAt(t, eqMm);
            double w = CONFLUENCE_WEIGHTS.getOrDefault(t.dominantRisk(), 0.50);
            weightedSum += prob / 100.0 * w;
            if (prob >= 65) {
                highRisk.add(t.name() + t.dominantRisk() + " " + Math.round(prob) + "%");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id());
            item.put("name", t.name());
            item.put("features", t.features());
            item.put("riskType", t.dominantRisk());
            item.put("probabilityPct", Math.round(prob));
            item.put("level", prob >= 65 ? "高" : (prob >= 45 ? "中" : "低"));
            item.put("realtimeMmH", hasData ? Math.round(mmH * 10) / 10.0 : null);
            item.put("equivalentMm", hasData ? Math.round(eqMm) : null);
            item.put("data", hasData);
            item.put("explanation", hasData
                    ? String.format("实时雨强 %.1fmm/h × %dh ≈ %.0fmm → %s概率 %.0f%%（%s）",
                            mmH, HORIZON_HOURS, eqMm, t.dominantRisk(), prob, String.join("、", t.features().values()))
                    : "该支流口暂无气象观测数据，按 0mm 保守处理");
            items.add(item);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", t.id());
            d.put("name", t.name());
            d.put("riskType", t.dominantRisk());
            d.put("probabilityPct", Math.round(prob));
            d.put("confluenceWeight", w);
            d.put("equivalentMm", hasData ? Math.round(eqMm) : null);
            detail.add(d);
        }
        double induced = MAINLINE_BASE_MS + MAINLINE_K * weightedSum;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "live");
        m.put("source", source);
        m.put("sourceLabel", sourceService.labelOf(source));
        m.put("realtimeSource", realtime);
        m.put("dataAvailable", anyData);
        m.put("fetchedAt", fetchedAt);
        m.put("horizonHours", HORIZON_HOURS);
        m.put("avgEquivalentMm", eqCount == 0 ? 0 : Math.round(eqSum / eqCount));
        m.put("equivalentNote", "等效过程降雨量 = 实时雨强(mm/h) × " + HORIZON_HOURS + "h，上限100mm；支流口为示意坐标，格点气象就近取值");
        m.put("tributaries", items);
        m.put("inducedCurrentMs", Math.round(induced * 100.0) / 100.0);
        m.put("weightedRiskSum", Math.round(weightedSum * 100.0) / 100.0);
        m.put("highRiskTributaries", highRisk);
        m.put("detail", detail);
        m.put("formula", String.format(
                "干流设计流速 = 基准 %.1fm/s + %.2f × Σ(概率×顶托权重)，横流1.0/洪水冲击0.8/淤积0.25",
                MAINLINE_BASE_MS, MAINLINE_K));
        return m;
    }

    /**
     * 支流风险对干流航运的影响聚合（把支流卡"用上"的核心）：
     * 各支流概率按风险类型加权求和 → 映射为干流入汇段顶托设计流速。
     * 物理叙事：支流水涨顶托干流、入汇段横流增强，干流可航行流速被推高。
     */
    public Map<String, Object> mainlineImpact(double rainfallMm) {
        double weightedSum = 0;
        List<String> highRisk = new ArrayList<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Tributary t : TRIBUTARIES) {
            double prob = probAt(t, rainfallMm);
            double w = CONFLUENCE_WEIGHTS.getOrDefault(t.dominantRisk(), 0.50);
            weightedSum += prob / 100.0 * w;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", t.id());
            d.put("name", t.name());
            d.put("riskType", t.dominantRisk());
            d.put("probabilityPct", Math.round(prob));
            d.put("confluenceWeight", w);
            detail.add(d);
            if (prob >= 65) {
                highRisk.add(t.name() + t.dominantRisk() + " " + Math.round(prob) + "%");
            }
        }
        double induced = MAINLINE_BASE_MS + MAINLINE_K * weightedSum;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rainfallMm", rainfallMm);
        m.put("inducedCurrentMs", Math.round(induced * 100.0) / 100.0);
        m.put("weightedRiskSum", Math.round(weightedSum * 100.0) / 100.0);
        m.put("highRiskTributaries", highRisk);
        m.put("detail", detail);
        m.put("formula", String.format(
                "干流设计流速 = 基准 %.1fm/s + %.2f × Σ(概率×顶托权重)，横流1.0/洪水冲击0.8/淤积0.25",
                MAINLINE_BASE_MS, MAINLINE_K));
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
