package com.example.aseanweatherlogistics.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运输任务（演示第一/四幕「公司派单」）：物流公司派给司机的跨境运单。
 * <p>
 * 司机端收到派单后自动从「普通导航模式」切换到「物流任务模式」：
 * 车牌、货物、起终点一律以派单为准，不再由司机手填，
 * 屏幕上显示车牌 + 货物信息 + 路线状态条（绿=畅通）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransportTask {

    /** 任务号（每次派单递增，司机端据此识别"这是新的一单"） */
    private String taskId;
    /** 车牌号（司机端状态条展示） */
    private String plate;
    /** 承运司机姓名 */
    private String driverName;
    /** 越南侧接力司机姓名（剧本第⑨幕"越南同事也收到通知"） */
    private String driverNameVn;
    /** 货物类型 key，对应 {@link com.example.aseanweatherlogistics.service.DemoConfigService#CARGO_TYPES} */
    private String cargoType;
    /** 货物名称 */
    private String cargoName;
    /** 货重（吨） */
    private Double weightT;
    /** 温控要求 */
    private String temp;
    /** 起运地节点 ID（默认南宁 NN） */
    private String originId;
    /** 目的地节点 ID（默认河内 HN） */
    private String destinationId;
    /** 要求送达时限（展示文案） */
    private String deadline;
    /** DISPATCHED=已派单待接单 / ACCEPTED=司机已接单 */
    private String status;
    private Long dispatchedAt;
    private Long acceptedAt;
}