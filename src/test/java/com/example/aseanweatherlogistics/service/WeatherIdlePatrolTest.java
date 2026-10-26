package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「后台气象守护空转」的两条固化用例。
 * <p>
 * 复现的问题：守护每 60 秒巡检一次，而 {@code weather.real.observation-time} 是一个固定时次——
 * 上游每次返回的字节完全一样。旧实现在这份「什么都没变」的数据上仍做三件贵事：
 * ① 每轮照打上游一次（60s 巡检正好贴 60s 节流下限，实测 21 小时 1247 次全白打，
 * 而规则明确禁止暴力访问）；② {@code clearRealRisks()} + 逐条 {@code applyRealRisk()}
 * 各自无条件 {@code fireChange()}，于是每分钟叫醒一次 agent：全量重算所有关注路线
 * ＋向所有 SSE 订阅者广播整套路线载荷；③ 每轮重跑走廊基线路径的 Dijkstra。
 * <p>
 * 本文件锁住 ①（内容未变则指数退避）与 ②（风险内容未变则不通知 agent）——
 * 这两条都是「不报错、只白跑」的缺陷，靠读代码发现不了，必须有时序断言。
 */
class WeatherIdlePatrolTest {

    // ==================== ① 内容未变 → 回源指数退避 ====================

    /** 桩数据源：内容可由测试改动，用来模拟「上游返回变了/没变」 */
    private static final class StubProvider implements WeatherProvider {
        static final String ID = "stub";
        double rainMm = 12.0;
        int calls = 0;

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String label() {
            return "桩源";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
            calls++;
            List<RealWeatherService.WeatherPoint> out = new ArrayList<>();
            for (RoadNode n : nodes) {
                // fetchedAt 用当前时间：真实场景每次必不同，正好验证签名没有把它算进去
                out.add(new RealWeatherService.WeatherPoint(n.getId(), n.getLatitude(), n.getLongitude(),
                        28.0, 70.0, rainMm, 15.0, 8000.0, 1008.0, System.currentTimeMillis()));
            }
            return out;
        }
    }

    private static RealWeatherService newService(StubProvider provider, long backoffMaxSeconds) {
        RealWeatherService svc = new RealWeatherService(
                new WeatherSourceService(List.of(provider), StubProvider.ID));
        ReflectionTestUtils.setField(svc, "ttlMinutes", 10L);
        // 基础窗口设成能被用例覆盖的最小值（内部 Math.max(1,·) → 1 秒），便于用秒级 sleep 驱退避
        ReflectionTestUtils.setField(svc, "minRequestIntervalSeconds", 0L);
        ReflectionTestUtils.setField(svc, "circuitFailureThreshold", 3L);
        ReflectionTestUtils.setField(svc, "circuitOpenSeconds", 300L);
        ReflectionTestUtils.setField(svc, "minSourceSwitchGapSeconds", 0L);
        ReflectionTestUtils.setField(svc, "unchangedBackoffMaxSeconds", backoffMaxSeconds);
        return svc;
    }

    private static List<RoadNode> nodes() {
        return List.of(new RoadNode("NN", "南宁", 22.82, 108.32, "city", 1),
                new RoadNode("HN", "河内", 21.03, 105.85, "city", 1));
    }

    private static long upstreamRequests(RealWeatherService svc) {
        Object v = svc.rateStats().get("upstreamRequests");
        return ((Number) v).longValue();
    }

    /** 上游内容逐字未变时，第三轮不该再打上游：退避把窗口从 1s 放大到 2s */
    @Test
    void unchangedUpstreamContent_backsOffUpstreamCalls() throws Exception {
        StubProvider provider = new StubProvider();
        RealWeatherService svc = newService(provider, 240);

        svc.fetchForNodes(nodes());           // 第 1 轮：回源
        Thread.sleep(1100);
        svc.fetchForNodes(nodes());           // 第 2 轮：内容相同 → streak=1，窗口翻倍到 2s
        Thread.sleep(1100);
        svc.fetchForNodes(nodes());           // 第 3 轮：距上次仅 1.1s < 2s → 应被退避挡下

        assertEquals(2, upstreamRequests(svc), "内容未变时第三轮应被退避挡掉，只回源 2 次");
        assertEquals(2, provider.calls, "桩源被真实调用次数应与上游计数一致");
        Map<String, Object> stats = svc.rateStats();
        assertEquals(1, ((Number) stats.get("unchangedStreak")).intValue(),
                "连续两轮内容相同 → 退避轮数应为 1");
        assertTrue(((Number) stats.get("effectiveIntervalSeconds")).longValue() >= 2,
                "实际生效的回源窗口应已翻倍");
    }

    /** 退避开关置 0 时行为必须退回原样（每源固定 min-interval 回源），防「优化」变成不可关闭的暗行为 */
    @Test
    void backoffDisabled_keepsLegacyFixedInterval() throws Exception {
        StubProvider provider = new StubProvider();
        RealWeatherService svc = newService(provider, 0);

        svc.fetchForNodes(nodes());
        Thread.sleep(1100);
        svc.fetchForNodes(nodes());
        Thread.sleep(1100);
        svc.fetchForNodes(nodes());   // 关闭退避：1.1s > 1s 基础窗口 → 照打

        assertEquals(3, upstreamRequests(svc), "退避关闭时应保持旧行为：过基础窗口就回源");
    }

    /** 内容真的变了必须立刻解除退避（省请求不能把新灾害挡在门外） */
    @Test
    void changedUpstreamContent_resetsBackoff() throws Exception {
        StubProvider provider = new StubProvider();
        RealWeatherService svc = newService(provider, 240);

        svc.fetchForNodes(nodes());
        Thread.sleep(1100);
        provider.rainMm = 61.0;       // 上游换了内容（例如降雨涨到暴雨量级）
        svc.fetchForNodes(nodes());

        Map<String, Object> stats = svc.rateStats();
        assertEquals(0, ((Number) stats.get("unchangedStreak")).intValue(),
                "内容变化必须把退避轮数清零，否则新灾害会被退避窗口挡住");
        assertEquals(1, ((Number) stats.get("effectiveIntervalSeconds")).longValue(),
                "清零后应回到基础窗口");
    }

    /**
     * 切源必须连带解除退避（但不能顺带放宽额度）。
     * <p>
     * 退避引入的新坑：一个已在后台退到 2~4 分钟的源被切回来时，用户「切完就点拉取」会撞在
     * 一个比基础窗口大几倍的闸上，表现为“切了没反应”——那是早先靠 resetThrottle 修掉过的体验缺陷。
     * 同时它不得变成绕过节流的口子：两道硬闸（本源基础间隔 + 跨源间隔）必须仍在。
     */
    @Test
    void resetThrottleOnSourceSwitch_clearsBackoffButNotTheBaseWindow() throws Exception {
        StubProvider provider = new StubProvider();
        RealWeatherService svc = newService(provider, 240);

        svc.fetchForNodes(nodes());
        Thread.sleep(1100);
        svc.fetchForNodes(nodes());           // streak=1 → 本源窗口翻倍到 2s
        Thread.sleep(1100);
        assertEquals(2, upstreamRequests(svc), "重置前：2s 窗口未到，应被退避挡下");

        svc.resetThrottle(StubProvider.ID);   // 模拟切回该源
        svc.fetchForNodes(nodes());
        assertEquals(3, upstreamRequests(svc), "切源应能立刻获得一轮本来就该允许的尝试");

        svc.fetchForNodes(nodes());           // 无 sleep：回源时间戳仍拦着，不得连打
        assertEquals(3, upstreamRequests(svc), "重置不得绕过基础节流窗口（否则又是变相暴力访问）");
    }

    // ==================== ② 风险内容未变 → 不叫醒 agent ====================

    private static RiskSegment risk(String edgeId, String reason, double penalty) {
        RiskSegment r = new RiskSegment();
        r.setEdgeId(edgeId);
        r.setReason(reason);
        r.setSeverity("CRITICAL");
        r.setPenaltyMultiplier(penalty);
        return r;
    }

    /** 同样的风险写第二遍不算变化：不得触发 agent 重算（守护空转的主开销就在这一次重算＋广播） */
    @Test
    void syncRealRisks_identicalContent_doesNotWakeAgent() {
        WeatherSimulator sim = new WeatherSimulator();
        AtomicInteger fires = new AtomicInteger();
        sim.setChangeListener(fires::incrementAndGet);

        assertTrue(sim.syncRealRisks(List.of(risk("E4", "暴雨(降水85.0mm/h)", 100))),
                "首次写入应判为变化");
        assertEquals(1, fires.get(), "首次写入通知一次");

        assertFalse(sim.syncRealRisks(List.of(risk("E4", "暴雨(降水85.0mm/h)", 100))),
                "逐字相同的第二遍不应判为变化");
        assertEquals(1, fires.get(), "内容没变就不要再叫醒 agent——旧路径每分钟都会叫一次");

        assertTrue(sim.syncRealRisks(List.of(risk("E4", "暴雨(降水120.0mm/h)", 100))),
                "reason 变了（降雨量上升）必须判为变化");
        assertEquals(2, fires.get(), "内容有变化时应通知重算");
    }

    /** 本轮不再成立的风险要摘掉并通知；但手动注入的边归场景剧本管，实时同步既不能删也不能覆盖 */
    @Test
    void syncRealRisks_removesStaleRealRisksButKeepsManualOnes() {
        WeatherSimulator sim = new WeatherSimulator();
        AtomicInteger fires = new AtomicInteger();
        sim.setChangeListener(fires::incrementAndGet);

        sim.injectRisk(risk("E9", "台风（气压≤990hPa且风速≥60km/h）", 80)); // 手动注入
        sim.syncRealRisks(List.of(risk("E4", "大雨(降水20.0mm/h)", 20)));    // 实时风险
        int firesAfterInject = fires.get();

        assertTrue(sim.syncRealRisks(List.of()), "实时风险被清空是变化，应通知一次");
        assertEquals(firesAfterInject + 1, fires.get());
        assertTrue(sim.currentRiskMap().containsKey("E9"), "手动注入的边不能被实时同步删掉");
        assertFalse(sim.currentRiskMap().containsKey("E4"), "本轮不再成立的实时风险应被摘掉");

        sim.syncRealRisks(List.of(risk("E9", "大雨(降水20.0mm/h)", 20)));
        assertTrue(sim.currentRiskMap().get("E9").getReason().startsWith("台风"),
                "手动注入的边优先级更高，实时同步不得覆盖");
    }
}
