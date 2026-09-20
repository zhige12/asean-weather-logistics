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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 *  5) 极端场景：终点所有入边被硬熔断 -> 明确抛"无可用路径"，而非返回荒谬时长
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
     * 场景 3：两个主要口岸同时硬熔断 -> 明确报"无可用路径"。
     * 说明：路网中虽有河口口岸 E36，但源 PBF 仅含越南数据，中国侧节点为硬编码稀疏节点，
     * 南宁到河口无中国境内公路（需绕道越南经老街反向入境），故两口岸同时熔断时确实无路可走。
     * 该用例固化这一真实边界，避免误以为存在第三条可用通道。
     */
    @Test
    void bothMainCustomsHardBlocked_throwsNoAvailablePath() {
        weatherSimulator.injectRisk(risk(EDGE_FRIENDSHIP, 100, "CRITICAL"));
        weatherSimulator.injectRisk(risk(EDGE_MONGCAI, 100, "CRITICAL"));

        assertThrows(IllegalStateException.class,
                () -> routeService.planRoute(ORIGIN, DEST, "normal", "general"),
                "友谊关与芒街同时硬熔断时应明确抛出无可用路径异常");
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
     * 场景 5：极端熔断 -> 明确报"无可用路径"，而非返回荒谬时长。
     * 做法：切断终点 HN 的所有相连边，使其在图论上不可达（比赌某个口岸必然经过更严谨）。
     */
    @Test
    void destinationFullyBlocked_throwsNoAvailablePath() {
        List<RoadEdge> edges = routeRepository.loadRoadNetwork().getEdges();
        long blocked = edges.stream()
                .filter(e -> DEST.equals(e.getToNodeId()) || DEST.equals(e.getFromNodeId()))
                .peek(e -> weatherSimulator.injectRisk(risk(e.getId(), 100, "CRITICAL")))
                .count();
        assertTrue(blocked > 0, "终点应至少有一条相连边可供熔断");

        assertThrows(IllegalStateException.class,
                () -> routeService.planRoute(ORIGIN, DEST, "normal", "general"),
                "终点所有入边硬熔断后，应明确抛出无可用路径异常");
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
