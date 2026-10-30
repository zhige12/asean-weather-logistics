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

    /**
     * 用「本机上一轮已经从该源取回、还留在内存里的数据」为节点补数；补不出来返回空列表。
     *
     * <p>硬性约束：本方法<b>绝不允许发起任何网络请求</b>——它的用途正是在上游节流/熔断窗口内
     * 给「刚被选进路线的新口岸」补上天气。节流挡掉时如果只能等窗口打开，实测要 240 秒量级
     * （实况接口按固定时次取数、内容不变会指数退避），调度员选了走芒街的线却迟迟看不到芒街。</p>
     *
     * <p>默认不支持（返回空），只有能保留上一轮格点/批量结果的源（CRA40 格点接口）值得实现；
     * 拿不到就退回原有行为，不会比现状更差。</p>
     */
    default List<RealWeatherService.WeatherPoint> interpolateFromLastFetch(List<RoadNode> nodes) {
        return List.of();
    }
}
