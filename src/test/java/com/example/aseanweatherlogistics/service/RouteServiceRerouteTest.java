package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划书阶段三验收标准：
 * "单元测试验证：注入暴雨后路径确实绕开风险路段"
 *
 * 使用真实路网（vietnam-road-network.json）与真实 Spring 上下文，覆盖：
 *  1) 无风险时走基线路径，rerouted=false         —— 基线正确
 *  2) 注入暴雨后 rerouted=true 且新路径不含风险边 —— 计划书验收原文
 *  3) 硬熔断：penalty>=100 的边被排除出结果路径
 *  4) 软惩罚：<100 的边仍可通行（仅放大权重）
 *  5) 极端场景：终点所有入边被硬熔断 -> 降级兜底仍给出可通行路线（resilientPath，不报错停摆）
 *  6) 冷链货损：普通货物不计算货损
 *  7) 清除风险后恢复原路径
 */
@SpringBootTest
class RouteServiceRerouteTest {

    /** 南宁 -> 河内（项目演示主路线） */
    private static final String ORIGIN = "NN";
    private static final String DEST = "HN";
    /** 友谊关口岸通道边（计划书 RAIN_YGG 场景注入目标） */
    private static final String EDGE_FRIENDSHIP = "E4";
    /** 芒街口岸通道边（绕行目标） */
    private static final String EDGE_MONGCAI = "E9";

    @Autowired
    private RouteService routeService;

    @Autowired
    private WeatherSimulator weatherSimulator;

    @Autowired
    private RouteRepository routeRepository;

    @BeforeEach
    void setUp() {
        // 每个用例前清空全部风险，保证用例间互不影响
        weatherSimulator.clearAll();
    }

    private RiskSegment risk(String edgeId, double penalty, String severity) {
        RiskSegment r = new RiskSegment();
        r.setEdgeId(edgeId);
        r.setReason("测试注入");
        r.setSeverity(severity);
        r.setPenaltyMultiplier(penalty);
        return r;
    }

    /** 场景 1：无风险时不应绕行 */
    @Test
    void noRisk_noReroute() {
        RouteResponse resp = routeService.planRoute(ORIGIN, DEST, "normal", "general");

        assertNotNull(resp, "无风险时应正常返回路线");
        assertFalse(resp.isRerouted(), "无风险时不应触发绕行");
        assertEquals(0.0, resp.getExtraHours(), 0.001, "无风险时额外延误应为 0");
        assertEquals(resp.getBaselinePathNodeIds(), resp.getPathNodeIds(), "无风险时当前路径应等同基准路径");
    }

    /**
     * 场景 2（计划书验收标准原文）：
     * 注入暴雨后，路径确实绕开风险路段。
     */
    @Test
    void heavyRain_reroutesAndAvoidsRiskyEdge() {
        routeService.planRoute(ORIGIN, DEST, "normal", "general");

        // 注入暴雨：penalty=100（计划书 3.2：>80mm/h 等效道路中断，且 >=100 触发硬熔断）
        weatherSimulator.injectRisk(risk(EDGE_FRIENDSHIP, 100, "CRITICAL"));

        RouteResponse resp = routeService.planRoute(ORIGIN, DEST, "normal", "general");

        assertNotNull(resp, "注入暴雨后仍应能规划出路线（应绕行而非报错）");
        assertTrue(resp.isRerouted(), "注入暴雨后应触发绕行");
        assertFalse(
                resp.getPathEdgeIds().contains(EDGE_FRIENDSHIP),
                "核心断言：绕行后的路径不应再包含风险边 " + EDGE_FRIENDSHIP
        );
        // 计划书 1.2「双口岸自适应绕行」：友谊关不通时应改用芒街口岸
        assertTrue(
                resp.getPathEdgeIds().contains(EDGE_MONGCAI),
                "友谊关熔断后应自动改走芒街口岸（双口岸自适应绕行）"
        );
        assertTrue(resp.getExtraHours() >= 0, "绕行带来的额外延误应为非负");
    }

    /**
     * 场景 3：两个主要口岸同时硬熔断 -> 不再报“无可用路径”，而是给出降级兜底路线。
     * 行为变更记录：早期版本此处确实抛 IllegalStateException；后来产品明确要求
     * “灾害一来就彻底没有路线会导致前端报错停摆”，改为 resilientPath 两级兜底
     * （熔断边降为软惩罚重搜 → 仍无解退回无风险基线），本用例固化为新行为。
     */
    @Test
    void bothMainCustomsHardBlocked_returnsSoftenedFallback() {
        weatherSimulator.injectRisk(risk(EDGE_FRIENDSHIP, 100, "CRITICAL"));
        weatherSimulator.injectRisk(risk(EDGE_MONGCAI, 100, "CRITICAL"));

        RouteResponse resp = assertDoesNotThrow(
                () -> routeService.planRoute(ORIGIN, DEST, "normal", "general"),
                "双口岸硬熔断时应降级兜底而不是报错停摆");
        assertNotNull(resp.getPathNodeIds(), "兜底路线仍应有完整节点序列");
        assertFalse(resp.getPathNodeIds().isEmpty(), "兜底路线不应为空");
        assertEquals(DEST, resp.getPathNodeIds().get(resp.getPathNodeIds().size() - 1),
                "兜底路线仍应抵达终点");
    }

    /** 场景 4：软惩罚（<100）不删除边，仅放大权重 */
    @Test
    void softPenalty_edgeRemainsAvailable() {
        routeService.planRoute(ORIGIN, DEST, "normal", "general");

        // 大雾 penalty=50（计划书 3.2 芒街场景），属软惩罚，不应导致规划失败
        weatherSimulator.injectRisk(risk(EDGE_MONGCAI, 50, "HIGH"));

        RouteResponse resp = routeService.planRoute(ORIGIN, DEST, "normal", "general");
        assertNotNull(resp, "软惩罚不应导致规划失败");
        assertTrue(resp.getEstimatedHours() > 0, "软惩罚下仍应规划出有效路线");
    }

    /**
     * 场景 5：极端熔断（终点全部相连边）-> 同样走降级兜底而非报错。
     * 做法：切断终点 HN 的所有相连边；resilientPath 把熔断边降为软惩罚后仍可抵达终点，
     * 固化“司机永远不会拿到一条报错线”的产品不变量（理由同上）。
     */
    @Test
    void destinationFullyBlocked_returnsSoftenedFallback() {
        List<RoadEdge> edges = routeRepository.loadRoadNetwork().getEdges();
        long blocked = edges.stream()
                .filter(e -> DEST.equals(e.getToNodeId()) || DEST.equals(e.getFromNodeId()))
                .peek(e -> weatherSimulator.injectRisk(risk(e.getId(), 100, "CRITICAL")))
                .count();
        assertTrue(blocked > 0, "终点应至少有一条相连边可供熔断");

        RouteResponse resp = assertDoesNotThrow(
                () -> routeService.planRoute(ORIGIN, DEST, "normal", "general"),
                "终点所有入边硬熔断时应降级兜底而不是报错");
        assertEquals(DEST, resp.getPathNodeIds().get(resp.getPathNodeIds().size() - 1),
                "兜底路线仍应抵达终点");
    }

    /** 场景 6：冷链货损模型（计划书 5.2）——非冷链货不计算货损 */
    @Test
    void nonColdCargo_noCargoLoss() {
        routeService.planRoute(ORIGIN, DEST, "normal", "general");
        weatherSimulator.injectRisk(risk(EDGE_FRIENDSHIP, 100, "CRITICAL"));

        RouteResponse resp = routeService.planRoute(ORIGIN, DEST, "normal", "general");
        assertEquals(0.0, resp.getCargoLossYuan(), 0.001, "普通货物不应计算冷链货损");
    }

    /** 场景 7：清除风险后应恢复原路径（可重复演示的关键） */
    @Test
    void clearRisk_restoresBaselineRoute() {
        List<String> baselinePath = routeService.planRoute(ORIGIN, DEST, "normal", "general").getPathNodeIds();

        weatherSimulator.injectRisk(risk(EDGE_FRIENDSHIP, 100, "CRITICAL"));
        assertTrue(routeService.planRoute(ORIGIN, DEST, "normal", "general").isRerouted(),
                "注入风险后应绕行");

        weatherSimulator.clearAll();
        RouteResponse restored = routeService.planRoute(ORIGIN, DEST, "normal", "general");

        assertFalse(restored.isRerouted(), "清除风险后应恢复不绕行");
        assertEquals(baselinePath, restored.getPathNodeIds(), "清除风险后应恢复原路径");
    }
}
