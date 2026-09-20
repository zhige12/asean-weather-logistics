package com.example.aseanweatherlogistics.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskSegment {
    private String edgeId;
    private String reason;
    private String severity;
    // penalty multiplier applied to distance (e.g., 100.0 means add 100x cost)
    private Double penaltyMultiplier;
}
