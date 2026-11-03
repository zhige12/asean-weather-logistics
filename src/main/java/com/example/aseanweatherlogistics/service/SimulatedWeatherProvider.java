package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return "离线模拟";
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
            // 离线模拟不构造台风场景，气压固定标准海压（不触发低气压判据）
            out.add(new RealWeatherService.WeatherPoint(
                    node.getId(), node.getLatitude(), node.getLongitude(),
                    temp, humidity, precipitation, windKph, visibility, 1013.0, fetchedAt
            ));
        }
        return out;
    }

    /**
     * 离线模拟本身就是纯本地计算（按节点 ID 派生确定性数值），不碰网络，
     * 所以节流窗口内也可以直接给新口岸补数——这正是断网演示时「选了哪条线就看到哪条线的点」要保证的。
     */
    @Override
    public List<RealWeatherService.WeatherPoint> interpolateFromLastFetch(List<RoadNode> nodes) {
        return fetchForNodes(nodes);
    }

    /**
     * 日最高/最低气温与总云量：按同一套伪随机参数从 {@link #fetchForNodes} 的气温派生。
     * <p>本类的定位就是「断网演示用的确定性模拟源」（界面上源标签写的是 离线模拟），
     * 所以这里给值不等于拿假数冒充真数；但<b>不构造 LCC</b>——低云量与气温没有可以直接沿用的
     * 常识关系，编一个只会让「哪些是真、哪些是模拟」变得难以分辨，缺项就让它缺。</p>
     */
    @Override
    public Map<String, Map<String, Double>> fetchExtendedElements(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (RealWeatherService.WeatherPoint p : fetchForNodes(nodes)) {
            int h = Math.abs((p.nodeId() == null ? "" : p.nodeId()).hashCode());
            Map<String, Double> one = new LinkedHashMap<>();
            one.put("TMAX", round(p.temperatureC() + 4.0 + (h % 20) / 10.0));
            one.put("TMIN", round(p.temperatureC() - 5.0 - (h % 15) / 10.0));
            one.put("TCC", round(20.0 + (h / 11) % 70));
            out.put(p.nodeId(), one);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * 前瞻降雨：从本类自己的伪实况按固定倍数派生（未来 3h 取当前雨强的三小时量、6h 取两倍），
     * 让离线演示的那一列不空转。界面上的源标签写的就是「离线模拟」，所以这不构成拿假数冒充真数；
     * 但它和其余展示字段一样不驱动任何算路判据。
     */
    @Override
    public Map<String, Map<String, Double>> fetchForecastOutlook(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (RealWeatherService.WeatherPoint p : fetchForNodes(nodes)) {
            Map<String, Double> one = new LinkedHashMap<>();
            one.put("rain3hMm", round(p.precipitationMm()));
            one.put("rain6hMm", round(p.precipitationMm() * 2.0));
            out.put(p.nodeId(), one);
        }
        return out;
    }
}
