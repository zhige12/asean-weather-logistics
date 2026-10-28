package com.example.aseanweatherlogistics.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 调用计量的确定性用例。
 * <p>
 * 这块数字是要拿去答「落地可行性：推理速度、成本、并发」的，所以必须锁住三件事：
 * ① 失败调用也要计数（只记成功会把「本地模型一直挂」显示成「本地模型很快」）；
 * ② 耗时同时有 avg 与 max（首次调用含权重加载，只报平均会低估一个量级）；
 * ③ 没有单价时不得凭空折算出成本数字。
 */
class AiMetricsServiceTest {

    private AiMetricsService fresh() {
        return new AiMetricsService();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channel(Map<String, Object> snap, String key) {
        return (Map<String, Object>) ((Map<String, Object>) snap.get("byChannel")).get(key);
    }

    @Test
    void aggregatesPerChannel_withFailuresAndMax() {
        AiMetricsService m = fresh();
        m.record(DeepseekService.CHANNEL_LOCAL, "qwen2.5:14b", 900, 100, 40, true);
        m.record(DeepseekService.CHANNEL_LOCAL, "qwen2.5:14b", 3100, 200, 60, true);
        m.record(DeepseekService.CHANNEL_LOCAL, "qwen2.5:14b", 60000, 0, 0, false);
        m.record(DeepseekService.CHANNEL_ONLINE, "deepseek-chat", 1100, 150, 50, true);

        Map<String, Object> snap = m.snapshot();
        Map<String, Object> local = channel(snap, DeepseekService.CHANNEL_LOCAL);

        assertEquals(3L, local.get("calls"), "三次本地尝试都要计数，含失败那次");
        assertEquals(1L, local.get("failures"), "失败次数单独可查");
        assertEquals(400L, local.get("tokens"), "prompt+completion 合并口径（100+40 + 200+60 + 失败那次 0）");
        assertEquals((900L + 3100L + 60000L) / 3, local.get("avgMs"), "平均耗时按调用数取整");
        assertEquals(60000, local.get("maxMs"), "max 必须留住，否则首次加载的 60s 会被平均抹平");
        assertEquals("qwen2.5:14b", local.get("model"));

        Map<String, Object> online = channel(snap, DeepseekService.CHANNEL_ONLINE);
        assertEquals(200L, online.get("tokens"));

        // 总量 = 各通道之和，不能漏掉失败那次
        assertEquals(4L, snap.get("calls"));
        assertEquals(600L, snap.get("tokens"));
    }

    @Test
    void nullChannel_goesToNoneBucket() {
        AiMetricsService m = fresh();
        m.record(null, null, 812, 0, 0, false);
        Map<String, Object> snap = m.snapshot();

        assertTrue(((Map<?, ?>) snap.get("byChannel")).containsKey("none"),
                "两条通道都没起来时也要留下痕迹，不能静默消失");
        assertEquals(1L, channel(snap, "none").get("failures"));
    }

    @Test
    void tracksInFlightAndPeak() {
        AiMetricsService m = fresh();
        assertEquals(0, m.snapshot().get("inFlight"));

        m.beginCall();
        m.beginCall();
        m.beginCall();
        m.endCall();
        Map<String, Object> snap = m.snapshot();
        assertEquals(2, snap.get("inFlight"), "当前在飞数应随结束递减");
        assertEquals(3, snap.get("peakInFlight"), "峰值要留住，它才是「两位评委同时点会占几个线程」的答案");
    }

    @Test
    void costNotInventedWhenPriceUnset() {
        AiMetricsService m = fresh();
        m.record(DeepseekService.CHANNEL_ONLINE, "deepseek-chat", 1000, 1_000_000, 1_000_000, true);
        Map<String, Object> snap = m.snapshot();

        assertFalse((Boolean) snap.get("costConfigured"), "没填单价时不得声称算出了成本");
        assertEquals(0.0, snap.get("costCny"));

        ReflectionTestUtils.setField(m, "onlineInputPerMtok", 2.0);
        ReflectionTestUtils.setField(m, "onlineOutputPerMtok", 8.0);
        Map<String, Object> priced = m.snapshot();
        assertTrue((Boolean) priced.get("costConfigured"));
        // 1M 输入 × 2 元 + 1M 输出 × 8 元 = 10 元
        assertEquals(10.0, ((Number) priced.get("costCny")).doubleValue(), 0.0001);
    }

    @Test
    void lastCallExposedForUi() {
        AiMetricsService m = fresh();
        assertNull(m.snapshot().get("lastCall"), "还没调用过时不要造一个假样本");
        m.record(DeepseekService.CHANNEL_LOCAL, "qwen2.5:14b", 1234, 70, 30, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) m.snapshot().get("lastCall");
        assertEquals(1234L, last.get("elapsedMs"));
        assertEquals(100L, last.get("tokens"));
        assertEquals("local", last.get("channel"));
    }
}
