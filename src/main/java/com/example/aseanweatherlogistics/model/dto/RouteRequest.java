package com.example.aseanweatherlogistics.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteRequest {
    private String originId;
    private String destinationId;
    /** 通关时段：normal / peak / holiday */
    private String period;
    /** 货类：general / cold / dangerous / oversized */
    private String cargoType;

    public RouteRequest(String originId, String destinationId) {
        this.originId = originId;
        this.destinationId = destinationId;
    }
}
