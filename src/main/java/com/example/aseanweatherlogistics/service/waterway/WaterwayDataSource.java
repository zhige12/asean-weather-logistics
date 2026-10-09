package com.example.aseanweatherlogistics.service.waterway;

/**
 * 运河通航观测数据源抽象：禁航判定四类参数的统一取数入口。
 * <p>
 * 现有两个实现：
 * <ul>
 *   <li>{@link MockWaterwayDataSource} —— 比赛/演示阶段：默认正常值 + 手动注入
 *       （如「能见度900m」），验证决策引擎链路；</li>
 *   <li>{@link OfficialWaterwayDataSource} —— 预留对接「广西水运江河海一体化调度平台」，
 *       接口地址与凭证经合作协议获取后配置启用，切换数据源不需要改动核心判定代码。</li>
 * </ul>
 */
public interface WaterwayDataSource {

    /** 数据源标识（decisionLog / 状态接口展示用）。 */
    String name();

    /**
     * 拉取一轮全航道实况观测。
     *
     * @throws Exception 网络/解析失败由调用方捕获并保持既有状态（"没网"≠"无风险"）
     */
    WaterwayObservation fetch() throws Exception;
}
