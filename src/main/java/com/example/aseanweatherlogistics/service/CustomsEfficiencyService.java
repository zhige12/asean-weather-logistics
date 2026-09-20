package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.CustomsEfficiencyEntry;
import com.example.aseanweatherlogistics.repository.CustomsEfficiencyRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 通关时效服务（计划书 3.3）。
 * 支持按 口岸 / 时段(period) / 货类(cargoType) 动态计算通关时长，
 * 并允许模拟调整（如口岸拥堵、临时管控）后由路线规划实时感知。
 */
@Service
public class CustomsEfficiencyService {

    private final Map<String, CustomsEfficiencyEntry> byPortId = new LinkedHashMap<>();
    private final Map<String, Adjustment> adjustments = new LinkedHashMap<>();
    private String activePeriod = "normal";
    private String activeCargoType = "general";

    public CustomsEfficiencyService(CustomsEfficiencyRepository repository) {
        for (CustomsEfficiencyEntry entry : repository.loadAll()) {
            byPortId.put(entry.getPortId(), entry);
        }
    }

    /** 当前生效的通关时长（基础值 × 时段系数 × 货类系数，或手动调整值）。 */
    public double customsHoursFor(String portId) {
        CustomsEfficiencyEntry entry = byPortId.get(portId);
        if (entry == null) {
            return 0.0;
        }
        Adjustment adj = adjustments.get(portId);
        if (adj != null) {
            return adj.hours;
        }
        double periodFactor = factor(entry.getPeriods(), activePeriod, 1.0);
        double cargoFactor = factor(entry.getCargoTypes(), activeCargoType, 1.0);
        return entry.getBaseHours() * periodFactor * cargoFactor;
    }

    /** 通过路网边 ID 查找口岸并返回当前通关时长（非口岸边返回 0）。 */
    public double customsHoursByEdgeId(String edgeId) {
        for (CustomsEfficiencyEntry entry : byPortId.values()) {
            if (edgeId != null && edgeId.equals(entry.getEdgeId())) {
                return customsHoursFor(entry.getPortId());
            }
        }
        return 0.0;
    }

    /** 模拟调整某口岸通关时长（如拥堵、临时管控）。 */
    public void update(String portId, double hours, String note) {
        CustomsEfficiencyEntry entry = byPortId.get(portId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown customs port: " + portId);
        }
        adjustments.put(portId, new Adjustment(hours, note == null ? "人工调整" : note));
    }

    /** 重置指定口岸为默认时效。 */
    public void reset(String portId) {
        adjustments.remove(portId);
    }

    public void resetAll() {
        adjustments.clear();
    }

    public void setActivePeriod(String period) {
        this.activePeriod = period == null ? "normal" : period;
    }

    public void setActiveCargoType(String cargoType) {
        this.activeCargoType = cargoType == null ? "general" : cargoType;
    }

    public String getActivePeriod() {
        return activePeriod;
    }

    public String getActiveCargoType() {
        return activeCargoType;
    }

    /** 返回全部口岸当前时效视图。 */
    public List<Map<String, Object>> listAll() {
        return byPortId.values().stream().map(e -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("portId", e.getPortId());
            view.put("portName", e.getPortName());
            view.put("edgeId", e.getEdgeId());
            view.put("baseHours", e.getBaseHours());
            view.put("currentHours", customsHoursFor(e.getPortId()));
            view.put("periods", e.getPeriods());
            view.put("cargoTypes", e.getCargoTypes());
            view.put("adjusted", adjustments.containsKey(e.getPortId()));
            Adjustment adj = adjustments.get(e.getPortId());
            view.put("note", adj == null ? "" : adj.note);
            return view;
        }).toList();
    }

    private double factor(Map<String, Double> table, String key, double fallback) {
        if (table == null) {
            return fallback;
        }
        Double v = table.get(key);
        return v == null ? fallback : v;
    }

    private record Adjustment(double hours, String note) {
    }
}
