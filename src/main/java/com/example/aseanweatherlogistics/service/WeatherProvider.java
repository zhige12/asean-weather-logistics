package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.List;
import java.util.Map;

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

    /**
     * 官方接口另外开放、但算路判据用不到的要素：日最高气温 TMAX / 日最低气温 TMIN /
     * 总云量 TCC / 低云量 LCC（{@code GET /api/v1/variables?product=observation} 列了 11 个要素，
     * 主链路取其中 7 个——雨/风/能见度/气压/气温/湿度够驱动边权，多出来的 4 个不驱动任何权重）。
     *
     * <p>它们不进 {@link RealWeatherService.WeatherPoint}：那个记录被算路、缓存签名、熔断退避共用，
     * 为展示型字段去改它会牵动主链路；这里单独一条通道，取不到就返回空 Map，
     * 界面按缺项显示「—」，<b>绝不用默认值凑一个看起来合理的数字</b>。</p>
     *
     * @return 节点 id → （变量名 → 值），缺项不填
     */
    default Map<String, Map<String, Double>> fetchExtendedElements(List<RoadNode> nodes) {
        return Map.of();
    }

    /**
     * 区域前瞻预报：官方 {@code GET /api/v1/forecasts/3h} 同样是开放接口，
     * 走廊侧已由 {@link ContestForecastService} 取满 11 个要素（PRE 驱动沙盘熔断），
     * 但区域态势以前只拿实况——只看“现在”、不看“接下来六小时会不会下”，在物流调度语境里是不完整的。
     *
     * <p>不复用 {@link ContestForecastService}：那个服务只有一份缓存（lastResult），
     * 走廊小框与区域大框轮流调用会互相返回对方 bbox 的错位结果（详见 AseanSituationService 类注释）。
     * 本方法无状态，频度完全由调用方（区域态势自己的一轮节流）控制。</p>
     *
     * @return 节点 id → （"rain3hMm" / "rain6hMm" → 毫米），取不到的项不填
     */
    default Map<String, Map<String, Double>> fetchForecastOutlook(List<RoadNode> nodes) {
        return Map.of();
    }
}
