package com.example.aseanweatherlogistics.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoadNode {
    private String id;
    private String name;
    private double latitude;
    private double longitude;
    /** 地名级别：city / town / village（仅标注节点有值，路网节点为空） */
    private String type;
    /** 行政级档位：1 首都/直辖市/重点城市，2 地级市，3 县/镇（level_places.py 生成，旧数据可能为 null） */
    private Integer level;
}
