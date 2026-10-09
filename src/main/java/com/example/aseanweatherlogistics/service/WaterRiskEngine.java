package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 水运风险引擎（双向切换的水运侧判定）：
 * 平陆运河禁航红线——能见度 / 流速 / 浪高 / 风力四参数（与《平陆运河通航安全管理规定》
 * 及调度平台感知网回传字段一一对应），与公路侧的降雨量参数双向映射，
 * 复用同一套"动态边权熔断"思路，只是物理量不同。
 * <p>
 * 禁航红线（任一触发即禁航）：
 * <ul>
 *   <li>能见度 &lt; 1000m（剧本：17:00 后能见度降至 1000m 以下建议锚泊）</li>
 *   <li>流速 &gt; 2.0 m/s</li>
 *   <li>浪高 &gt; 2.0 m</li>
 *   <li>风力 ≥ 7 级（风速 ≥ 13.9 m/s，蒲福风级 7 级下限）</li>
 * </ul>
 * <p>
 * 连续风险评分（未禁航时的连续分值，供方案对比"风险"维度）：
 * 能见度 &lt; 1500m +30、流速 &gt; 1.5m/s +20，再乘支流差异化权重。
 * <p>
 * 支流耦合（调度大屏支流风险卡"参与决策"的入口）：各支流差异化概率经
 * {@link TributaryRiskService#mainlineImpact} 映射为一个"干流设计流速"，
 * 与人工基准取更不利者参与红线判定——支流水涨会顶托干流、推高入汇段流速。
 */
@Service
public class WaterRiskEngine {

    /** 禁航红线 */
    public static final double VIS_MIN_M = 1000.0;
    public static final double CURRENT_MAX_MS = 2.0;
    public static final double WAVE_MAX_M = 2.0;
    /** 风力禁航红线：7 级风下限 13.9 m/s（《规定》"风力达到7级及以上"） */
    public static final double WIND_MAX_MS = 13.9;

    /** 当前运河通航条件（内存态，沙盘/官方接入适配层可调；默认正常） */
    private volatile double visibilityM = 1200.0;
    private volatile double currentSpeedMs = 1.8;
    private volatile double waveHeightM = 0.8;
    private volatile double windSpeedMs = 6.0;

    /** 支流风险耦合出的干流设计流速（m/s），0 = 未联动；参与判定时与人工基准取更不利值 */
    private volatile double tributaryCurrentMs = 0.0;

    public void setConditions(Double vis, Double current, Double wave) {
        setConditions(vis, current, wave, null);
    }

    /** 四参数版：null 表示该项不变（调度平台观测数据接入层透传用）。 */
    public void setConditions(Double vis, Double current, Double wave, Double wind) {
        if (vis != null) this.visibilityM = vis;
        if (current != null) this.currentSpeedMs = current;
        if (wave != null) this.waveHeightM = wave;
        if (wind != null) this.windSpeedMs = wind;
    }

    /** 支流联动：注入/清除支流映射出的干流设计流速（<=0 视为清除）。 */
    public void setTributaryImpact(double inducedCurrentMs) {
        this.tributaryCurrentMs = Math.max(0.0, inducedCurrentMs);
    }

    /** 判定用设计流速：人工基准与支流涨水耦合取更不利者。 */
    public double effectiveCurrentMs() {
        return Math.max(currentSpeedMs, tributaryCurrentMs);
    }

    public boolean isWaterBlocked() {
        return visibilityM < VIS_MIN_M
                || effectiveCurrentMs() > CURRENT_MAX_MS
                || waveHeightM > WAVE_MAX_M
                || windSpeedMs >= WIND_MAX_MS;
    }

    /** 风速（m/s）换算蒲福风级（取所在级下限，12 级封顶）。 */
    public static int beaufortForce(double ms) {
        double[] limits = {0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7};
        for (int i = 0; i < limits.length; i++) {
            if (ms < limits[i]) return i;
        }
        return 12;
    }

    /** 触发了哪条红线（用于弹窗文案），未触发返回 null。 */
    public String blockedReason() {
        if (visibilityM < VIS_MIN_M) {
            return String.format("能见度 %.0fm < %.0fm，触发禁航红线", visibilityM, VIS_MIN_M);
        }
        double cur = effectiveCurrentMs();
        if (cur > CURRENT_MAX_MS) {
            String src = tributaryCurrentMs > currentSpeedMs ? "（含支流涨水联动贡献）" : "";
            return String.format("流速 %.1fm/s > %.1fm/s，触发禁航红线%s", cur, CURRENT_MAX_MS, src);
        }
        if (waveHeightM > WAVE_MAX_M) {
            return String.format("浪高 %.1fm > %.1fm，触发禁航红线", waveHeightM, WAVE_MAX_M);
        }
        if (windSpeedMs >= WIND_MAX_MS) {
            return String.format("风速 %.1fm/s（%d级风）≥ 7级禁航红线", windSpeedMs, beaufortForce(windSpeedMs));
        }
        return null;
    }

    /** 连续风险分值（0-100，未禁航时的风险程度）。 */
    public double scoreWaterRisk() {
        double score = 0;
        if (visibilityM < 1500) score += 30;
        if (effectiveCurrentMs() > 1.5) score += 20;
        if (waveHeightM > 1.2) score += 15;
        if (windSpeedMs > 10.8) score += 10;   // 逼近 7 级（10.8m/s 起为 6 级大风）
        if (isWaterBlocked()) score = Math.max(score, 80);
        return Math.min(100, score);
    }

    public String riskLevel() {
        if (isWaterBlocked()) return "高";
        double s = scoreWaterRisk();
        return s >= 45 ? "中" : "低";
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibilityM", visibilityM);
        // currentSpeedMs 输出判定用设计流速（沙盘面板按红线标红），人工基准/支流耦合单独暴露
        m.put("currentSpeedMs", effectiveCurrentMs());
        m.put("manualCurrentSpeedMs", currentSpeedMs);
        m.put("tributaryCurrentMs", tributaryCurrentMs);
        m.put("waveHeightM", waveHeightM);
        m.put("windSpeedMs", windSpeedMs);
        m.put("windForce", beaufortForce(windSpeedMs));
        m.put("blocked", isWaterBlocked());
        m.put("blockedReason", blockedReason());
        m.put("riskLevel", riskLevel());
        m.put("riskScore", scoreWaterRisk());
        Map<String, Object> redlines = new LinkedHashMap<>();
        redlines.put("visibilityMinM", VIS_MIN_M);
        redlines.put("currentMaxMs", CURRENT_MAX_MS);
        redlines.put("waveMaxM", WAVE_MAX_M);
        redlines.put("windMaxMs", WIND_MAX_MS);
        m.put("redlines", redlines);
        return m;
    }
}
