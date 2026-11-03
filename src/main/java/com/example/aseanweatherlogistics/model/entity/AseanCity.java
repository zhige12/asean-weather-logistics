package com.example.aseanweatherlogistics.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 东盟态势城市（门户「东盟态势」视图的观测点位）。
 *
 * <p>这些点位<b>不参与算路</b>：系统路网只覆盖中国—越南走廊，曼谷→新加坡这类城市对
 * 在路网里没有边，拿来算路线会凭空造出不存在的路。本表只服务「区域气象态势展示」——
 * 按经纬度向官方 CRA40 格点取实况、再算单点 AMRI，让界面能如实交代覆盖范围。
 */
@Getter
@Setter
public class AseanCity {
    @JsonProperty("cityId")
    private String cityId;

    /** 中文名（界面主展示） */
    @JsonProperty("name")
    private String name;

    /** 拉丁写法英文名（语言切换为非中文时直接用，避免前端词典逐词硬凑出不存在的地名译法） */
    @JsonProperty("nameEn")
    private String nameEn;

    @JsonProperty("country")
    private String country;

    /** 拉丁写法国名，理由同 nameEn */
    @JsonProperty("countryEn")
    private String countryEn;

    @JsonProperty("countryCode")
    private String countryCode;

    /** 城市定位：首都 / 经济中心 / 港口枢纽 / 边境口岸城市 / 中国—东盟枢纽 */
    @JsonProperty("role")
    private String role;

    /** 城市定位的拉丁写法，理由同 nameEn */
    @JsonProperty("roleEn")
    private String roleEn;

    @JsonProperty("latitude")
    private double latitude;

    @JsonProperty("longitude")
    private double longitude;

    /** 是否位于中国—越南作业走廊上（界面据此标「走廊」徽标，与纯态势城市区分） */
    @JsonProperty("corridor")
    private boolean corridor;
}
