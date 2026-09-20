package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
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
 * 模型：P(rain) = base × (0.55 + rain/100)，45mm 时系数为 1.0（即 base 即剧本标称概率），
 * 截断到 [5%, 97%]。base 来自各支流特征向量的专家标定。
 */
@Service
public class TributaryRiskService {

    /** 支流档案：特征向量 + 标定基线概率（45mm 降雨下） */
    public record Tributary(String id, String name, String dominantRisk,
                            Map<String, String> features, double baseProbPct) {
    }

    public static final List<Tributary> TRIBUTARIES = List.of(
            new Tributary("JIUZHOU", "旧州江支流口", "淤积风险",
                    Map.of("河床质", "沙层河床", "历史淤积比", "84%", "入汇形态", "顺直入汇", "含沙量", "高"),
                    72.0),
            new Tributary("XINPING", "新坪水支流口", "淤积风险",
                    Map.of("河床质", "沙层河床", "入汇形态", "弯顶入汇", "含沙量", "降雨后显著增大", "历史淤积比", "61%"),
                    58.0),
            new Tributary("SHAPING", "沙坪河支流口", "洪水冲击风险",
                    Map.of("河床质", "岩质河床", "入汇角", "大（近直角入汇）", "历史淤积比", "12%", "含沙量", "低"),
                    35.0),
            new Tributary("LAOCUN", "老村河支流口", "横流风险",
                    Map.of("河床质", "砂卵石河床", "横流流速", "降雨后可能超标", "航道宽度", "窄", "弯曲半径", "小"),
                    61.0)
    );

    /** 各支流在未来 6 小时降雨 rainfallMm 下的风险概率预测。 */
    public List<Map<String, Object>> predict(double rainfallMm) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tributary t : TRIBUTARIES) {
            double prob = clamp(t.baseProbPct() * (0.55 + rainfallMm / 100.0), 5.0, 97.0);
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

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
