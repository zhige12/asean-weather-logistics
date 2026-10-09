package com.example.aseanweatherlogistics.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 后台气象守护：不依赖任何客户端轮询，定期主动拉取沿线实时气象并同步为风险段。
 *
 * <p>为什么需要：此前「气象 → 风险 → 熔断 → SSE 推送」整条链路的唯一触发源，是前端 15 秒轮询
 * {@code /api/route/plan-with-weather} 时顺带调用的 {@link RouteService#syncRealWeather}——
 * 只要没有一个页面开着，系统就永远不会发现任何真实灾害。本守护补上这个不依赖客户端的触发源。</p>
 *
 * <p>合规保护（比赛方要求禁止暴力访问）：调度周期 60 秒贴着
 * {@link RealWeatherService} 的最小请求间隔下限，且其内部还有连续失败熔断静默，
 * 不会把上游接口打爆。force=true 只是绕过 RouteService 侧 5 分钟的同步 TTL，
 * 真正决定发不发请求的仍是 RealWeatherService 的节流。</p>
 *
 * <p>职责边界：本类只负责「发现」——把实时气象写进风险表。新风险触发 clearRealRisks/applyRealRisk
 * 内部的 fireChange() 后，RouteAgentService 会自动防抖重算并 SSE 推送，
 * 熔断（penalty≥100 硬切断）也在那条链上生效。本类不碰熔断与推送逻辑。</p>
 *
 * <p>可配置：{@code weather.watchdog.enabled}（默认 true，置 false 则本 Bean 根本不创建、退回纯轮询检测）、
 * {@code weather.watchdog.interval}（默认 60s）、{@code weather.watchdog.initial-delay}（默认 20s）。</p>
 */
@Service
@ConditionalOnProperty(name = "weather.watchdog.enabled", havingValue = "true", matchIfMissing = true)
public class WeatherWatchdogService {
    private static final Logger log = LoggerFactory.getLogger(WeatherWatchdogService.class);

    private final RouteService routeService;
    private final RouteAgentService routeAgentService;
    private final RealWeatherService realWeatherService;

    public WeatherWatchdogService(RouteService routeService,
                                  RouteAgentService routeAgentService,
                                  RealWeatherService realWeatherService) {
        this.routeService = routeService;
        this.routeAgentService = routeAgentService;
        this.realWeatherService = realWeatherService;
    }

    /**
     * 周期主动巡检一次（周期与启动延迟由 weather.watchdog.interval / .initial-delay 配置，
     * 默认 60s / 20s，启动后延迟先让路网与数据源就绪）。
     * fixedDelay 语义：上一轮「跑完」后再等一个周期，绝不与慢请求叠跑。
     */
    @Scheduled(fixedDelayString = "${weather.watchdog.interval:60s}",
            initialDelayString = "${weather.watchdog.initial-delay:20s}")
    public void patrol() {
        try {
            // 关注走廊（含兜底主路线）的沿线节点取并集、只拉一次，避免逐条 clearRealRisks 互相清除
            List<String[]> corridors = routeAgentService.watchedCorridors();
            routeService.syncRealWeatherForCorridors(true, corridors);

            // 可观测性：把本轮实际生效的气象源打进日志，让「真检测到底通没通」一眼可证
            String src = realWeatherService.lastSource();
            if (src == null || src.isBlank() || "none".equals(src) || "simulated".equals(src)) {
                log.warn("气象守护：本轮未取到真实气象（lastSource={}），"
                        + "请检查 weather.real.token / weather.source 配置——真实熔断链路暂不生效", src);
            } else if (log.isDebugEnabled()) {
                log.debug("气象守护：本轮真实气象源={}，沿线风险已同步", src);
            }
        } catch (Exception e) {
            // 守护线程绝不因单次异常中断后续调度
            log.warn("气象守护本轮失败：{}", e.toString());
        }
    }
}
