package com.example.aseanweatherlogistics.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteResponse {
    private List<String> pathNodeIds;
    private List<String> pathEdgeIds;
    private double totalDistanceKm;
    private double estimatedHours;
    private boolean rerouted;
    private List<RiskSegment> riskSegments;

    /** 无风险时的基准路径节点 */
    private List<String> baselinePathNodeIds;
    /** 基准通行时长（小时） */
    private double baselineHours;
    /** 当前（叠加风险后）通行时长（小时） */
    private double currentHours;
    /** 因熔断绕行增加的时长（小时） */
    private double extraHours;
    /** 冷链货损估算（元）：仅冷链货且延误>4h 时 >0（计划书 5.2：2% 基础 + 高温>30°C +1% + 高湿>85% +0.5%，×延误小时/24） */
    private double cargoLossYuan;
    /**
     * 当前路径节点坐标序列 [[lat,lng], ...]，供司机端/移动端直接绘图。
     * 司机端不能加载 35MB 路网 JSON，因此由后端随路线一并下发（单条跨境路约 50-100 点，几 KB）。
     */
    private List<double[]> pathCoords;
    /** 基准（无风险）路径坐标序列，用于地图上画灰虚线做绕行对比 */
    private List<double[]> baselinePathCoords;
    /**
     * 基准路径每条边 ID，与 baselinePathEdgeSpans 一一对应。
     * 大屏可据此把"原路线穿过风险区"的那几段标红，直观展示绕行避开了什么。
     */
    private List<String> baselinePathEdgeIds;
    /** 每条基准边在 baselinePathCoords 中的下标区间 [startIdx, endIdx] */
    private List<int[]> baselinePathEdgeSpans;
    /**
     * 每条边在 pathCoords 中的下标区间 [startIdx, endIdx]，与 pathEdgeIds 一一对应。
     * 边几何加密后 pathCoords 点数 > 边数，前端风险段高亮需按区间取点。
     */
    private List<int[]> pathEdgeSpans;

    /** 预计到达时间（ISO 8601 格式），受天气和通关影响 */
    private String estimatedArrival;
    /** 延迟原因说明（如"因暴雨延迟 +2h"） */
    private String delayReason;
    /** 碳排放估算（kg CO₂） */
    private double carbonEmissionKg;
    /** 风险剖面：沿路径采样点，每点含 distanceKm/riskLevel/penalty/hazardType */
    private List<java.util.Map<String, Object>> riskProfile;
}
