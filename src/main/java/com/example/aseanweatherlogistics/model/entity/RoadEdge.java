package com.example.aseanweatherlogistics.model.entity;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoadEdge {
    private String id;
    private String fromNodeId;
    private String toNodeId;
    private double distanceKm;
    /** 路段限速（km/h），用于通行时间 = 距离/限速 */
    private Double speedKmh;
    /** 是否为口岸通关边（如友谊关/芒街跨境虚拟边） */
    private boolean customs;
    /** 口岸平均通关耗时（小时），如友谊关约 2.5h */
    private Double customsDelayHours;
    /** 路段名称，如「南友高速·南宁—崇左」 */
    private String name;
    /** 道路等级：motorway / trunk / primary / customs */
    private String roadType;
    /** 边几何折线 [[lat,lon],...]（OSM 真实走向，供前端贴合底图绘制；null 时前端退化为直线） */
    private List<List<Double>> c;
}
