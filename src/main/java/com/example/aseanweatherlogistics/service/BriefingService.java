package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 任务简报生成服务：将路线规划结果格式化为可复制的文本简报
 */
@Service
public class BriefingService {

    /**
     * 生成任务简报（纯文本格式）
     *
     * @param route      路线规划结果
     * @param originName 起点名称
     * @param destName   终点名称
     * @param plate      车牌号
     * @param cargoType  货物类型
     * @return 格式化文本简报
     */
    public String generateBriefing(RouteResponse route, String originName, String destName,
                                   String plate, String cargoType) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("  【跨境运输任务简报】\n");
        sb.append("═══════════════════════════════════════\n\n");

        // 基本信息
        sb.append("▸ 路线：").append(originName).append(" → ").append(destName).append("\n");
        if (plate != null && !plate.isBlank()) {
            sb.append("▸ 车牌：").append(plate).append("\n");
        }
        sb.append("▸ 货物：").append(cargoTypeName(cargoType)).append("\n");
        sb.append("▸ 生成时间：").append(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");

        // 路线摘要
        sb.append("【路线摘要】\n");
        sb.append("  总距离：").append(String.format("%.0f", route.getTotalDistanceKm())).append(" km\n");
        sb.append("  预计行驶：").append(String.format("%.1f", route.getEstimatedHours())).append(" 小时\n");
        if (route.getEstimatedArrival() != null) {
            try {
                OffsetDateTime eta = OffsetDateTime.parse(route.getEstimatedArrival());
                sb.append("  预计到达：").append(eta.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append("\n");
            } catch (Exception ignored) {}
        }
        if (route.getExtraHours() > 0.5) {
            sb.append("  ⚠ 延迟：+").append(String.format("%.1f", route.getExtraHours())).append(" 小时");
            if (route.getDelayReason() != null) {
                sb.append("（").append(route.getDelayReason()).append("）");
            }
            sb.append("\n");
        }
        if (route.isRerouted()) {
            sb.append("  🔄 已自动绕行（切换口岸/走廊）\n");
        }
        sb.append("\n");

        // 风险评估
        List<RiskSegment> risks = route.getRiskSegments();
        if (risks != null && !risks.isEmpty()) {
            sb.append("【风险评估】\n");
            sb.append("  风险路段：").append(risks.size()).append(" 处\n");
            int critical = 0, high = 0, medium = 0;
            for (RiskSegment r : risks) {
                if ("CRITICAL".equals(r.getSeverity())) critical++;
                else if ("HIGH".equals(r.getSeverity())) high++;
                else medium++;
            }
            if (critical > 0) sb.append("  🔴 严重：").append(critical).append(" 处\n");
            if (high > 0) sb.append("  🟠 高：").append(high).append(" 处\n");
            if (medium > 0) sb.append("  🟡 中：").append(medium).append(" 处\n");
            sb.append("\n");
        }

        // 碳排放
        if (route.getCarbonEmissionKg() > 0) {
            sb.append("【碳排放】\n");
            sb.append("  估算排放：").append(String.format("%.0f", route.getCarbonEmissionKg())).append(" kg CO₂\n");
            sb.append("  （按重载卡车 0.35L/km × 柴油 2.68kg/L 计算）\n\n");
        }

        // 冷链货损
        if (route.getCargoLossYuan() > 0) {
            sb.append("【冷链货损预警】\n");
            sb.append("  预估货损：¥").append(String.format("%.0f", route.getCargoLossYuan())).append("\n");
            sb.append("  （延误超 4h，温湿度加速损耗）\n\n");
        }

        // 建议
        sb.append("【调度建议】\n");
        if (risks != null && !risks.isEmpty()) {
            sb.append("  • 沿途有").append(risks.size()).append("处风险路段，请提醒司机注意\n");
        }
        if (route.getExtraHours() > 2) {
            sb.append("  • 延迟超过 2 小时，建议通知收货方\n");
        }
        if (route.isRerouted()) {
            sb.append("  • 已自动绕行，请确认新路线是否合适\n");
        }
        if (risks == null || risks.isEmpty()) {
            sb.append("  • 路线通畅，按计划执行即可\n");
        }

        sb.append("\n═══════════════════════════════════════\n");
        sb.append("  东盟跨境物流气象导航平台\n");
        sb.append("═══════════════════════════════════════\n");

        return sb.toString();
    }

    private String cargoTypeName(String cargoType) {
        if (cargoType == null) return "普货";
        return switch (cargoType) {
            case "cold" -> "冷链";
            case "dangerous" -> "危化品";
            case "oversized" -> "大件";
            default -> "普货";
        };
    }
}
