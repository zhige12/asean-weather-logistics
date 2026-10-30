package com.example.aseanweatherlogistics.service;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * AI 并发闸（有界信号量 + 快速失败）的确定性用例。
 * <p>
 * 这块要答的是评分点「落地可行性·并发」：本地/在线模型都是 60s 阻塞调用，一次调用占住一个
 * Tomcat 工作线程。旧版只有 peakInFlight 观测而无背压。本测试锁死三件事：
 * ① 并发达上限时，后来的调用<b>快速失败</b>（rejected=true、text/channel 均 null，交上层降级），
 *    而不是排队把等待堆成新的挂死；
 * ② 无论多少请求并发涌入，真正进入模型调用的<b>同时数从不超过上限</b>（这才叫「限住」）；
 * ③ 有空位时正常放行，不误伤；上限值随 /api/ai/metrics 回包如实透出。
 */
class DeepseekServiceConcurrencyGateTest {

    /** 可控阻塞的 Ollama 桩：进入 chatDetailed 时计数并（可选）等释放信号，用来「占住」令牌。 */
    private static final class StubOllama extends OllamaService {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        StubOllama(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String model() {
            return "stub-model";
        }

        @Override
        public LlmReply chatDetailed(String systemPrompt, String userPrompt, int maxTokens) {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            inside.decrementAndGet();
            return new LlmReply("ok", 1, 1);
        }
    }

    @Test
    void rejectsFastWhenGateSaturated_andNeverExceedsLimit() throws Exception {
        AiMetricsService metrics = new AiMetricsService();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StubOllama ollama = new StubOllama(entered, release);
        // 上限 1、acquire-wait 0：只要有一个在飞，后来的立即失败
        DeepseekService svc = new DeepseekService(ollama, metrics, 1, 0);

        // T1 进入模型调用并阻塞，占住唯一的令牌
        AtomicReference<DeepseekService.AiReply> first = new AtomicReference<>();
        Thread t1 = new Thread(() -> first.set(svc.chatWithChannel("sys", "u", 10)));
        t1.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "T1 应已进入模型调用");

        // 主线程再发一发：无空位 → 快速失败、降级
        DeepseekService.AiReply second = svc.chatWithChannel("sys", "u", 10);
        assertTrue(second.rejected(), "饱和时后来的调用应被标记为并发拒绝");
        assertNull(second.text(), "拒绝时不返回模型文本");
        assertNull(second.channel(), "拒绝时无通道，交上层按降级处理");
        assertEquals(1, ollama.maxInside.get(), "被拒绝的调用绝不能进入模型（同时进入数不得超过上限）");

        Map<String, Object> snap = metrics.snapshot();
        assertEquals(1L, snap.get("rejected"), "拒绝数要可核");
        assertEquals(1, snap.get("inFlightLimit"), "上限随回包如实透出");

        // 放行 T1，它正常拿到本地通道
        release.countDown();
        t1.join(3000);
        assertFalse(first.get().rejected(), "抢到令牌的调用不该被记为拒绝");
        assertEquals(DeepseekService.CHANNEL_LOCAL, first.get().channel());
        assertEquals(1, ollama.maxInside.get(), "全程同时进入模型的最多数仍受上限约束");
    }

    @Test
    void passesThroughWhenCapacityAvailable() {
        AiMetricsService metrics = new AiMetricsService();
        StubOllama ollama = new StubOllama(null, null); // 不阻塞，立即返回
        DeepseekService svc = new DeepseekService(ollama, metrics, 4, 2000);

        DeepseekService.AiReply reply = svc.chatWithChannel("sys", "u", 10);
        assertFalse(reply.rejected(), "有空位时正常放行");
        assertEquals(DeepseekService.CHANNEL_LOCAL, reply.channel());
        assertEquals(0L, metrics.snapshot().get("rejected"), "没触发限流时拒绝数应为 0");
    }
}
