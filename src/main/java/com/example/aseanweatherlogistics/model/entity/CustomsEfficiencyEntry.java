package com.example.aseanweatherlogistics.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 通关时效表（计划书 3.3）。
 * 每个口岸一段配置：基础通关时长 + 时段系数 + 货类系数。
 */
@Getter
@Setter
public class CustomsEfficiencyEntry {
    @JsonProperty("portId")
    private String portId;

    @JsonProperty("portName")
    private String portName;

    @JsonProperty("edgeId")
    private String edgeId;

    @JsonProperty("baseHours")
    private double baseHours;

    @JsonProperty("periods")
    private Map<String, Double> periods;

    @JsonProperty("cargoTypes")
    private Map<String, Double> cargoTypes;
}
