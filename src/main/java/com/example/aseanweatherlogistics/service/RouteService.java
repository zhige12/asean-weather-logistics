package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.dto.RouteRequest;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.repository.RouteRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RouteService {
    private static final double DEFAULT_SPEED_KMH = 55.0;
    /**
     * 硬切断阈值：风险惩罚系数达到该值视为「物理中断」，该边不参与路径搜索（真正绕不过去）。
     * 取 100 的依据与计划书一致：>80mm/h 暴雨 -> 100 即「等效道路中断」，
     * 且 WeatherSimulator.severityFor(100) 正好判为 CRITICAL；
     * 而大雾 50 / 台风 80 / 高温 10 仍按软惩罚处理（可通行但显著变慢，允许权衡后通过）。
     */
    private static final double IMPASSABLE_PENALTY = 100.0;
    /**
     * 硬熔断边「降级兜底」后的软惩罚系数：当所有走廊都被硬熔断时，把 100+ 降为 50
     * 再搜一次，保证仍能给出"虽然危险但可通行"的方案，而不是直接报错让司机停在原地。
     */
    private static final double SOFTENED_PENALTY = 50.0;
    /** 候选路线总数上限：灾害叠加时也要保证有足够可选项（司机端可滚动选择） */
    private static final int MAX_CANDIDATES = 10;
    /** 候选去重阈值：边集合 Jaccard 相似度高于该值视为同一条路线 */
    private static final double SIMILARITY_THRESHOLD = 0.85;
    /** 候选池内部去重阈值（更宽松，仅剔除近乎重复项，保留相似走廊供外层自适应筛选） */
    private static final double POOL_DEDUP_THRESHOLD = 0.98;

    private final RouteRepository routeRepository;
    private final WeatherSimulator weatherSimulator;
    private final CustomsEfficiencyService customsEfficiencyService;
    private final RealWeatherService realWeatherService;

    /** 冷链示例货值（元）：计划书 5.2 货损模型基数（约 18 吨火龙果/凤梨） */
    private static final double CARGO_VALUE_YUAN = 100_000;
    /** 最近一次 planRoute 的货类型，供 Agent 毫秒级重算(planRouteFast)沿用，冷链货损计算需要 */
    private volatile String lastCargoType;

    private Map<String, RoadNode> nodeById = Map.of();
    private List<RoadEdge> edges = List.of();
    private Map<String, List<RoadEdge>> adjacency = Map.of();
    /** 边 ID -> 边 的哈希索引：路径重建/里程统计从线性扫描 O(E) 降为 O(1) */
    private Map<String, RoadEdge> edgeById = Map.of();
    /** 邻接表默认空桶，避免每次 getOrDefault 新建空 List */
    private static final List<RoadEdge> NO_EDGES = List.of();

    private static final long REAL_SYNC_TTL_MS = 5 * 60_000L;
    private volatile long lastRealSyncAt = 0;

    /** 多核并行规划线程池：baseline/current 两条 Dijkstra 并行跑（9800X3D 8核16线程） */
    private final ExecutorService planExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "plan-worker");
                t.setDaemon(true);
                return t;
            });

    public RouteService(RouteRepository routeRepository, WeatherSimulator weatherSimulator,
                        CustomsEfficiencyService customsEfficiencyService,
                        RealWeatherService realWeatherService) {
        this.routeRepository = routeRepository;
        this.weatherSimulator = weatherSimulator;
        this.customsEfficiencyService = customsEfficiencyService;
        this.realWeatherService = realWeatherService;
    }

    @PostConstruct
    public void init() {
        RouteRepository.RoadNetworkData data = routeRepository.loadRoadNetwork();
        this.nodeById = data.getNodes().stream().collect(Collectors.toMap(RoadNode::getId, n -> n));
        this.edges = data.getEdges();
        this.edgeById = edges.stream().collect(Collectors.toMap(RoadEdge::getId, e -> e));
        Map<String, List<RoadEdge>> adjacencyBuilder = new HashMap<>();
        for (RoadEdge edge : edges) {
            adjacencyBuilder.computeIfAbsent(edge.getFromNodeId(), ignored -> new ArrayList<>()).add(edge);
            adjacencyBuilder.computeIfAbsent(edge.getToNodeId(), ignored -> new ArrayList<>()).add(reverse(edge));
        }
        this.adjacency = adjacencyBuilder;
        // 中国侧为手工建模的虚拟走廊（无 OSM 源几何），补上贴合真实高速/国道的走向折线，
        // 否则司机端画线在城市间直连（看似"穿山"）；越南侧 OSM 抽稀边已自带 c 几何
        patchVirtualChinaEdges();
        // 注册路径解析器：供 WeatherSimulator 做"整段路径"灾害注入（如谅山—北江）
        weatherSimulator.setPathResolver(this::edgesOnPath);
    }

    /** 给缺少几何的中国侧虚拟边补 c（OSM 真实道路几何见 CNRoadGeom，仅内存补全、不改路网文件） */
    private void patchVirtualChinaEdges() {
        for (RoadEdge e : this.edges) {
            if (e.getC() == null || e.getC().size() < 2) {
                double[][] geom = CNRoadGeom.MAP.get(e.getId());
                if (geom != null) {
                    List<List<Double>> c = new ArrayList<>(geom.length);
                    for (double[] p : geom) {
                        c.add(List.of(p[0], p[1]));
                    }
                    e.setC(c);
                }
            }
        }
    }

    /** 返回无风险条件下 from -> to 最短路径经过的边 ID（供场景整段注入） */
    public List<String> edgesOnPath(String fromNodeId, String toNodeId) {
        PathResult r = shortestPath(fromNodeId, toNodeId, Map.of());
        return r.pathEdgeIds();
    }

    private RoadEdge reverse(RoadEdge e) {
        return new RoadEdge(e.getId(), e.getToNodeId(), e.getFromNodeId(), e.getDistanceKm(),
                e.getSpeedKmh(), e.isCustoms(), e.getCustomsDelayHours(), e.getName(), e.getRoadType(),
                e.getC());
    }

    public List<RoadNode> getAllNodes() {
        return new ArrayList<>(nodeById.values());
    }

    public List<RoadEdge> getAllEdges() {
        return new ArrayList<>(edges);
    }

    public RoadNode getNode(String nodeId) {
        return nodeById.get(nodeId);
    }

    public RouteResponse planRoute(RouteRequest request) {
        return planRoute(request.getOriginId(), request.getDestinationId(),
                request.getPeriod(), request.getCargoType());
    }

    public RouteResponse planRoute(String originId, String destinationId) {
        return planRoute(originId, destinationId, null, null);
    }

    public RouteResponse planRoute(String originId, String destinationId, String period, String cargoType) {
        if (period != null) {
            customsEfficiencyService.setActivePeriod(period);
        }
        if (cargoType != null) {
            customsEfficiencyService.setActiveCargoType(cargoType);
        }
        this.lastCargoType = cargoType;
        validate(originId, destinationId);
        // 先算无风险基准路径，再按"起点→终点沿线城市"拉取实时天气并注入风险，
        // 最后用带风险的路网算当前路径（气象只关注沿线城市，不再全量拉取）
        PathResult baseline = joinOrThrow(CompletableFuture.supplyAsync(
                () -> shortestPath(originId, destinationId, Map.of()), planExecutor));
        syncRealWeather(false, baseline.pathNodeIds);
        PathResult current = computeCurrentPath(originId, destinationId, baseline);
        return buildResponse(baseline, current, lastCargoType);
    }

    /**
     * Agent 实时守护用毫秒级重算：不重新拉天气（天气走 TTL 缓存），
     * 直接用当前风险图并行跑双 Dijkstra，突发灾害后「马上」产出新路线。
     */
    public RouteResponse planRouteFast(String originId, String destinationId) {
        validate(originId, destinationId);
        PathResult baseline = joinOrThrow(CompletableFuture.supplyAsync(
                () -> shortestPath(originId, destinationId, Map.of()), planExecutor));
        PathResult current = computeCurrentPath(originId, destinationId, baseline);
        return buildResponse(baseline, current, lastCargoType);
    }

    // ==================== 多路线候选（司机端"多条路线→自选→导航"）====================

    /**
     * 为 起点→终点 生成多条互不重复的候选路线（最多 {@link #MAX_CANDIDATES} 条），供司机端自行选择：
     *   1. recommended 推荐路线：实时气象风险感知（默认选中）；
     *   2. alternate-N  备选路线：多比例禁边 / 强制经由不同中间节点 / 局部权重扰动 / 强制不同口岸，尽量给出不同走廊；
     *   3. fastest      最快路线：纯时间最短、无视气象减速（明知有风险也要走时的出口）。
     *
     * 灾害叠加时不会「无路可走就停摆」：若所有走廊都被硬熔断，会把熔断边降级为软惩罚再搜一次，
     * 至少给出一条"可通行但含风险路段"的方案，由司机自行权衡。
     */
    public List<Map<String, Object>> planCandidates(String originId, String destinationId, String period, String cargoType) {
        if (period != null) {
            customsEfficiencyService.setActivePeriod(period);
        }
        if (cargoType != null) {
            customsEfficiencyService.setActiveCargoType(cargoType);
        }
        this.lastCargoType = cargoType;
        validate(originId, destinationId);

        PathResult baseline = shortestPath(originId, destinationId, Map.of());
        syncRealWeather(false, baseline.pathNodeIds);
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();

        List<Map<String, Object>> out = new ArrayList<>();
        for (CandidatePath c : buildCandidates(originId, destinationId, baseline, riskMap)) {
            out.add(toCandidate(c.key, c.label, c.path, riskMap, c.note));
        }
        return out;
    }

    /**
     * 统一的候选路线生成（含去重与兜底），planCandidates 与 planRouteByChoice 共用，
     * 保证司机端「预览看到的那条」与「点选后重算出来的那条」完全一致。
     * 返回顺序即展示顺序，第 0 条固定为推荐路线。
     */
    private List<CandidatePath> buildCandidates(String originId, String destinationId,
                                                PathResult baseline, Map<String, RiskSegment> riskMap) {
        // 推荐路线：风险感知；若已被全线熔断，自动降级（软惩罚 -> 无风险基线），绝不抛错
        ResilientPath rec = resilientPath(originId, destinationId, riskMap, baseline);
        // 备选搜索沿用"能搜出路"的那张风险图，避免备选全军覆没
        Map<String, RiskSegment> searchRiskMap = rec.softened ? softenRiskMap(riskMap) : riskMap;

        // 推荐路线若为了避风险绕出明显更长的时效（例如只能走滞留严重的口岸），标签点明"稳妥优先"，
        // 让司机一眼看到下方还有更快的选择，而不是被一条超长路线劝退。
        boolean slowTradeoff = rec.path.hours() > baseline.hours() * 1.5 + 2.0;

        List<CandidatePath> out = new ArrayList<>();

        // ---- 推荐路线的标签与说明按「实际情况」生成 ----
        // 之前无论有没有风险、有没有绕行，这里永远是「推荐路线 / 实时气象风险最低」，
        // 大屏上就是一张雷打不动的静态卡片；无风险时"风险最低"更是不知所云（没有风险可最低）。
        // 现在按实况分四种措辞：全线通畅 / 已避开风险改走他处 / 风险避不开 / 兜底或稳妥优先。
        long recRisks = countRisksOnPath(rec.path.pathEdgeIds(), riskMap);
        long baseRisks = countRisksOnPath(baseline.pathEdgeIds(), riskMap);
        String recPort = portLabelOf(rec.path);
        String basePort = portLabelOf(baseline);
        // 基准路线有风险、推荐路线没有 → 推荐是为了避险换了走廊
        boolean avoidedRisk = baseRisks > 0 && recRisks == 0;
        // 出境地换了口岸（如友谊关熔断改走芒街）
        boolean switchedPort = recPort != null && basePort != null && !recPort.equals(basePort);
        // 说明句里口岸用括号补语，避免和前半句直接粘在一起（"…一致经友谊关口岸"读不通）
        String viaPort = recPort == null ? "" : "（经" + recPort + "）";
        double extraH = rec.path.hours() - baseline.hours();

        String recLabel;
        String recNote;
        if (rec.softened) {
            // 主通道已中断：硬熔断降级为软惩罚得到的兜底路径
            recLabel = "推荐路线（谨慎通行）";
            recNote = "主通道已中断，此为当前仍可通行方案"
                    + (recRisks > 0 ? "，含 " + recRisks + " 处风险路段请减速慢行" : "");
        } else if (avoidedRisk) {
            // 有风险且已避开：说清"避开了几处、改走哪里、多花多久"
            recLabel = "推荐路线（已避开风险）";
            recNote = "已避开主通道 " + baseRisks + " 处风险路段"
                    + (switchedPort ? "，改走" + recPort : "")
                    + (extraH > 0.2 ? "，比最快路线多约 " + Math.round(extraH) + "h" : "");
        } else if (recRisks > 0) {
            // 风险在推荐路线上且绕不开：如实告知，不粉饰
            recLabel = "推荐路线（谨慎通行·含风险路段）";
            recNote = "沿线 " + recRisks + " 处风险路段无法绕开" + viaPort + "，请减速慢行";
        } else if (slowTradeoff) {
            recLabel = "推荐路线（稳妥优先·时效偏长）";
            recNote = "沿线无气象风险，但比最快路线多约 " + Math.round(extraH) + "h" + viaPort + "，可按需权衡";
        } else {
            // 全线通畅：不再说"风险最低"
            recLabel = "推荐路线（全线通畅）";
            recNote = "沿线暂无气象风险，时效与最快路线一致" + viaPort;
        }
        out.add(new CandidatePath("recommended", recLabel, recNote, rec.path, rec.softened));

        // 多级绕行产出候选池；阈值先严后宽，尽量凑够可选条数（同一走廊的重复项始终会被剔除）
        List<PathResult> pool = findMultiAlternates(originId, destinationId, searchRiskMap, rec.path);
        double[] thresholds = {SIMILARITY_THRESHOLD, 0.75, 0.6};
        // 已用路径以推荐路线打底：备选与推荐过于相似时直接丢弃，避免"备选其实就是推荐"
        List<PathResult> picked = new ArrayList<>();
        picked.add(rec.path);
        for (double th : thresholds) {
            for (PathResult alt : pool) {
                if (picked.size() >= MAX_CANDIDATES) break;
                if (containsSimilar(alt, picked, th)) continue;
                picked.add(alt);
            }
            if (picked.size() >= MAX_CANDIDATES) break;
        }
        // 「最快路线」：纯时间最短、无视风险（也是灾害下不愿绕行时的兜底出口）
        if (picked.size() < MAX_CANDIDATES && !containsSimilar(baseline, picked, SIMILARITY_THRESHOLD)) {
            picked.add(baseline);
        }
        // 按预计时效升序：司机先看到「又快又稳」的方案，异常漫长的绕行排到最后，不会被误选
        picked.sort(Comparator.comparingDouble(PathResult::hours));

        // 推荐路线固定在首位；池中若再次出现推荐路线（强制经由/口岸权重等策略可能搜回原路线）则丢弃。
        // 这里用近重复阈值兜底，既能剔除同一路线，又不会抹掉上面刻意放宽得到的不同走廊。
        List<PathResult> ordered = new ArrayList<>();
        ordered.add(rec.path);
        for (PathResult p : picked) {
            if (p == rec.path || containsSimilar(p, ordered, POOL_DEDUP_THRESHOLD)) continue;
            ordered.add(p);
        }

        int idx = 0;
        for (int i = 1; i < ordered.size(); i++) {
            PathResult p = ordered.get(i);
            boolean isFastest = p == baseline;
            long risks = countRisksOnPath(p.pathEdgeIds(), riskMap);
            String key;
            String label;
            String note;
            if (isFastest) {
                key = "fastest";
                label = "最快路线";
                note = risks > 0
                        ? "忽略天气减速，时间最短但需自担风险（含 " + risks + " 处风险路段）"
                        : "忽略天气减速，时间最短";
            } else {
                idx++;
                key = "alternate-" + idx;
                label = "备选路线 " + (char) ('A' + Math.min(idx - 1, 25));
                note = risks > 0
                        ? "绕开主通道，经另一走廊抵达（含 " + risks + " 处风险路段）"
                        : "绕开主通道，经另一走廊抵达";
            }
            out.add(new CandidatePath(key, label, note, p, rec.softened));
        }
        return out;
    }

    /** 与候选路径集合比较：存在相似度超过阈值的路线即视为重复 */
    private boolean containsSimilar(PathResult p, List<PathResult> chosen, double threshold) {
        if (p == null) {
            return true;
        }
        for (PathResult existing : chosen) {
            if (similarity(p.pathEdgeIds(), existing.pathEdgeIds()) > threshold) {
                return true;
            }
        }
        return false;
    }

    /** 候选路线（键 + 展示文案 + 路径），键用于 driver 端回传 choice */
    private record CandidatePath(String key, String label, String note, PathResult path, boolean softened) {
    }

    /** 路线 + 是否经过「硬熔断降级」才找到（用于给司机端提示风险等级） */
    private record ResilientPath(PathResult path, boolean softened) {
    }

    /**
     * 风险感知路径 + 两级兜底，保证灾害叠加时也不抛异常：
     *   1. 正常按风险图搜索；
     *   2. 全线被硬熔断时，把熔断边降级为软惩罚（{@link #SOFTENED_PENALTY}）再搜一次；
     *   3. 仍无解（图本身不连通）则退回无风险基线。
     */
    private ResilientPath resilientPath(String originId, String destinationId,
                                        Map<String, RiskSegment> riskMap, PathResult baseline) {
        try {
            return new ResilientPath(shortestPath(originId, destinationId, riskMap), false);
        } catch (IllegalStateException primary) {
            Map<String, RiskSegment> softened = softenRiskMap(riskMap);
            if (!softened.isEmpty()) {
                try {
                    return new ResilientPath(shortestPath(originId, destinationId, softened), true);
                } catch (IllegalStateException ignored) {
                    // 连降级后都搜不出（图本身不连通），落到基线
                }
            }
            return new ResilientPath(baseline, true);
        }
    }

    /** 把硬熔断边（penalty >= 100）降级为软惩罚，保留"可通行"语义，仅供兜底搜索使用 */
    private Map<String, RiskSegment> softenRiskMap(Map<String, RiskSegment> riskMap) {
        Map<String, RiskSegment> soft = new HashMap<>(Math.max(16, riskMap.size()));
        for (Map.Entry<String, RiskSegment> en : riskMap.entrySet()) {
            RiskSegment s = en.getValue();
            if (s == null) {
                continue;
            }
            if (isImpassable(s)) {
                double p = s.getPenaltyMultiplier() == null
                        ? SOFTENED_PENALTY
                        : Math.min(s.getPenaltyMultiplier(), SOFTENED_PENALTY);
                soft.put(en.getKey(), new RiskSegment(s.getEdgeId(), s.getReason(), s.getSeverity(), p));
            } else {
                soft.put(en.getKey(), s);
            }
        }
        return soft;
    }

    /**
     * 司机选定候选后按偏好重算完整路线（导航轮询保持一致）。
     * choice: recommended=实时风险感知（默认）；fastest=纯时间基准；alternate=绕行走廊。
     */
    public RouteResponse planRouteByChoice(String originId, String destinationId, String period, String cargoType, String choice) {
        if (period != null) {
            customsEfficiencyService.setActivePeriod(period);
        }
        if (cargoType != null) {
            customsEfficiencyService.setActiveCargoType(cargoType);
        }
        this.lastCargoType = cargoType;
        validate(originId, destinationId);
        PathResult baseline = shortestPath(originId, destinationId, Map.of());
        syncRealWeather(false, baseline.pathNodeIds);
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        PathResult chosen;
        boolean softened = false;
        if ("fastest".equals(choice)) {
            chosen = baseline; // 最快 = 无风险纯时间基准（沿线风险段仍会标注，供司机权衡）
        } else {
            // 与 planCandidates 复用同一套候选生成，按 key 精确取回司机所选的同一条路线
            List<CandidatePath> candidates = buildCandidates(originId, destinationId, baseline, riskMap);
            CandidatePath picked = null;
            for (CandidatePath c : candidates) {
                if (c.key.equals(choice)) {
                    picked = c;
                    break;
                }
            }
            if (picked == null) {
                picked = candidates.get(0); // choice 缺失/失效时回落到推荐路线，而不是报错
            }
            chosen = picked.path;
            softened = picked.softened;
        }
        RouteResponse resp = buildResponse(baseline, chosen, cargoType);
        if (softened) {
            // 兜底方案里含「已熔断但被降级放行」的边，必须留痕提示
            String warn = "部分路段已硬熔断，当前为可通行兜底方案，请减速慢行";
            resp.setDelayReason(resp.getDelayReason() == null || resp.getDelayReason().isBlank()
                    ? warn : resp.getDelayReason() + "；" + warn);
        }
        if (!"recommended".equals(choice)) {
            // 司机"主动选择"最快/备选路线 ≠ 灾害自动熔断绕行：
            // 重置 rerouted 标记，避免司机端误弹"已熔断·自动绕行"红色告警
            resp.setRerouted(false);
        }
        return resp;
    }

    /** 多级绕行：多比例禁边 + 强制经由中间节点 + 局部权重扰动 + 强制不同口岸，最大限度生成不同走廊备选 */
    private List<PathResult> findMultiAlternates(String originId, String destinationId,
                                                  Map<String, RiskSegment> riskMap, PathResult base) {
        List<PathResult> results = new ArrayList<>();
        List<String> ids = base.pathEdgeIds();
        List<String> nodes = base.pathNodeIds();
        int n = ids.size();
        if (n < 2) return results;

        // 策略组 1: 禁走推荐路线全部边（找完全不同的走廊）
        addIfNew(results, tryAlternate(originId, destinationId, riskMap, new HashSet<>(ids)));

        // 策略组 2: 多种比例禁走前缀（从起点侧绕开）——比例越细，越容易凑出不同走廊
        for (double ratio : new double[]{0.3, 0.5, 0.2, 0.7, 0.4, 0.6}) {
            int cut = Math.max(1, Math.min(n - 1, (int) Math.round(n * ratio)));
            addIfNew(results, tryAlternate(originId, destinationId, riskMap, new HashSet<>(ids.subList(0, cut))));
        }

        // 策略组 3: 多种比例禁走后缀（从终点侧绕开）
        for (double ratio : new double[]{0.3, 0.5, 0.2, 0.4}) {
            int cut = Math.max(1, Math.min(n - 1, (int) Math.round(n * ratio)));
            addIfNew(results, tryAlternate(originId, destinationId, riskMap, new HashSet<>(ids.subList(n - cut, n))));
        }

        // 策略组 4: 两端同时禁走（保留中段），逼出"两头都换走廊"的方案
        for (double ratio : new double[]{0.3, 0.45}) {
            int cut = Math.max(1, Math.min(n / 2, (int) Math.round(n * ratio)));
            Set<String> combo = new HashSet<>(ids.subList(0, cut));
            combo.addAll(ids.subList(n - cut, n));
            addIfNew(results, tryAlternate(originId, destinationId, riskMap, combo));
        }

        // 策略组 5: 强制经由推荐路径上的中间节点（每 ~12% 采一个），逼出"同口岸不同走廊"的变体
        int step = Math.max(1, n / 8);
        for (int i = step; i < n; i += step) {
            addIfNew(results, pathVia(originId, nodes.get(i), destinationId, riskMap));
        }

        // 策略组 6: 局部权重扰动（把路径中段的边权放大），让算法自行寻找局部替代走廊
        int[][] perturbRanges = {{(int) (n * 0.15), (int) (n * 0.85)}, {(int) (n * 0.3), (int) (n * 0.7)}};
        for (int[] range : perturbRanges) {
            Map<String, Double> perturb = new HashMap<>();
            for (int i = Math.max(0, range[0]); i < Math.min(n, range[1]); i++) {
                perturb.put(ids.get(i), 8.0);
            }
            if (!perturb.isEmpty()) {
                addIfNew(results, tryAlternate(originId, destinationId, riskMap, Set.of(), perturb));
            }
        }

        // 策略组 7: 强行走不同口岸。中越边境三大口岸：友谊关(E4)、芒街(E9)、河口(E36)
        // 惩罚其他口岸关联边、吸引目标口岸边，迫使路径改走指定口岸
        String[] portNodes = {"E4", "E9", "E36"};
        Set<String> basePorts = new HashSet<>();
        for (String pn : portNodes) {
            if (nodes.contains(pn)) basePorts.add(pn);
        }
        for (String targetPort : portNodes) {
            if (basePorts.contains(targetPort) && basePorts.size() <= 1) continue; // 唯一口岸，无需绕
            Map<String, Double> portWeights = new HashMap<>();
            for (RoadEdge e : edges) {
                String from = e.getFromNodeId();
                String to = e.getToNodeId();
                boolean touchesTarget = from.equals(targetPort) || to.equals(targetPort)
                        || (e.getName() != null && e.getName().contains(targetPort));
                if (touchesTarget) {
                    portWeights.put(e.getId(), 0.5); // 目标口岸：减权吸引
                    continue;
                }
                for (String pn : portNodes) {
                    if (from.equals(pn) || to.equals(pn) || (e.getName() != null && e.getName().contains(pn))) {
                        portWeights.put(e.getId(), portWeights.getOrDefault(e.getId(), 1.0) * 5.0);
                        break;
                    }
                }
            }
            addIfNew(results, tryAlternate(originId, destinationId, riskMap, Set.of(), portWeights));
        }

        return results;
    }

    /** 加入候选池前去重：仅剔除近乎重复的方案，保留"相似但不同走廊"的备选供外层自适应筛选 */
    private void addIfNew(List<PathResult> results, PathResult r) {
        if (r == null) return;
        for (PathResult existing : results) {
            if (similarity(r.pathEdgeIds(), existing.pathEdgeIds()) > POOL_DEDUP_THRESHOLD) {
                return;
            }
        }
        results.add(r);
    }

    /** 强制经由指定中间节点：origin→via 与 via→dest 拼接（衔接点去重）；via 不可达时返回 null */
    private PathResult pathVia(String originId, String viaId, String destinationId, Map<String, RiskSegment> riskMap) {
        if (viaId == null || viaId.equals(originId) || viaId.equals(destinationId)) return null;
        try {
            PathResult a = shortestPath(originId, viaId, riskMap);
            PathResult b = shortestPath(viaId, destinationId, riskMap);
            List<String> nodeIds = new ArrayList<>(a.pathNodeIds());
            for (int i = 1; i < b.pathNodeIds().size(); i++) {
                nodeIds.add(b.pathNodeIds().get(i));
            }
            List<String> edgeIds = new ArrayList<>(a.pathEdgeIds());
            edgeIds.addAll(b.pathEdgeIds());
            return new PathResult(nodeIds, edgeIds,
                    a.totalDistanceKm() + b.totalDistanceKm(), a.hours() + b.hours());
        } catch (IllegalStateException e) {
            return null; // 该中间节点在当前风险下不可达
        }
    }

    private PathResult tryAlternate(String originId, String destinationId, Map<String, RiskSegment> riskMap,
                                    Set<String> forbidden) {
        try {
            return shortestPath(originId, destinationId, riskMap, forbidden);
        } catch (IllegalStateException e) {
            return null; // 禁走后无可用路径（该走廊为唯一通道）
        }
    }

    /** 带边权重的绕行：forbidden 为空时不额外禁边，weightOverrides 对指定边乘系数 */
    private PathResult tryAlternate(String originId, String destinationId, Map<String, RiskSegment> riskMap,
                                    Set<String> forbidden, Map<String, Double> weightOverrides) {
        try {
            return shortestPath(originId, destinationId, riskMap, forbidden, weightOverrides);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 边集合 Jaccard 相似度：0 完全不同，1 完全相同。用于候选去重/过滤冗余路线 */
    private double similarity(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        int inter = 0;
        for (String x : sa) {
            if (sb.contains(x)) {
                inter++;
            }
        }
        return (double) inter / Math.max(1, sa.size() + sb.size() - inter);
    }

    private Map<String, Object> toCandidate(String key, String label, PathResult p,
                                            Map<String, RiskSegment> riskMap, String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("note", note);
        m.put("via", routeVia(p.pathNodeIds()));
        m.put("hours", round(p.hours()));
        m.put("distanceKm", round(p.totalDistanceKm()));
        m.put("riskCount", countRisksOnPath(p.pathEdgeIds(), riskMap));
        // 逐段风险边 ID：司机端按段着色（风险段红 / 安全段绿）
        m.put("riskEdgeIds", riskEdgesOnPath(p.pathEdgeIds(), riskMap));
        m.put("nodeIds", p.pathNodeIds());
        m.put("edgeIds", p.pathEdgeIds());
        List<int[]> spans = new ArrayList<>();
        m.put("coords", toCoords(p.pathNodeIds(), p.pathEdgeIds(), spans));
        m.put("edgeSpans", spans);
        return m;
    }

    /** 途经摘要：取路径上全部中文名城市/口岸，压缩为前 6 个展示（排除起终点自身） */
    private String routeVia(List<String> nodeIds) {
        List<String> named = new ArrayList<>();
        for (String id : nodeIds) {
            RoadNode n = nodeById.get(id);
            if (n == null || n.getName() == null || n.getName().isBlank()) {
                continue;
            }
            String name = n.getName().trim();
            if (name.equals("南宁") || name.equals("河内")) {
                continue;
            }
            named.add(name);
        }
        if (named.isEmpty()) {
            return "直达";
        }
        List<String> via = named.size() <= 6 ? named : named.subList(0, 6);
        return String.join(" → ", via) + (named.size() > 6 ? " …" : "");
    }

    /** 某条路径上命中的有效风险段条数（用于候选卡片"X 处风险"角标） */
    private long countRisksOnPath(List<String> edgeIds, Map<String, RiskSegment> riskMap) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return 0;
        }
        return edgeIds.stream().filter(eid -> {
            RiskSegment r = riskMap.get(eid);
            return r != null && r.getPenaltyMultiplier() != null && r.getPenaltyMultiplier() > 1.0;
        }).count();
    }

    /**
     * 路径的出境地口岸名（如「友谊关口岸」），用于推荐路线的说明文案。
     * 取路径上**最后一条**口岸虚拟边（出境地）；无口岸边则返回 null。
     * 边名形如「友谊关口岸通道」，这里去掉"通道/通行/通关"只留地名。
     */
    private String portLabelOf(PathResult p) {
        if (p == null || p.pathEdgeIds() == null) {
            return null;
        }
        String name = null;
        for (String eid : p.pathEdgeIds()) {
            RoadEdge e = edgeById.get(eid);
            if (e != null && e.isCustoms() && e.getName() != null && !e.getName().isBlank()) {
                name = e.getName();
            }
        }
        return name == null ? null
                : name.replace("通道", "").replace("通行", "").replace("通关", "").trim();
    }

    /** 某条路径上命中风险的边 ID 列表（供司机端按段着色：风险段红 / 安全段绿） */
    private List<String> riskEdgesOnPath(List<String> edgeIds, Map<String, RiskSegment> riskMap) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String eid : edgeIds) {
            RiskSegment r = riskMap.get(eid);
            if (r != null && r.getPenaltyMultiplier() != null && r.getPenaltyMultiplier() > 1.0) {
                out.add(eid);
            }
        }
        return out;
    }

    /**
     * 用当前风险图（weatherSimulator.currentRiskMap）算当前路径。
     * 全线硬熔断时走 resilientPath 的两级兜底，避免「灾害一来就彻底没有路线」导致前端报错停摆。
     */
    private PathResult computeCurrentPath(String originId, String destinationId, PathResult baseline) {
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        CompletableFuture<PathResult> currentFuture = CompletableFuture.supplyAsync(
                () -> resilientPath(originId, destinationId, riskMap, baseline).path, planExecutor);
        return joinOrThrow(currentFuture);
    }

    /** 组装路线响应：当前路径 vs 无风险基准路径，计算 ETA、碳排放、货损 */
    private RouteResponse buildResponse(PathResult baseline, PathResult current, String cargoType) {
        boolean rerouted = !baseline.pathNodeIds.equals(current.pathNodeIds);
        RouteResponse resp = new RouteResponse();
        resp.setPathNodeIds(current.pathNodeIds);
        resp.setPathEdgeIds(current.pathEdgeIds);
        resp.setTotalDistanceKm(round(current.totalDistanceKm));
        resp.setEstimatedHours(round(current.hours));
        resp.setRerouted(rerouted);
        resp.setRiskSegments(weatherSimulator.currentRisks());
        resp.setBaselinePathNodeIds(baseline.pathNodeIds);
        resp.setBaselineHours(round(baseline.hours));
        resp.setCurrentHours(round(current.hours));
        double delayHours = Math.max(0, current.hours - baseline.hours);
        resp.setExtraHours(round(delayHours));
        resp.setCargoLossYuan(round(calculateCargoLoss(cargoType, delayHours, current.pathNodeIds)));
        List<int[]> pathSpans = new ArrayList<>();
        resp.setPathCoords(toCoords(current.pathNodeIds, current.pathEdgeIds, pathSpans));
        resp.setPathEdgeSpans(pathSpans);
        // 基准路径同样下发"边 ID + 边区间"：大屏可把原路线中穿过风险区的段标红，直观对比绕行
        List<int[]> baselineSpans = new ArrayList<>();
        resp.setBaselinePathCoords(toCoords(baseline.pathNodeIds, baseline.pathEdgeIds, baselineSpans));
        resp.setBaselinePathEdgeIds(baseline.pathEdgeIds);
        resp.setBaselinePathEdgeSpans(baselineSpans);

        // ETA 智能预估：当前时间 + 预计行驶时长（含天气/通关惩罚）
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.ZonedDateTime eta = now.plusMinutes(Math.round(current.hours * 60));
        resp.setEstimatedArrival(eta.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        // 延迟原因说明
        if (delayHours > 0.5) {
            String reason = buildDelayReason(delayHours, current.pathEdgeIds);
            resp.setDelayReason(reason);
        }

        // 碳排放估算：距离 × 油耗率 × 排放因子
        resp.setCarbonEmissionKg(round(calculateCarbonEmission(current.totalDistanceKm)));

        // 风险剖面：沿路径每 10km 采样，用于前端绘制热力条
        resp.setRiskProfile(buildRiskProfile(current.pathEdgeIds));

        return resp;
    }

    /** 构建延迟原因说明：分析路径上的风险段，给出主要延迟因素 */
    private String buildDelayReason(double delayHours, List<String> pathEdgeIds) {
        Map<String, com.example.aseanweatherlogistics.model.dto.RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        List<String> riskReasons = new ArrayList<>();
        for (String edgeId : pathEdgeIds) {
            com.example.aseanweatherlogistics.model.dto.RiskSegment risk = riskMap.get(edgeId);
            if (risk != null && risk.getPenaltyMultiplier() != null && risk.getPenaltyMultiplier() > 1.5) {
                String reason = risk.getReason();
                if (!riskReasons.contains(reason)) {
                    riskReasons.add(reason);
                }
                if (riskReasons.size() >= 3) break;
            }
        }
        if (riskReasons.isEmpty()) {
            return String.format("因路况延迟 +%.1fh", delayHours);
        }
        return String.format("因%s延迟 +%.1fh", String.join("、", riskReasons), delayHours);
    }

    /** 碳排放计算：距离(km) × 0.35L/km（重载卡车油耗） × 2.68kg/L（柴油碳排放因子） */
    private double calculateCarbonEmission(double distanceKm) {
        double fuelConsumptionLiters = distanceKm * 0.35;
        return fuelConsumptionLiters * 2.68;
    }

    /** 构建风险剖面：沿路径每 10km 采样，返回各段风险等级 */
    public List<Map<String, Object>> buildRiskProfile(List<String> pathEdgeIds) {
        List<Map<String, Object>> profile = new ArrayList<>();
        if (pathEdgeIds == null || pathEdgeIds.isEmpty()) {
            return profile;
        }
        Map<String, com.example.aseanweatherlogistics.model.dto.RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        double cumulativeDistance = 0;
        double sampleIntervalKm = 10.0;
        double nextSampleAt = sampleIntervalKm;

        for (String edgeId : pathEdgeIds) {
            RoadEdge edge = edgeById.get(edgeId);
            if (edge == null) continue;
            double edgeStart = cumulativeDistance;
            double edgeEnd = cumulativeDistance + edge.getDistanceKm();

            // 检查该边是否有风险
            com.example.aseanweatherlogistics.model.dto.RiskSegment risk = riskMap.get(edgeId);
            String riskLevel = "SAFE";
            String hazardType = null;
            double penalty = 1.0;
            if (risk != null && risk.getPenaltyMultiplier() != null) {
                penalty = risk.getPenaltyMultiplier();
                if (penalty >= 50) riskLevel = "CRITICAL";
                else if (penalty >= 20) riskLevel = "HIGH";
                else if (penalty > 1.5) riskLevel = "MEDIUM";
                hazardType = risk.getReason();
            }

            // 在该边范围内按间隔采样
            while (nextSampleAt <= edgeEnd) {
                Map<String, Object> point = new HashMap<>();
                point.put("distanceKm", round(nextSampleAt));
                point.put("riskLevel", riskLevel);
                point.put("penalty", round(penalty));
                point.put("hazardType", hazardType);
                profile.add(point);
                nextSampleAt += sampleIntervalKm;
            }
            cumulativeDistance = edgeEnd;
        }
        return profile;
    }

    /**
     * 节点 ID 序列 → 坐标序列 [[lat,lng], ...]，供移动端司机端绘图（见 RouteResponse.pathCoords）。
     * 逐边插回 OSM 真实道路几何（边 c 折线）：长抽稀边不再画成切弯直线，而是贴合底图实际走向；
     * 无几何数据的边（如中国侧虚拟走廊）退化为节点直连。
     */
    private List<double[]> toCoords(List<String> nodeIds, List<String> edgeIds) {
        return toCoords(nodeIds, edgeIds, null);
    }

    /**
     * 边几何展开为完整折线。edgeSpans（可选出参）按 edgeIds 下标记录每条边在折线中的
     * [startIdx, endIdx] 区间：几何抽稀后 pathCoords 点数与边数不再一一对应，
     * 前端风险段高亮需按区间取点，否则标红位置会错位。
     */
    private List<double[]> toCoords(List<String> nodeIds, List<String> edgeIds, List<int[]> edgeSpans) {
        List<double[]> coords = new ArrayList<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return coords;
        }
        int edgeCount = edgeIds == null ? 0 : edgeIds.size();
        for (int i = 0; i < nodeIds.size(); i++) {
            RoadNode n = nodeById.get(nodeIds.get(i));
            if (n == null) {
                // 保持 spans 与 edgeIds 下标对齐（占位区间）
                if (edgeSpans != null && i > 0 && coords.size() > 0) {
                    edgeSpans.add(new int[]{coords.size() - 1, coords.size() - 1});
                }
                continue;
            }
            double[] nodePt = new double[]{n.getLatitude(), n.getLongitude()};
            if (i == 0) {
                coords.add(nodePt);
                continue;
            }
            int spanStart = coords.size() - 1;
            // 上一段边 edgeIds[i-1]：nodeIds[i-1] -> nodeIds[i]
            RoadEdge e = (i - 1 < edgeCount) ? edgeById.get(edgeIds.get(i - 1)) : null;
            List<List<Double>> geom = e == null ? null : e.getC();
            if (geom != null && geom.size() > 1 && !coords.isEmpty()) {
                List<Double> g0 = geom.get(0);
                List<Double> gn = geom.get(geom.size() - 1);
                boolean reversed = sqDistKm(g0.get(0), g0.get(1),
                        coords.get(coords.size() - 1)[0], coords.get(coords.size() - 1)[1])
                        > sqDistKm(gn.get(0), gn.get(1),
                        coords.get(coords.size() - 1)[0], coords.get(coords.size() - 1)[1]);
                // 几何折线中间点插入（首点=上一节点已存在；末点以当前节点收尾）
                for (int k = 1; k < geom.size() - 1; k++) {
                    List<Double> p = reversed ? geom.get(geom.size() - 1 - k) : geom.get(k);
                    coords.add(new double[]{p.get(0), p.get(1)});
                }
            }
            coords.add(nodePt); // 当前节点收尾（无几何的边即节点直连）
            if (edgeSpans != null) {
                edgeSpans.add(new int[]{spanStart, coords.size() - 1});
            }
        }
        return coords;
    }

    /** 平面近似平方距离（仅用于方向判定，不需地球曲率） */
    private double sqDistKm(double lat1, double lon1, double lat2, double lon2) {
        double d0 = lat1 - lat2, d1 = lon1 - lon2;
        return d0 * d0 + d1 * d1;
    }

    /**
     * 计划书 5.2 冷链货损量化模型：
     * 延误 ≤4h 不启动；损耗率 = 2% 基础 + 沿线最高温>30°C 加 1% + 最高湿>85% 加 0.5%；
     * 货损 = 货值 × 损耗率 × (延误小时/24)。温湿度取自实时天气缓存（无缓存用常温默认）。
     */
    private double calculateCargoLoss(String cargoType, double delayHours, List<String> pathNodeIds) {
        if (!"cold".equals(cargoType) || delayHours <= 4) {
            return 0;
        }
        double temp = 28, humidity = 70; // 无实时天气时的常温默认（不触发任何加成）
        Map<String, RealWeatherService.WeatherPoint> pts = realWeatherService.cachedPoints();
        if (pts != null && !pts.isEmpty()) {
            double maxT = -999, maxH = -1;
            for (String nid : pathNodeIds) {
                RealWeatherService.WeatherPoint p = pts.get(nid);
                if (p != null) {
                    maxT = Math.max(maxT, p.temperatureC());
                    maxH = Math.max(maxH, p.humidityPct());
                }
            }
            if (maxT > -999) temp = maxT;
            if (maxH >= 0) humidity = maxH;
        }
        double rate = 0.02 + (temp > 30 ? 0.01 : 0) + (humidity > 85 ? 0.005 : 0);
        return CARGO_VALUE_YUAN * rate * (delayHours / 24.0);
    }

    /**
     * 拉取真实天气并同步为风险段（计划书 3.1）。
     * force=false 时带 5 分钟 TTL 缓存；网络失败静默降级，不改变现有风险。
     * 未指定路径时回退为全量有名字节点（页面首次加载兜底）。
     *
     * @return 节点实时天气视图列表
     */
    public List<Map<String, Object>> syncRealWeather(boolean force) {
        return syncRealWeather(force, null);
    }

    /**
     * 按"起点→终点路径沿线城市"拉取实时天气。
     * pathNodeIds 为路径节点 ID 序列，仅拉取沿线有名字的城市/口岸，减少请求量、更快返回。
     */
    public List<Map<String, Object>> syncRealWeather(boolean force, List<String> pathNodeIds) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRealSyncAt < REAL_SYNC_TTL_MS) {
            // 按路径节点过滤缓存，使不同路线有不同的天气剖面
            return realWeatherView(pathNodeIds);
        }
        // 真实天气拉取放独立线程并设总超时：网络慢/离线时绝不阻塞路径规划（保证"毫秒级重算"卖点）
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> path = pathNodeIds == null || pathNodeIds.isEmpty() ? null : pathNodeIds;
            Future<?> future;
            if (path == null) {
                future = executor.submit((Runnable) this::pullRealWeather);
            } else {
                future = executor.submit((Runnable) () -> pullRealWeatherForPath(path));
            }
            try {
                future.get(force ? 45 : 2, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
            } catch (Exception ignored) {
            }
        } finally {
            executor.shutdownNow();
        }
        lastRealSyncAt = now;
        return realWeatherView(pathNodeIds);
    }

    /** 气象按钮：按起终点沿线城市拉取；未提供起终点时回退全量 */
    public List<Map<String, Object>> syncRealWeatherForRoute(boolean force, String originId, String destinationId) {
        if (originId == null || originId.isBlank() || destinationId == null || destinationId.isBlank()) {
            return syncRealWeather(force);
        }
        try {
            PathResult baseline = shortestPath(originId, destinationId, Map.of());
            return syncRealWeather(force, baseline.pathNodeIds);
        } catch (Exception e) {
            return syncRealWeather(force);
        }
    }

    /** 全量有名字节点（页面首载、未规划路径时的兜底） */
    private void pullRealWeather() {
        // 只对城市/口岸等有名字节点拉取实时天气：路网含 5 万+ OSM 无名中间节点，
        // 全量拉取会触发免费 API 限流，且超时内根本拉不完（气象按钮无响应）。
        List<RoadNode> nodes = getAllNodes().stream()
                .filter(n -> n.getName() != null && !n.getName().isBlank())
                .collect(Collectors.toList());
        pullRealWeather(nodes);
    }

    /** 只拉取路径沿线的有名字节点（按路径顺序去重） */
    private void pullRealWeatherForPath(List<String> pathNodeIds) {
        List<RoadNode> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : pathNodeIds) {
            if (!seen.add(id)) {
                continue;
            }
            RoadNode n = nodeById.get(id);
            if (n != null && n.getName() != null && !n.getName().isBlank()) {
                nodes.add(n);
            }
        }
        pullRealWeather(nodes);
    }

    private void pullRealWeather(List<RoadNode> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        List<RealWeatherService.WeatherPoint> points = realWeatherService.fetchForNodes(nodes);
        if (points == null) {
            return;
        }
        Map<String, RealWeatherService.WeatherPoint> byNode = points.stream()
                .collect(Collectors.toMap(RealWeatherService.WeatherPoint::nodeId, p -> p));
        weatherSimulator.clearRealRisks();
        for (RoadEdge edge : edges) {
            RealWeatherService.WeatherPoint w1 = byNode.get(edge.getFromNodeId());
            RealWeatherService.WeatherPoint w2 = byNode.get(edge.getToNodeId());
            RiskSegment risk = buildRealRisk(edge, w1, w2);
            if (risk != null) {
                weatherSimulator.applyRealRisk(risk);
            }
        }
    }

    /** 当前缓存的节点天气视图 */
    public List<Map<String, Object>> realWeatherView() {
        return realWeatherView(null);
    }

    /** 按路径节点过滤天气，null/空则返回全量 */
    public List<Map<String, Object>> realWeatherView(List<String> pathNodeIds) {
        Map<String, RealWeatherService.WeatherPoint> pts = realWeatherService.cachedPoints();
        Set<String> filter = (pathNodeIds != null && !pathNodeIds.isEmpty())
                ? new HashSet<>(pathNodeIds) : null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (RealWeatherService.WeatherPoint p : pts.values()) {
            if (filter != null && !filter.contains(p.nodeId())) continue;
            Map<String, Object> item = new java.util.LinkedHashMap<>(p.toMap());
            RoadNode node = nodeById.get(p.nodeId());
            item.put("nodeName", node == null ? p.nodeId() : node.getName());
            out.add(item);
        }
        return out;
    }

    /** 依据两端点实时天气为边生成风险段；天气正常返回 null。 */
    private RiskSegment buildRealRisk(RoadEdge edge,
                                      RealWeatherService.WeatherPoint w1,
                                      RealWeatherService.WeatherPoint w2) {
        RealWeatherService.WeatherPoint w = worse(w1, w2);
        if (w == null) {
            return null;
        }
        double penalty = 0;
        List<String> reasons = new ArrayList<>();
        if (w.precipitationMm() >= 50) {
            penalty = Math.max(penalty, 100);
            reasons.add("暴雨(降水" + w.precipitationMm() + "mm/h)");
        } else if (w.precipitationMm() >= 20) {
            penalty = Math.max(penalty, 20);
            reasons.add("大雨(降水" + w.precipitationMm() + "mm/h)");
        }
        if (w.windKph() >= 60) {
            penalty = Math.max(penalty, 50);
            reasons.add("大风(" + w.windKph() + "km/h)");
        } else if (w.windKph() >= 40) {
            penalty = Math.max(penalty, 15);
            reasons.add("阵风(" + w.windKph() + "km/h)");
        }
        if (w.visibilityM() < 100) {
            penalty = Math.max(penalty, 50);
            reasons.add("大雾(能见度<100m)");
        } else if (w.visibilityM() < 500) {
            penalty = Math.max(penalty, 20);
            reasons.add("轻雾(能见度<500m)");
        }
        if (w.temperatureC() >= 35) {
            penalty = Math.max(penalty, 8);
            reasons.add("高温(" + w.temperatureC() + "°C，冷链货损风险)");
        }
        if (penalty <= 0) {
            return null;
        }
        String severity = penalty >= 100 ? "CRITICAL" : (penalty >= 30 ? "HIGH" : "MEDIUM");
        return new RiskSegment(edge.getId(), "实时气象:" + String.join(";", reasons), severity, penalty);
    }

    private RealWeatherService.WeatherPoint worse(RealWeatherService.WeatherPoint a,
                                                  RealWeatherService.WeatherPoint b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return scoreWeather(a) >= scoreWeather(b) ? a : b;
    }

    private double scoreWeather(RealWeatherService.WeatherPoint w) {
        double s = 0;
        if (w.precipitationMm() >= 50) {
            s += 100;
        } else if (w.precipitationMm() >= 20) {
            s += 20;
        }
        if (w.windKph() >= 60) {
            s += 50;
        } else if (w.windKph() >= 40) {
            s += 15;
        }
        if (w.visibilityM() < 100) {
            s += 50;
        } else if (w.visibilityM() < 500) {
            s += 20;
        }
        if (w.temperatureC() >= 35) {
            s += 8;
        }
        return s;
    }

    private void validate(String originId, String destinationId) {
        if (originId == null || destinationId == null) {
            throw new IllegalArgumentException("originId and destinationId are required");
        }
        if (!nodeById.containsKey(originId) || !nodeById.containsKey(destinationId)) {
            throw new IllegalArgumentException("originId or destinationId does not exist in the road network");
        }
    }

    private PathResult joinOrThrow(CompletableFuture<PathResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private PathResult shortestPath(String originId, String destinationId, Map<String, RiskSegment> riskMap) {
        return shortestPath(originId, destinationId, riskMap, Set.of());
    }

    /**
     * Dijkstra 求最短路径（可选禁走指定边）。
     *
     * @param forbiddenEdgeIds 需要排除的边 ID（生成"备选路线"时禁走已选路线，模拟 Yen 算法的第一层旁路），
     *                         为空集合时不产生任何额外开销
     */
    private PathResult shortestPath(String originId, String destinationId, Map<String, RiskSegment> riskMap,
                                    Set<String> forbiddenEdgeIds) {
        return shortestPath(originId, destinationId, riskMap, forbiddenEdgeIds, Map.of());
    }

    /** Dijkstra 带边权重乘数：对指定边乘以 weightOverrides 中的系数，用于吸引/排斥特定口岸 */
    private PathResult shortestPath(String originId, String destinationId, Map<String, RiskSegment> riskMap,
                                    Set<String> forbiddenEdgeIds, Map<String, Double> weightOverrides) {
        Map<String, Double> distance = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Map<String, RoadEdge> prevEdge = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::distance));
        distance.put(originId, 0.0);
        queue.add(new NodeDistance(originId, 0.0));

        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();
            Double settled = distance.get(current.nodeId());
            if (settled == null || current.distance() > settled) continue;
            if (current.nodeId().equals(destinationId)) break;
            for (RoadEdge edge : adjacency.getOrDefault(current.nodeId(), NO_EDGES)) {
                if (forbiddenEdgeIds.contains(edge.getId())) continue;
                RiskSegment risk = riskMap.get(edge.getId());
                if (isImpassable(risk)) continue;
                double w = edgeTimeHours(edge, risk);
                // 应用边权重乘数
                Double mult = weightOverrides.get(edge.getId());
                if (mult != null) w *= mult;
                double candidate = current.distance() + w;
                Double known = distance.get(edge.getToNodeId());
                if (known == null || candidate < known) {
                    distance.put(edge.getToNodeId(), candidate);
                    previous.put(edge.getToNodeId(), current.nodeId());
                    prevEdge.put(edge.getToNodeId(), edge);
                    queue.add(new NodeDistance(edge.getToNodeId(), candidate));
                }
            }
        }

        Double destDist = distance.get(destinationId);
        if (destDist == null || destDist.isInfinite()) {
            long blockedEdges = riskMap.values().stream().filter(this::isImpassable).count();
            String detail = blockedEdges > 0
                    ? blockedEdges + " 条道路已被硬熔断，所有通往目的地的路线均不可通行，请人工介入或解除部分封路"
                    : "当前权重配置下无可用路径";
            throw new IllegalStateException(detail);
        }
        List<String> pathRev = new ArrayList<>();
        List<String> edgeIdsRev = new ArrayList<>();
        String cursor = destinationId;
        while (cursor != null) {
            pathRev.add(cursor);
            if (!cursor.equals(originId)) {
                RoadEdge e = prevEdge.get(cursor);
                if (e != null) edgeIdsRev.add(e.getId());
            }
            cursor = previous.get(cursor);
        }
        Collections.reverse(pathRev);
        Collections.reverse(edgeIdsRev);
        double dist = 0;
        for (String eid : edgeIdsRev) {
            RoadEdge e = findEdge(eid);
            if (e != null) dist += e.getDistanceKm();
        }
        return new PathResult(pathRev, edgeIdsRev, dist, destDist);
    }

    private RoadEdge findEdge(String edgeId) {
        return edgeById.get(edgeId);
    }

    /**
     * 硬切断判定：惩罚系数 >= IMPASSABLE_PENALTY 的边视为不可通行（见 IMPASSABLE_PENALTY 注释）。
     * 注意：硬切断的边已在 shortestPath 中被 continue 跳过，不会走到下面的乘法。
     */
    // ==================== 局部绕行避灾 ====================

    /**
     * 局部绕行：从司机当前位置绕开灾害段，重回原路线，只改受影响的局部、不动其余路段。
     *
     * @param destinationId       路线终点（不变）
     * @param currentRouteEdgeIds 当前路线的边 ID 序列
     * @param currentRouteNodeIds 当前路线的节点 ID 序列
     * @param hazardEdgeIds       需要绕开的灾害边 ID 集合
     * @param progressRatio       司机已走完路线的比例（0.0~1.0），用于定位当前位置
     * @return 绕行后的完整路线响应
     */
    public RouteResponse detourAroundHazard(String destinationId,
                                            List<String> currentRouteEdgeIds,
                                            List<String> currentRouteNodeIds,
                                            Set<String> hazardEdgeIds,
                                            double progressRatio) {
        validate(currentRouteNodeIds.get(0), destinationId);
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();

        // ---- 1. 找当前所在节点 ----
        String currentNodeId = findCurrentNode(currentRouteNodeIds, currentRouteEdgeIds, progressRatio);

        // ---- 2. 找灾害段的起止节点和重回节点 ----
        String hazardStartNode = null;
        String hazardEndNode = null;
        String rejoinNode = null;

        int firstHazardIdx = -1;
        int lastHazardIdx = -1;
        for (int i = 0; i < currentRouteEdgeIds.size(); i++) {
            if (hazardEdgeIds.contains(currentRouteEdgeIds.get(i))) {
                if (firstHazardIdx < 0) firstHazardIdx = i;
                lastHazardIdx = i;
            }
        }
        if (firstHazardIdx < 0) {
            // 没有灾害边在当前路线上 → 返回当前路线
            return planRouteFast(currentRouteNodeIds.get(0), destinationId);
        }

        // 灾害段起点：第一个 hazard 边的 fromNode
        hazardStartNode = findEdge(currentRouteEdgeIds.get(firstHazardIdx)).getFromNodeId();
        // 灾害段终点：最后一个 hazard 边的 toNode
        hazardEndNode = findEdge(currentRouteEdgeIds.get(lastHazardIdx)).getToNodeId();
        // 重回节点：灾害段终点之后的下一个节点
        int rejoinIdx = currentRouteNodeIds.indexOf(hazardEndNode);
        if (rejoinIdx >= 0 && rejoinIdx + 1 < currentRouteNodeIds.size()) {
            rejoinNode = currentRouteNodeIds.get(rejoinIdx + 1);
        } else {
            rejoinNode = destinationId; // 灾害在路线末尾 → 直接到终点
        }

        // ---- 3. 分段路由 ----
        // preSegment: currentNode → hazardStartNode（沿原路线不变）
        // detourSegment: hazardStartNode → rejoinNode（Dijkstra 绕行，禁用 hazardEdgeIds）
        // postSegment: rejoinNode → destinationId（沿原路线不变）

        List<String> combinedNodeIds = new java.util.ArrayList<>();
        List<String> combinedEdgeIds = new java.util.ArrayList<>();

        // 3a. preSegment（走原路线的 already-passed + 安全部分）
        boolean foundCurrent = false;
        for (int i = 0; i < currentRouteNodeIds.size(); i++) {
            String nid = currentRouteNodeIds.get(i);
            if (nid.equals(currentNodeId)) foundCurrent = true;
            if (foundCurrent) {
                combinedNodeIds.add(nid);
                if (i < currentRouteEdgeIds.size()) {
                    combinedEdgeIds.add(currentRouteEdgeIds.get(i));
                }
                if (nid.equals(hazardStartNode)) break;
            }
        }

        // 3b. detourSegment（Dijkstra 绕行）
        Set<String> forbidden = new HashSet<>(hazardEdgeIds);
        // 也禁掉当前灾害边在风险图中标记为 impassable 的边
        for (String eid : hazardEdgeIds) {
            RiskSegment r = riskMap.get(eid);
            if (r != null && isImpassable(r)) forbidden.add(eid);
        }

        PathResult detourPath;
        try {
            detourPath = shortestPath(hazardStartNode, rejoinNode, riskMap, forbidden, Map.of());
        } catch (IllegalStateException e) {
            // 绕行失败 → 退化为全程重算
            return planRouteFast(currentRouteNodeIds.get(0), destinationId);
        }

        // 追加 detour 段（跳过与 preSegment 末点重复的衔接点）
        if (!detourPath.pathNodeIds().isEmpty()
                && detourPath.pathNodeIds().get(0).equals(hazardStartNode)) {
            // 跳过起点
            for (int i = 1; i < detourPath.pathNodeIds().size(); i++) {
                combinedNodeIds.add(detourPath.pathNodeIds().get(i));
            }
        } else {
            combinedNodeIds.addAll(detourPath.pathNodeIds());
        }
        // 跳过 detour 段的第一条边（preSegment 可能已包含）
        for (int i = 0; i < detourPath.pathEdgeIds().size(); i++) {
            String eid = detourPath.pathEdgeIds().get(i);
            if (!combinedEdgeIds.contains(eid)) {
                combinedEdgeIds.add(eid);
            }
        }

        // 3c. postSegment（从 rejoinNode 之后沿原路线到终点）
        boolean afterRejoin = false;
        for (int i = 0; i < currentRouteNodeIds.size(); i++) {
            String nid = currentRouteNodeIds.get(i);
            if (nid.equals(rejoinNode)) {
                afterRejoin = true;
                continue; // 跳过 rejoinNode 本身（detour 段已包含）
            }
            if (afterRejoin) {
                combinedNodeIds.add(nid);
                if (i < currentRouteEdgeIds.size() && i > 0) {
                    String eid = currentRouteEdgeIds.get(i - 1);
                    if (!combinedEdgeIds.contains(eid)) {
                        combinedEdgeIds.add(eid);
                    }
                }
            }
        }

        // ---- 4. 构建响应 ----
        // 以 combined 路线为 current，原始路线为 baseline
        double detourDist = 0;
        double detourHours = 0;
        for (String eid : combinedEdgeIds) {
            RoadEdge e = findEdge(eid);
            if (e != null) {
                detourDist += e.getDistanceKm();
                RiskSegment r = riskMap.get(eid);
                detourHours += edgeTimeHours(e, r);
            }
        }

        // 构建原始路线的 PathResult 作为 baseline
        PathResult baseline = new PathResult(
                new ArrayList<>(currentRouteNodeIds),
                new ArrayList<>(currentRouteEdgeIds),
                sumDistance(currentRouteEdgeIds),
                sumHours(currentRouteEdgeIds, riskMap));

        PathResult current = new PathResult(combinedNodeIds, combinedEdgeIds, detourDist, detourHours);

        return buildResponse(baseline, current, lastCargoType);
    }

    /** 根据进度比例找到路线上的当前节点 */
    private String findCurrentNode(List<String> nodeIds, List<String> edgeIds, double ratio) {
        if (nodeIds == null || nodeIds.isEmpty()) return null;
        if (ratio <= 0 || edgeIds == null || edgeIds.isEmpty()) return nodeIds.get(0);
        if (ratio >= 1.0) return nodeIds.get(nodeIds.size() - 1);

        double totalDist = 0;
        for (String eid : edgeIds) {
            RoadEdge e = findEdge(eid);
            if (e != null) totalDist += e.getDistanceKm();
        }
        double targetDist = totalDist * ratio;
        double cumDist = 0;
        for (int i = 0; i < edgeIds.size(); i++) {
            RoadEdge e = findEdge(edgeIds.get(i));
            if (e == null) continue;
            cumDist += e.getDistanceKm();
            if (cumDist >= targetDist) {
                // 返回这条边的起点（即当前位置所在段的起点）
                return e.getFromNodeId();
            }
        }
        return nodeIds.get(nodeIds.size() - 1);
    }

    private double sumDistance(List<String> edgeIds) {
        double d = 0;
        for (String eid : edgeIds) {
            RoadEdge e = findEdge(eid);
            if (e != null) d += e.getDistanceKm();
        }
        return d;
    }

    private double sumHours(List<String> edgeIds, Map<String, RiskSegment> riskMap) {
        double h = 0;
        for (String eid : edgeIds) {
            RoadEdge e = findEdge(eid);
            if (e != null) h += edgeTimeHours(e, riskMap.get(eid));
        }
        return h;
    }

    private boolean isImpassable(RiskSegment risk) {
        return risk != null && risk.getPenaltyMultiplier() != null
                && risk.getPenaltyMultiplier() >= IMPASSABLE_PENALTY;
    }

    /**
     * 边权重（小时）= 距离/限速 + 口岸通关时间，再乘以气象惩罚系数。
     * 惩罚系数含义（与计划书一致）：
     *   >80mm/h 暴雨 -> 100（等效道路中断）；泥石流风险>0.7 -> 1000（直接切断）
     * 其中 >=100 的边已被 isImpassable 判定为硬切断并排除，此处只处理软惩罚。
     */
    private double edgeTimeHours(RoadEdge edge, RiskSegment risk) {
        double speed = (edge.getSpeedKmh() != null && edge.getSpeedKmh() > 0)
                ? edge.getSpeedKmh() : DEFAULT_SPEED_KMH;
        double hours = edge.getDistanceKm() / speed;
        if (edge.isCustoms()) {
            double customsHours = customsEfficiencyService.customsHoursByEdgeId(edge.getId());
            if (customsHours > 0) {
                hours += customsHours;
            } else if (edge.getCustomsDelayHours() != null) {
                hours += edge.getCustomsDelayHours();
            }
        }
        if (risk != null && risk.getPenaltyMultiplier() != null && risk.getPenaltyMultiplier() > 0) {
            hours *= risk.getPenaltyMultiplier();
        }
        return hours;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record NodeDistance(String nodeId, double distance) {
    }

    private record PathResult(List<String> pathNodeIds, List<String> pathEdgeIds, double totalDistanceKm, double hours) {
    }
}
