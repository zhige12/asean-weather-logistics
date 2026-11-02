package com.example.aseanweatherlogistics.service;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门户 AI「快速导航」的离线规则路径固化。
 * <p>
 * 只测确定性核心（大模型通道不可用时走的规则兜底，同 CopilotServiceRuleParseTest 的纪律）：
 * 门户的能力边界是产品决策——「打开X」跳转、导航去某地、知识库问答三类以外的一切
 * （场景模拟/禁航推演/沙盘重置……）都必须回「此功能正在学习中」，绝不执行。
 * 构造时依赖全给 null：大模型路径抛异常会被 catch 后落规则兜底，正好是被测路径。
 */
class PortalCopilotRuleTest {

    private final PortalCopilotService portal = new PortalCopilotService(null, new CopilotService(null, null, null, null, null, null));

    private Map<String, Object> chat(String msg) {
        return portal.chat(msg);
    }

    // ---------- 打开类：跳到门户内对应页面 ----------

    @Test
    void openDriver_returnsExternalJump() {
        Map<String, Object> r = chat("打开司机端");
        assertEquals("open", r.get("action"));
        Map<?, ?> args = (Map<?, ?>) r.get("args");
        assertEquals("driver", args.get("target"));
        assertEquals("/driver.html", args.get("url"));
    }

    @Test
    void openDashboard_andInternalViews() {
        assertEquals("dashboard", ((Map<?, ?>) chat("进入调度大屏").get("args")).get("target"));
        assertEquals("open", chat("看看水运").get("action"));
        assertEquals("waterway", ((Map<?, ?>) chat("切换水运页面").get("args")).get("target"));
        assertEquals("customs", ((Map<?, ?>) chat("打开通关时效").get("args")).get("target"));
        assertEquals("weather", ((Map<?, ?>) chat("进入实时气象").get("args")).get("target"));
    }

    @Test
    void questionsAreNotJumpsToForeignPages() {
        // 提问句式不得被听成「打开某页」的跳转口令；能只读展示的会引导到对应视图，
        // 但目的地必须是门户内视图（#view=…），绝不是司机端/大屏这种操作台外链
        Map<String, Object> r1 = chat("司机端拥堵吗");
        assertEquals("open", r1.get("action"));
        assertEquals("#view=customs", ((Map<?, ?>) r1.get("args")).get("url"));
        Map<String, Object> r2 = chat("今天天气怎么样");
        assertEquals("open", r2.get("action"));
        assertEquals("#view=weather", ((Map<?, ?>) r2.get("args")).get("url"));
    }

    // ---------- 导航类：跳司机端并带目的地参数 ----------

    @Test
    void navigateTo_opensDriverWithDeepLink() {
        Map<String, Object> r = chat("导航去河内");
        assertEquals("navigate", r.get("action"));
        Map<?, ?> args = (Map<?, ?>) r.get("args");
        assertEquals("河内", args.get("destination"));
        assertEquals("/driver.html?nav=河内", args.get("url"));
    }

    // ---------- 知识库问答：复用大屏同一条 RAG 链路 ----------

    @Test
    void knowledgeGoesThroughRag_andCarriesSourcesShape() {
        Map<String, Object> r = chat("冷链货物通关要什么单证");
        assertEquals("knowledge", r.get("action"));
        // knowledge=null（直接构造）时链路如实降级为「知识库当前不可用」，不得伪装成有答案
        Map<?, ?> data = (Map<?, ?>) r.get("data");
        assertTrue(String.valueOf(data.get("summary")).contains("知识库"), "应如实说明知识库状态");
    }

    // ---------- 越界类：学习中，绝不执行 ----------

    @Test
    void simulationIntent_isRefusedWithLearningMessage() {
        for (String msg : new String[]{"打开暴雨模拟", "模拟运河禁航", "重置沙盘", "把友谊关降雨调到90mm"}) {
            Map<String, Object> r = chat(msg);
            assertEquals("say", r.get("action"), "越界指令不得产生任何动作：" + msg);
            String say = String.valueOf(r.get("say"));
            assertTrue(say.contains("学习中"), "越界指令必须回「学习中」：" + msg + " => " + say);
            assertTrue(say.contains("调度大屏"), "越界指令要指路大屏 AI：" + msg);
        }
    }

    @Test
    void knowledgeQuestionsWithScopeWords_areNotRefused() {
        // 带「场景/禁航/沙盘」字样的知识提问不是操作指令，不得被越界拦截误伤
        for (String msg : new String[]{"暴雨场景下货损怎么理赔", "运河禁航的规定是什么", "熔断的判定标准是什么"}) {
            Map<String, Object> r = chat(msg);
            assertTrue(!String.valueOf(r.get("say")).startsWith("此功能正在学习中"),
                    "知识提问不得被当成越界指令拒绝：" + msg + " => " + r.get("say"));
        }
    }

    @Test
    void customsDataQuery_guidesToReadonlyView() {
        // 「各口岸通关时效」在门户里可只读查看：引导到通关时效视图，同时说明不做推演
        Map<String, Object> r = chat("各口岸通关时效是多少");
        assertEquals("open", r.get("action"));
        assertEquals("customs", ((Map<?, ?>) r.get("args")).get("target"));
        assertTrue(String.valueOf(r.get("say")).contains("学习中"));
    }

    @Test
    void emptyMessage_asksForInput() {
        assertEquals("say", chat("  ").get("action"));
    }
}
