package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 水运风险引擎（双向切换的水运侧判定）：
 * 平陆运河禁航红线——能见度 / 流速 / 浪高三参数，与公路侧的降雨量参数双向映射，
 * 复用同一套"动态边权熔断"思路，只是物理量不同。
 * <p>
 * 禁航红线（任一触发即禁航）：
 * <ul>
 *   <li>能见度 &lt; 1000m（剧本：17:00 后能见度降至 1000m 以下建议锚泊）</li>
 *   <li>流速 &gt; 2.0 m/s</li>
 *   <li>浪高 &gt; 2.0 m</li>
 * </ul>
 * 风险评分（未禁航时的连续分值，供方案对比"风险"维度）：
 * 能见度 &lt; 1500m +30、流速 &gt; 1.5m/s +20，再乘支流差异化权重。
 */
@Service
public class WaterRiskEngine {

    /** 禁航红线 */
    public static final double VIS_MIN_M = 1000.0;
    public static final double CURRENT_MAX_MS = 2.0;
    public static final double WAVE_MAX_M = 2.0;

    /** 当前运河通航条件（内存态，沙盘可调；默认正常） */
    private volatile double visibilityM = 1200.0;
    private volatile double currentSpeedMs = 1.8;
    private volatile double waveHeightM = 0.8;

    public void setConditions(Double vis, Double current, Double wave) {
        if (vis != null) this.visibilityM = vis;
        if (current != null) this.currentSpeedMs = current;
        if (wave != null) this.waveHeightM = wave;
    }

    public boolean isWaterBlocked() {
        return visibilityM < VIS_MIN_M
                || currentSpeedMs > CURRENT_MAX_MS
                || waveHeightM > WAVE_MAX_M;
    }

    /** 触发了哪条红线（用于弹窗文案），未触发返回 null。 */
    public String blockedReason() {
        if (visibilityM < VIS_MIN_M) {
            return String.format("能见度 %.0fm < %.0fm，触发禁航红线", visibilityM, VIS_MIN_M);
        }
        if (currentSpeedMs > CURRENT_MAX_MS) {
            return String.format("流速 %.1fm/s > %.1fm/s，触发禁航红线", currentSpeedMs, CURRENT_MAX_MS);
        }
        if (waveHeightM > WAVE_MAX_M) {
            return String.format("浪高 %.1fm > %.1fm，触发禁航红线", waveHeightM, WAVE_MAX_M);
        }
        return null;
    }

    /** 连续风险分值（0-100，未禁航时的风险程度）。 */
    public double scoreWaterRisk() {
        double score = 0;
        if (visibilityM < 1500) score += 30;
        if (currentSpeedMs > 1.5) score += 20;
        if (waveHeightM > 1.2) score += 15;
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
        m.put("currentSpeedMs", currentSpeedMs);
        m.put("waveHeightM", waveHeightM);
        m.put("blocked", isWaterBlocked());
        m.put("blockedReason", blockedReason());
        m.put("riskLevel", riskLevel());
        m.put("riskScore", scoreWaterRisk());
        Map<String, Object> redlines = new LinkedHashMap<>();
        redlines.put("visibilityMinM", VIS_MIN_M);
        redlines.put("currentMaxMs", CURRENT_MAX_MS);
        redlines.put("waveMaxM", WAVE_MAX_M);
        m.put("redlines", redlines);
        return m;
    }
}
