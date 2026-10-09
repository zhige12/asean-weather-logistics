package com.example.aseanweatherlogistics.service.waterway;

import org.springframework.stereotype.Component;

/**
 * 模拟数据源（比赛/演示阶段，默认启用）：
 * 持有最近一轮"平台回传"观测值——初始为全航道正常值，
 * 演示时经 WaterwayController 手动注入（如「能见度 900m」触发禁航），
 * 走与官方源完全相同的 ingest → 红线比对 → canal-block 广播链路，
 * 证明接入逻辑已跑通，正式落地只换数据源实现。
 */
@Component
public class MockWaterwayDataSource implements WaterwayDataSource {

    /** 正常通航基准值（与沙盘 reset 一致） */
    private static final double OK_VIS = 1200.0;
    private static final double OK_CURRENT = 1.8;
    private static final double OK_WAVE = 0.8;
    private static final double OK_WIND = 6.0;

    /** 最近一轮手动注入值；null 分量表示沿用基准值 */
    private volatile Double vis;
    private volatile Double current;
    private volatile Double wave;
    private volatile Double wind;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public WaterwayObservation fetch() {
        return new WaterwayObservation(
                vis != null ? vis : OK_VIS,
                current != null ? current : OK_CURRENT,
                wave != null ? wave : OK_WAVE,
                wind != null ? wind : OK_WIND,
                "马道枢纽（模拟）", "mock", System.currentTimeMillis());
    }

    /** 手动注入一轮观测（null 分量落回基准值），演示"官方平台推送"用。 */
    public void inject(Double visibilityM, Double currentMs, Double waveHeightM, Double windMs) {
        this.vis = visibilityM;
        this.current = currentMs;
        this.wave = waveHeightM;
        this.wind = windMs;
    }
}
