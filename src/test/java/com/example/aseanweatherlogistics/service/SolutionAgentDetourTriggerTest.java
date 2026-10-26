package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「灾害打不到司机正在走的这条线，就不许出绕路结论」的固化用例。
 * <p>
 * 复现的缺陷：给芒街注入台风（默认主路线 NN→HN 走的是友谊关，芒街根本不在车上这条路里），
 * 方案生成智能体却把方案A 命名为「公路绕行友谊关」、整轮分析翻成 reroute 模式，
 * 推荐语于是变成「建议绕行友谊关」——绕的还是当前这条线，绕了个寂寞。
 * <p>
 * 判据只认一件事：风险感知路线与无气象基准路线是否不同（{@code RouteResponse.isRerouted()}）。
 * 友谊关熔断（原剧本场景）路线确实被改了，但该结论要分两种形态各自锁住：
 * 未发车（progressRatio &lt; 0）标红＋绕行命名但走卡牌视图；在途才进 reroute 六维增量表。
 */
@SpringBootTest
class SolutionAgentDetourTriggerTest {

    /** 南宁 -> 河内（演示主路线，基准走友谊关） */
    private static final String ORIGIN = "NN";
    private static final String DEST = "HN";
    /** 友谊关口岸通道边 */
    private static final String EDGE_FRIENDSHIP = "E4";
    /** 芒街口岸通道边 */
    private static final String EDGE_MONGCAI = "E9";
    /** 等效熔断（与 DecisionSandboxService.FUSE_PENALTY 同口径） */
    private static final double FUSE = 100.0;

    @Autowired
    private WeatherSimulator weatherSimulator;
    @Autowired
    private RiskAgent riskAgent;
    @Autowired
    private SolutionAgent solutionAgent;

    @BeforeEach
    void setUp() {
        weatherSimulator.clearAll();
    }

    private void fuse(String edgeId) {
        RiskSegment r = new RiskSegment();
        r.setEdgeId(edgeId);
        r.setReason("测试注入：台风");
        r.setSeverity("CRITICAL");
        r.setPenaltyMultiplier(FUSE);
        weatherSimulator.injectRisk(r);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planA(SolutionAgent.SolutionResult s) {
        return ((List<Map<String, Object>>) s.plans()).stream()
                .filter(p -> "A".equals(p.get("id"))).findFirst().orElseThrow();
    }

    /** 常态（无任何灾害）：方案A 是直达友谊关，不进改道模式 */
    @Test
    void noHazard_staysNormal() {
        SolutionAgent.SolutionResult s = solutionAgent.generate(riskAgent.assess(), ORIGIN, DEST, -1);

        Map<String, Object> a = planA(s);
        assertEquals("normal", s.mode(), "无灾害时不应进 reroute");
        assertEquals(Boolean.FALSE, a.get("routeAffected"), "无灾害时本程不应被判为受影响");
        assertFalse(String.valueOf(a.get("name")).contains("绕行"),
                "无灾害时方案名不应出现绕行：" + a.get("name"));
    }

    /**
     * 核心用例：芒街熔断、车在友谊关上 —— 不得触发绕路。
     */
    @Test
    void hazardOnOtherCorridor_doesNotTriggerDetour() {
        fuse(EDGE_MONGCAI);

        SolutionAgent.SolutionResult s = solutionAgent.generate(riskAgent.assess(), ORIGIN, DEST, -1);

        Map<String, Object> a = planA(s);
        String name = String.valueOf(a.get("name"));
        assertEquals(Boolean.FALSE, a.get("routeAffected"),
                "芒街熔断而路线走友谊关时，本程不应被判为受影响");
        assertEquals("normal", s.mode(), "灾害没打到本程这条线时不应进 reroute 模式");
        assertFalse(name.contains("绕行"), "方案A 名称里不能出现「绕行」：" + name);
        assertTrue(name.contains("友谊关"), "方案A 名称应点明本程实际走的走廊：" + name);
        assertEquals(0.0, ((Number) a.get("extraHours")).doubleValue(), 0.001,
                "未绕行就不应有额外耗时");
        // 推荐语必须如实交代"断的是别的走廊"，否则调度员会以为无事发生
        String rec = s.recommendation();
        assertNotNull(rec, "推荐语不应为空");
        assertTrue(rec.contains("芒街") && (rec.contains("不在本程") || rec.contains("无需绕行")),
                "推荐语应说明芒街熔断但本程不受影响：" + rec);
    }

    /** 回归防线（未发车）：友谊关熔断（原剧本场景）仍须把本程标为受影响并绕向芒街，
     * 但模式保持 normal——10-23 引入的【未发车闸门】：progressRatio < 0 时没有“既定原计划”可绕，
     * 六维增量表不成立，应给常态候选卡牌视图（该红的红、AI 推荐最安全的一条）。
     * <p>
     * 本用例写于 10-10、闸门落在 10-23，旧断言写的是闸门前的行为（未发车也要进 reroute），
     * 按现行契约改写：绕行结论一并不丢，只是换个展示形态。
     */
    @Test
    void hazardOnCurrentCorridor_beforeDeparture_marksDetourButStaysCardView() {
        fuse(EDGE_FRIENDSHIP);

        SolutionAgent.SolutionResult s = solutionAgent.generate(riskAgent.assess(), ORIGIN, DEST, -1);

        Map<String, Object> a = planA(s);
        String name = String.valueOf(a.get("name"));
        assertEquals(Boolean.TRUE, a.get("routeAffected"), "本程所在的友谊关被熔断，应判为受影响");
        assertTrue(name.contains("绕行") && name.contains("芒街"),
                "友谊关熔断应绕向芒街，方案名不应失真：" + name);
        assertEquals("normal", s.mode(), "未发车没有原计划可绕，不应出六维增量表（未发车闸门）");
    }

    /**
     * 回归防线（在途）：友谊关熔断且车已在路上（有既定路线被打断）时，必须进 reroute。
     * 锁住【未发车闸门】的另一侧：闸门只管 progressRatio < 0，不能把真该出绕行图的场景一并挡掉。
     */
    @Test
    void hazardOnCurrentCorridor_inTransit_triggersReroute() {
        fuse(EDGE_FRIENDSHIP);

        // 0.3：已发车但未过 BACKWARD_GATE(0.5)，联运方案仍在牌桌上
        SolutionAgent.SolutionResult s = solutionAgent.generate(riskAgent.assess(), ORIGIN, DEST, 0.3);

        Map<String, Object> a = planA(s);
        String name = String.valueOf(a.get("name"));
        assertEquals(Boolean.TRUE, a.get("routeAffected"), "在途且友谊关熔断，应判为受影响");
        assertEquals("reroute", s.mode(), "友谊关熔断＋车在途，必须仍是改道模式");
        assertTrue(name.contains("绕行") && name.contains("芒街"),
                "友谊关熔断应绕向芒街，方案名不应失真：" + name);
    }
}
