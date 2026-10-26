package com.example.aseanweatherlogistics.service;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自然语言调度舱「离线规则解析」固化用例。
 * <p>
 * 只测确定性核心：大模型不可用时（拔网线/未装 Ollama）走的 {@link CopilotService#ruleParse}，
 * 以及只读/写操作的确认闸门分类（{@link CopilotService#isReadOnly}）——这条路径不依赖任何外部模型，
 * 是评委现场无论有没有网都能复现的兜底，必须锁死。大模型解析路径不在此测（非确定性）。
 */
class CopilotServiceRuleParseTest {

    /** 规则解析与分类都不触碰注入的依赖，直接以 null 构造被测对象。 */
    private final CopilotService copilot = new CopilotService(null, null, null, null, null);

    private String toolOf(String msg) {
        return copilot.ruleParse(msg).tool();
    }

    // ---------- 只读查询：应直接执行、不需确认 ----------

    @Test
    void riskQuery_isReadOnly() {
        assertEquals("query_risk", toolOf("友谊关现在什么风险"));
        assertTrue(CopilotService.isReadOnly("query_risk"));
    }

    @Test
    void customsQuery_isReadOnly() {
        assertEquals("query_customs", toolOf("口岸通关时效怎么样"));
        assertTrue(CopilotService.isReadOnly("query_customs"));
    }

    @Test
    void planQuery_isReadOnly() {
        assertEquals("plan_routes", toolOf("帮我规划一条去河内的路线"));
        assertTrue(CopilotService.isReadOnly("plan_routes"));
    }

    @Test
    void analysisQuery_isReadOnly() {
        assertEquals("run_analysis", toolOf("跑一下 AI 决策分析给个建议"));
        assertTrue(CopilotService.isReadOnly("run_analysis"));
    }

    // ---------- 写操作：必须走确认闸门，不得只读 ----------

    @Test
    void injectTyphoon_needsConfirm() {
        CopilotService.Intent it = copilot.ruleParse("模拟一场台风演练");
        assertEquals("inject_scenario", it.tool());
        assertEquals("typhoon", it.args().get("scenario"));
        assertFalse(CopilotService.isReadOnly(it.tool()), "注入场景是写操作，必须确认");
    }

    @Test
    void injectHeavyRain_needsConfirm() {
        CopilotService.Intent it = copilot.ruleParse("打开暴雨场景");
        assertEquals("inject_scenario", it.tool());
        assertEquals("rain", it.args().get("scenario"));
    }

    @Test
    void blockWater_needsConfirm() {
        assertEquals("block_water", toolOf("模拟平陆运河禁航"));
        assertFalse(CopilotService.isReadOnly("block_water"));
    }

    @Test
    void resetSandbox_needsConfirm() {
        assertEquals("reset_sandbox", toolOf("重置沙盘撤销所有风险"));
        assertFalse(CopilotService.isReadOnly("reset_sandbox"));
    }

    @Test
    void setRainfall_extractsNumber() {
        CopilotService.Intent it = copilot.ruleParse("把友谊关降雨调到 90mm");
        assertEquals("set_rainfall", it.tool());
        assertEquals(90.0, ((Number) it.args().get("yggMm")).doubleValue(), 0.001,
                "应从句子里抽出友谊关的 90mm");
    }

    // ---------- 未知指令降级为 chat，绝不幻觉出不存在的工具 ----------

    @Test
    void unknown_fallsBackToChat() {
        assertEquals("chat", toolOf("今天天气不错啊"));
        assertTrue(CopilotService.isKnown("chat"));
        assertFalse(CopilotService.isKnown("delete_everything"), "白名单外的工具不得被认可");
    }

    // ---------- 预览文案：写操作确认前必须能翻成人话 ----------

    @Test
    void describe_coversWriteTools() {
        Map<String, Object> rainArgs = Map.of("scenario", "typhoon");
        assertTrue(copilot.describe("inject_scenario", rainArgs).contains("台风"));
        assertTrue(copilot.describe("block_water", Map.of()).contains("禁航"));
        assertTrue(copilot.describe("reset_sandbox", Map.of()).contains("重置"));
    }
}
