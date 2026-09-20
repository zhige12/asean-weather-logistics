package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 离线模拟气象数据源（断网演示用）：按节点 ID 生成确定性的温和天气，
 * 保证天气卡片有数据可展示，且数值温和（不触发风险惩罚，避免与灾害场景注入混淆）。
 */
@Service
public class SimulatedWeatherProvider implements WeatherProvider {

    public static final String ID = "simulated";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "离线模拟数据（不联网）";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        long fetchedAt = System.currentTimeMillis();
        List<RealWeatherService.WeatherPoint> out = new ArrayList<>(nodes.size());
        for (RoadNode node : nodes) {
            int h = Math.abs((node.getId() == null ? "" : node.getId()).hashCode());
            // 纬度越低越热：22°N 附近约 30℃，10°N 附近约 33℃
            double lat = Math.min(30.0, Math.max(10.0, node.getLatitude()));
            double temp = 26 + (30 - lat) * 0.25 + (h % 30) / 10.0;
            double humidity = 55 + (h / 7) % 30;
            double precipitation = (h % 100) < 15 ? (h % 30) / 10.0 : 0.0;
            double windKph = 6 + (h / 13) % 18;
            double visibility = precipitation > 0 ? 6000 + (h % 20) * 100 : 9000 + (h % 30) * 100;
            out.add(new RealWeatherService.WeatherPoint(
                    node.getId(), node.getLatitude(), node.getLongitude(),
                    temp, humidity, precipitation, windKph, visibility, fetchedAt
            ));
        }
        return out;
    }
}
