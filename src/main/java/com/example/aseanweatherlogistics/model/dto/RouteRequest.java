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

    /** 坐标模式（与 originId/destinationId 二选一）：起点纬度 WGS-84，先地理编码再吸附路网 */
    private Double startLat;
    private Double startLng;
    /** 坐标模式：终点纬度/经度 */
    private Double endLat;
    private Double endLng;

    public RouteRequest(String originId, String destinationId) {
        this.originId = originId;
        this.destinationId = destinationId;
    }

    /** 保留四参构造：@AllArgsConstructor 加坐标字段后变八参，旧调用点靠这个兼容 */
    public RouteRequest(String originId, String destinationId, String period, String cargoType) {
        this.originId = originId;
        this.destinationId = destinationId;
        this.period = period;
        this.cargoType = cargoType;
    }
}
