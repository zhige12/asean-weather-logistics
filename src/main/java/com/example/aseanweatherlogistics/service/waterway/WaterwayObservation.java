package com.example.aseanweatherlogistics.service.waterway;

/**
 * 平陆运河航段实况观测（禁航判定四类核心参数）。
 * <p>
 * 字段与「广西水运江河海一体化调度平台」（"智慧运河"）感知网回传项一一对应：
 * <ul>
 *   <li>{@code visibilityM}  —— 能见度（m），气象监测站/视频监控反演</li>
 *   <li>{@code currentMs}    —— 流速（m/s），11 座专用水文站，每 15 分钟上报</li>
 *   <li>{@code waveHeightM}  —— 浪高（m），127 座航标 + 雷达等全航道感知网</li>
 *   <li>{@code windMs}       —— 风速（m/s），气象监测站（禁航红线为风力 ≥ 7 级）</li>
 * </ul>
 * 任一字段允许为 null，表示该平台本轮未回传此项，接入层保持引擎现值不变。
 */
public record WaterwayObservation(Double visibilityM,
                                  Double currentMs,
                                  Double waveHeightM,
                                  Double windMs,
                                  String station,
                                  String source,
                                  long observedAt) {

    /** 与上一轮观测比较四类参数，全等则视为无变化（避免每 15 分钟刷决策日志）。 */
    public boolean sameValues(WaterwayObservation other) {
        if (other == null) {
            return false;
        }
        return eq(visibilityM, other.visibilityM)
                && eq(currentMs, other.currentMs)
                && eq(waveHeightM, other.waveHeightM)
                && eq(windMs, other.windMs);
    }

    private static boolean eq(Double a, Double b) {
        return (a == null && b == null) || (a != null && b != null && Double.compare(a, b) == 0);
    }
}
