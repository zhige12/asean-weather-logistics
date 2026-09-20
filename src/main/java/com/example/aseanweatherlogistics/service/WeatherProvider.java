package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.List;

/**
 * 气象数据源统一抽象（支持运行时切换）：
 * <ul>
 *   <li>{@code contest-observation} 比赛官方 CRA40 实况接口</li>
 *   <li>{@code open-meteo} 公网真实气象接口（Open-Meteo，免 Token）</li>
 *   <li>{@code simulated} 离线模拟数据（断网演示用）</li>
 * </ul>
 * 实现类只需负责"给定节点 → 返回天气点"，缓存与降级由 RealWeatherService 统一处理。
 */
public interface WeatherProvider {

    /** 数据源标识（写入 lastSource，供前端展示） */
    String id();

    /** 中文展示名 */
    String label();

    /** 是否已配置可用（如比赛接口缺 Token 则不可用，前端置灰） */
    boolean isConfigured();

    /** 拉取给定节点的实时天气；失败返回空列表，由上层降级到缓存 */
    List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes);
}
