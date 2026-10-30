package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.dto.RouteRequest;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.repository.RouteRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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

    // 台风 / 强对流判据已集中到 TyphoonRiskRule（真实气象与沙盘模拟共用同一套阈值）
    /** 最近一次 planRoute 的货类型，供 Agent 毫秒级重算(planRouteFast)沿用，冷链货损计算需要 */
    private volatile String lastCargoType;

    private Map<String, RoadNode> nodeById = Map.of();
    private List<RoadEdge> edges = List.of();
    private Map<String, List<RoadEdge>> adjacency = Map.of();
    /** 边 ID -> 边 的哈希索引：路径重建/里程统计从线性扫描 O(E) 降为 O(1) */
    private Map<String, RoadEdge> edgeById = Map.of();
    /** 邻接表默认空桶，避免每次 getOrDefault 新建空 List */
    private static final List<RoadEdge> NO_EDGES = List.of();

    /** 全图最高限速（km/h）：A* 启发式的分母，启动时算一次；启发式 = 直线距离/最快限速，保证不高估剩余代价 */
    private double maxEdgeSpeedKmh = DEFAULT_SPEED_KMH;

    /**
     * 越南侧节点集合（懒算并缓存）：从河内(HN)出发、只沿非口岸(customs)边可达的节点。
     * 中越两侧路网仅由口岸虚拟边相连，故此集合与「中国侧」天然互斥——用于判断某目的地是否位于越南，
     * 进而决定普通导航是否并列推荐「坐船出境」（见 IntermodalService.serves）。
     */
    private volatile Set<String> vietnamSideNodes;

    private static final long REAL_SYNC_TTL_MS = 5 * 60_000L;
    private volatile long lastRealSyncAt = 0;
    /**
     * 实时气象拉取共用线程：以前每次 {@link #syncRealWeather} 都 new 一个单线程池再用完 shutdown，
     * 后台守护 60s 一轮＝一天 1440 个线程池的创建与回收；改成常驻单线程（daemon），
     * 超时仍由 future.cancel(true) 中断任务，不再销毁线程。
     */
    private final ExecutorService realPullExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "real-weather-pull");
        t.setDaemon(true);
        return t;
    });
    /** 拉取并发闸：上一轮还没回来就不再排第二个（上游慢时守护不会把队列越堆越长） */
    private final java.util.concurrent.atomic.AtomicBoolean realPullInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 走廊基线沿线节点并集缓存：{@link #syncRealWeatherForCorridors} 里的 Dijkstra 跑的是
     * 无风险的基线路径（penalties 为空 Map），只随路网站点变化，与气象风险无关；
     * 守护每 60s 一轮却每轮重算 2~3 条走廊，纯浪费。走廊集合不变且未过 TTL 时直接复用。
     */
    private static final long CORRIDOR_UNION_TTL_MS = 10 * 60_000L;
    private volatile String corridorUnionKey;
    private volatile List<String> corridorUnionValue;
    private volatile long corridorUnionAtMs;

    /**
     * 候选路线结果缓存：键 = 起终点 + 口岸状态签名（时段/货类/人工调整），
     * 条目内再带一份「风险图签名」，两者都一致时直接复用上次结果。
     * <p>
     * 为什么要它：调度端派单时已经为同一 O/D 算过一次候选，司机点「确认接单」会再算一遍；
     * 单次要跑 25 轮并行搜索，2 核服务器实测 7~18s —— 命中缓存后与派单那次逐字段相同，
     * 但只需毫秒级返回。风险/口岸任一变化 → 签名不同 → 照旧重算，不影响正确性。
     */
    private static final long CANDIDATES_CACHE_TTL_MS = 10 * 60_000L;
    private static final int CANDIDATES_CACHE_MAX = 8;
    private final Map<String, CachedCandidates> candidatesCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedCandidates> eldest) {
                    return size() > CANDIDATES_CACHE_MAX;
                }
            };
    /** 同一键并发请求只算一次：后到的请求等前一个算完直接拿缓存（2 核上比各自算一遍更快） */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> candidatesLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 候选缓存条目：口岸签名已在键里；这里存风险签名 + 可复用的无风险基线 + 结果快照 + 按 key 索引的原始路径 */
    private record CachedCandidates(String riskSignature, PathResult baseline,
                                    List<Map<String, Object>> result,
                                    Map<String, CandidatePath> byKey, long at) {
    }

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
        // 陆水联运上船锚点自愈：路网文件重新生成后曾丢失 "LJ" 节点，导致坐船方案算路 400（见方法注释）
        ensureCanalAnchor();
        // A* 启发式基准：全图最大限速（启发式用它折算剩余时间下界，必然可采纳）
        double ms = DEFAULT_SPEED_KMH;
        for (RoadEdge e : edges) {
            if (e.getSpeedKmh() != null && e.getSpeedKmh() > ms) ms = e.getSpeedKmh();
        }
        this.maxEdgeSpeedKmh = ms;
        // 中国侧为手工建模的虚拟走廊（无 OSM 源几何），补上贴合真实高速/国道的走向折线，
        // 否则司机端画线在城市间直连（看似"穿山"）；越南侧 OSM 抽稀边已自带 c 几何
        patchVirtualChinaEdges();
        // 注册路径解析器：供 WeatherSimulator 做"整段路径"灾害注入（如谅山—北江）
        weatherSimulator.setPathResolver(this::edgesOnPath);
    }

    /** 南宁港六景作业区（上船点）坐标：与地名库/place-names.json 的「南宁港六景作业区」同点 */
    private static final String CANAL_EMBARK_NODE_ID = "LJ";
    private static final double[] CANAL_EMBARK_POS = {22.86899, 108.88669};

    /**
     * 陆水联运上船锚点自愈：并入广西密集路网重新生成 vietnam-road-network.json 后，
     * 手工建模的「LJ」（南宁港六景作业区）节点被冲掉，司机端/调度大屏选定坐船方案后
     * 请求 originId→LJ 的上船公路段全部 400 —— 前端拿不到新线就维持旧公路候选
     * （可能经芒街），地图与「陆水联运」卡片自相矛盾；调度方案 B 导航同样被击穿。
     * 启动时若缺 LJ，则合成该节点并用一条短连接线挂到最近的路网节点（柳南高速六景互通一带），
     * 此后无论路网文件再怎么重新生成，只要六景附近还有路，坐船方案就不会坏。
     * 连接线几何不再用两点直线（直线会斜穿街区，司机端表现为「导航线穿建筑物」），
     * 而是沿真实路网顶点贪心跳到港区 1km 圈内，最后一段短直线进港（港区内本无 OSM 道路）。
     */
    private void ensureCanalAnchor() {
        if (nodeById.containsKey(CANAL_EMBARK_NODE_ID)) {
            return;
        }
        RoadNode nearest = null;
        double bestKm = Double.MAX_VALUE;
        for (RoadNode n : nodeById.values()) {
            double km = approxKm(CANAL_EMBARK_POS[0], CANAL_EMBARK_POS[1], n.getLatitude(), n.getLongitude());
            if (km < bestKm) {
                bestKm = km;
                nearest = n;
            }
        }
        // 5km 内没有可挂节点说明路网文件本身出了问题，不硬造孤立节点
        if (nearest == null || bestKm > 5.0) {
            return;
        }
        RoadNode lj = new RoadNode(CANAL_EMBARK_NODE_ID, "南宁港六景作业区",
                CANAL_EMBARK_POS[0], CANAL_EMBARK_POS[1], null, null);
        List<List<Double>> linkGeom = canalLinkGeometry(nearest, CANAL_EMBARK_POS);
        double linkKm = polyKm(linkGeom);
        RoadEdge link = new RoadEdge("ELJ-CONNECT", nearest.getId(), CANAL_EMBARK_NODE_ID,
                Math.max(0.1, Math.round(linkKm * 10) / 10.0), 40.0, false, null,
                "六景港区连接线", "trunk", linkGeom);
        nodeById.put(CANAL_EMBARK_NODE_ID, lj);
        edges.add(link);
        edgeById.put(link.getId(), link);
        adjacency.computeIfAbsent(nearest.getId(), ignored -> new ArrayList<>()).add(link);
        adjacency.computeIfAbsent(CANAL_EMBARK_NODE_ID, ignored -> new ArrayList<>()).add(reverse(link));
    }

    /**
     * 港区连接线沿真实路网的贪心跳接：每步在邻接节点里选「严格更靠近码头且横向
     * 不偏离〈当前点→码头〉直线 1.5km」的最优者，沿该边补齐真实形状点；距码头
     * ≤ 1km 或无路可跳即停，收尾短直线段进港。里程按拼接后的折线实长回写，
     * 保证「声称里程 == 画线里程」（与走廊边 distanceKm 校正同一口径）。
     */
    private List<List<Double>> canalLinkGeometry(RoadNode from, double[] target) {
        List<List<Double>> geom = new ArrayList<>();
        geom.add(List.of(from.getLatitude(), from.getLongitude()));
        RoadNode cur = from;
        Set<String> seen = new HashSet<>();
        seen.add(cur.getId());
        for (int hop = 0; hop < 8; hop++) {
            double curKm = approxKm(cur.getLatitude(), cur.getLongitude(), target[0], target[1]);
            if (curKm <= 1.0) {
                break;
            }
            RoadNode nextNode = null;
            RoadEdge nextEdge = null;
            double nextBest = curKm;
            for (RoadEdge e : adjacency.getOrDefault(cur.getId(), NO_EDGES)) {
                if (e.isCustoms()) {
                    continue;
                }
                String otherId = cur.getId().equals(e.getFromNodeId()) ? e.getToNodeId() : e.getFromNodeId();
                RoadNode n = nodeById.get(otherId);
                if (n == null || seen.contains(n.getId())) {
                    continue;
                }
                if (offsetFromSegKm(n.getLatitude(), n.getLongitude(),
                        cur.getLatitude(), cur.getLongitude(), target[0], target[1]) > 1.5) {
                    continue; // 横向偏移过大：往反方向绕的路不要
                }
                double nKm = approxKm(n.getLatitude(), n.getLongitude(), target[0], target[1]);
                if (nKm < nextBest - 0.1) {
                    nextBest = nKm;
                    nextNode = n;
                    nextEdge = e;
                }
            }
            if (nextNode == null) {
                break;
            }
            appendEdgeShape(geom, nextEdge, cur, nextNode);
            seen.add(nextNode.getId());
            cur = nextNode;
        }
        geom.add(List.of(target[0], target[1]));
        return geom;
    }

    /** 按「cur -> next」方向把边接入折线：与 toCoords 同口径取向，补中间形状点后以节点收尾 */
    private void appendEdgeShape(List<List<Double>> geom, RoadEdge e, RoadNode cur, RoadNode next) {
        List<List<Double>> c = e.getC();
        if (c != null && c.size() > 2) {
            List<Double> head = c.get(0);
            List<Double> tail = c.get(c.size() - 1);
            boolean rev = approxKm(cur.getLatitude(), cur.getLongitude(), head.get(0), head.get(1))
                    > approxKm(cur.getLatitude(), cur.getLongitude(), tail.get(0), tail.get(1));
            for (int k = 1; k < c.size() - 1; k++) {
                List<Double> p = rev ? c.get(c.size() - 1 - k) : c.get(k);
                geom.add(List.of(p.get(0), p.get(1)));
            }
        }
        geom.add(List.of(next.getLatitude(), next.getLongitude()));
    }

    /** 点到线段的横向距离（km）：贪心跳接时限制候选节点不得绕离〈当前点→目标〉直线 */
    private static double offsetFromSegKm(double plat, double plon,
                                          double alat, double alon, double blat, double blon) {
        double cos = Math.cos(Math.toRadians(plat));
        double px = plon * cos, py = plat;
        double ax = alon * cos, ay = alat;
        double dx = blon * cos - ax, dy = blat - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - ax - dx * t, py - ay - dy * t) * 111.32;
    }

    /** 折线实长（km）：连接线里程按几何实长回写 */
    private static double polyKm(List<List<Double>> pts) {
        double km = 0;
        for (int i = 1; i < pts.size(); i++) {
            km += approxKm(pts.get(i - 1).get(0), pts.get(i - 1).get(1), pts.get(i).get(0), pts.get(i).get(1));
        }
        return km;
    }

    /** 经纬度近似平面距离（km）：启动期一次性扫描用，不需要严格测地线 */
    private static double approxKm(double lat1, double lng1, double lat2, double lng2) {
        double dx = (lng2 - lng1) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * 110.57;
        return Math.hypot(dx, dy);
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

    /** 节点只读视图（不拷贝）：供全量扫描场景（如坐标吸附）避免每次 new ArrayList 复制数万节点 */
    public java.util.Collection<RoadNode> allNodesView() {
        return nodeById.values();
    }

    public List<RoadEdge> getAllEdges() {
        return new ArrayList<>(edges);
    }

    public RoadNode getNode(String nodeId) {
        return nodeById.get(nodeId);
    }

    /**
     * 该节点是否在越南侧：以「从河内(HN)出发、不跨越任何口岸边可达」定义。
     * 中越两侧路网只靠口岸虚拟边(customs)相连，故越南侧与中国侧互斥——据此判断「本程是否要去越南」，
     * 比按城市名枚举或按经纬度卡国界都稳（边境城市如东兴↔芒街、凭祥↔同登纬度几乎贴着）。
     */
    public boolean isInVietnam(String nodeId) {
        return nodeId != null && vietnamSide().contains(nodeId);
    }

    private Set<String> vietnamSide() {
        Set<String> s = vietnamSideNodes;
        if (s == null) {
            synchronized (this) {
                if (vietnamSideNodes == null) {
                    vietnamSideNodes = floodNoCustoms("HN");
                }
                s = vietnamSideNodes;
            }
        }
        return s;
    }

    /** 从 anchor 出发、只沿非口岸(customs)边做 BFS，返回可达节点集合（用于标定一侧国境） */
    private Set<String> floodNoCustoms(String anchor) {
        Set<String> seen = new HashSet<>();
        if (!nodeById.containsKey(anchor)) {
            return seen;
        }
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(anchor);
        seen.add(anchor);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (RoadEdge e : adjacency.getOrDefault(cur, NO_EDGES)) {
                if (e.isCustoms()) {
                    continue; // 口岸虚拟边两侧分属不同国家，不跨越
                }
                String next = e.getToNodeId();
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
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
        // 与候选接口共用同一份快照：推荐路线就是 buildCandidates 的第 0 条（resilientPath），
        // 命中缓存时不再重跑「基线 + 风险」两趟搜索（2 核服务器单次约 1.5~2s）
        CachedCandidates snap = ensureCandidates(originId, destinationId);
        CandidatePath rec = snap.byKey.get("recommended");
        if (rec == null) {
            // 理论上不会发生（buildCandidates 必产出 recommended）；兜底走原路径，绝不 500
            PathResult baseline = joinOrThrow(CompletableFuture.supplyAsync(
                    () -> shortestPath(originId, destinationId, Map.of()), planExecutor));
            ResilientPath r = computeCurrentPath(originId, destinationId, baseline);
            RouteResponse fallback = buildResponse(baseline, r.path, lastCargoType);
            fallback.setCorridorAllFused(r.softened);
            return fallback;
        }
        RouteResponse resp = buildResponse(snap.baseline, rec.path, lastCargoType);
        resp.setCorridorAllFused(rec.softened);
        return resp;
    }

    /**
     * Agent 实时守护用毫秒级重算：不重新拉天气（天气走 TTL 缓存），
     * 直接用当前风险图并行跑双 Dijkstra，突发灾害后「马上」产出新路线。
     */
    public RouteResponse planRouteFast(String originId, String destinationId) {
        validate(originId, destinationId);
        PathResult baseline = joinOrThrow(CompletableFuture.supplyAsync(
                () -> shortestPath(originId, destinationId, Map.of()), planExecutor));
        ResilientPath rec = computeCurrentPath(originId, destinationId, baseline);
        RouteResponse resp = buildResponse(baseline, rec.path, lastCargoType);
        // 公路是否已无路可走（靠降级才出得了路）：同一趟搜索的产物，不让下游再跑一次注定失败的搜索
        resp.setCorridorAllFused(rec.softened);
        return resp;
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
        return copyCandidates(ensureCandidates(originId, destinationId).result());
    }

    /**
     * 取（或补齐）当前 O/D 的候选快照，planCandidates / planRoute / planRouteByChoice 共用：
     * 口岸状态（在键里）+ 风险图签名一致且未过期 → 直接返回缓存，零搜索；
     * 否则跑一遍完整候选生成并写回缓存。
     * <p>
     * 这样「大屏派单算一次 → 司机接单取候选 → 按 choice 开始导航再取一次」这条链路上，
     * 只有第一次真的跑 25 轮 A*，后两次都是毫秒级。
     */
    private CachedCandidates ensureCandidates(String originId, String destinationId) {
        String cacheKey = candidatesKey(originId, destinationId);
        Object lock = candidatesLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            CachedCandidates cached;
            synchronized (candidatesCache) {
                cached = candidatesCache.get(cacheKey);
            }
            // 基线是无风险图上的最短路，与气象风险无关：口岸状态未变（同键）即可复用，省一次 Dijkstra
            PathResult baseline = cached != null
                    ? cached.baseline()
                    : shortestPath(originId, destinationId, Map.of());
            syncRealWeather(false, baseline.pathNodeIds());
            Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
            String riskSignature = riskStateSignature(riskMap);
            if (cached != null && riskSignature.equals(cached.riskSignature())
                    && System.currentTimeMillis() - cached.at() < CANDIDATES_CACHE_TTL_MS) {
                return cached;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, CandidatePath> byKey = new LinkedHashMap<>();
            for (CandidatePath c : buildCandidates(originId, destinationId, baseline, riskMap)) {
                out.add(toCandidate(c.key, c.label, c.path, riskMap, c.note, originId, destinationId, c.softened));
                byKey.put(c.key, c);
            }
            CachedCandidates fresh = new CachedCandidates(riskSignature, baseline, out, byKey,
                    System.currentTimeMillis());
            synchronized (candidatesCache) {
                candidatesCache.put(cacheKey, fresh);
            }
            return fresh;
        }
    }

    /** 候选缓存键：起点 + 终点 + 口岸状态签名（时段/货类/人工调整） */
    private String candidatesKey(String originId, String destinationId) {
        return originId + '>' + destinationId + '|' + customsEfficiencyService.stateSignature();
    }

    /**
     * 风险图签名：边 ID 集合或任一惩罚系数/等级变化都会改变签名。
     * 排序后再拼，保证同一份风险图无论 Map 遍历顺序都得到同一个签名。
     */
    private static String riskStateSignature(Map<String, RiskSegment> riskMap) {
        if (riskMap == null || riskMap.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>(riskMap.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder(keys.size() * 32 + 8);
        for (String k : keys) {
            RiskSegment r = riskMap.get(k);
            String penalty = r == null || r.getPenaltyMultiplier() == null ? "-" : r.getPenaltyMultiplier().toString();
            String sev = r == null || r.getSeverity() == null ? "-" : r.getSeverity();
            sb.append(k).append(':').append(penalty).append(':').append(sev).append(';');
        }
        return sb.toString();
    }

    /**
     * 缓存结果对外必须发一份新的 Map（调用方会往每条候选里 put 灾害概率等字段）：
     * 只复制外层 Map，坐标/边 ID 等 List 只读共享，不产生额外内存开销。
     */
    private static List<Map<String, Object>> copyCandidates(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) {
            out.add(new HashMap<>(m));
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
            // 备选线是否也属于「降级放行」：逐条看它自己踩没踩熔断边，不直接继承推荐线的结论。
            // 降级搜出来的备选里完全可能有整条都不穿熔断段的安全线，一刀切继承会把安全线
            // 也标成「谨慎通行」（橙色降级虚线、不建议通行徽标都跟着误报）。
            boolean altSoftened = rec.softened && pathTouchesFusedEdge(p, riskMap);
            out.add(new CandidatePath(key, label, note, p, altSoftened));
        }
        return out;
    }

    /** 该路径上是否存在硬熔断边（penalty ≥ {@code IMPASSABLE_PENALTY}） */
    private boolean pathTouchesFusedEdge(PathResult p, Map<String, RiskSegment> riskMap) {
        if (p == null || p.pathEdgeIds() == null) {
            return false;
        }
        for (String eid : p.pathEdgeIds()) {
            if (isImpassable(riskMap.get(eid))) {
                return true;
            }
        }
        return false;
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

    // ==================== 全域受阻判据（供 CorridorAdvisoryService 出「返回/等待」建议）====================

    /**
     * 公路走廊是否已「全部硬熔断」：按当前风险图搜不出任何路径，必须把熔断边降级为软惩罚才出得了路。
     * <p>
     * 这条判据比「看沙盘两个口岸断面是否熔断」可靠：沙盘只建模了友谊关/芒街两个点，
     * 而走廊是否真断要看路网——河口等其它出境边被注入同等风险时同样计入，
     * 也不会出现「AI 说建议等待、算路引擎却照常给出一条向前路线」两套口径打架。
     * 起终点非法等业务异常不等于受阻（返回 false，让调用方照常报错）。
     */
    public boolean roadCorridorsAllFused(String originId, String destinationId) {
        try {
            shortestPath(originId, destinationId, weatherSimulator.currentRiskMap());
            return false;
        } catch (IllegalStateException noPath) {
            return true;
        } catch (Exception badRequest) {
            return false;
        }
    }

    /**
     * 出境点（路径上最后一条口岸虚拟边）在全程里程中的位置比例 0~1；路径不含口岸边返回 -1。
     * 各端据此判断车在「境内段」还是「已出境」：境内可以折返，出境后折返要重新通关、不经济，只能就地等。
     */
    public double customsCrossRatio(List<String> edgeIds) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return -1;
        }
        double total = 0;
        double cum = 0;
        double crossAt = -1;
        for (String eid : edgeIds) {
            RoadEdge e = findEdge(eid);
            if (e == null) {
                continue;
            }
            if (e.isCustoms()) {
                crossAt = cum; // 反复覆盖 → 最终留下最后一条口岸边（出境地）的位置
            }
            cum += e.getDistanceKm();
            total += e.getDistanceKm();
        }
        if (crossAt < 0 || total <= 0) {
            return -1;
        }
        return Math.min(1.0, crossAt / total);
    }

    /** 按进度比例取路线上的当前节点 ID（供「返回起点」估算折返里程），不在途时传负数即起点 */
    public String nodeAtProgress(List<String> nodeIds, List<String> edgeIds, double ratio) {
        return findCurrentNode(nodeIds, edgeIds, ratio);
    }

    /** 某边的展示名（口岸名剥掉「通道/通行/通关」后缀），查不到时退回边 ID 本身 */
    public String edgeLabel(String edgeId) {
        RoadEdge e = edgeId == null ? null : edgeById.get(edgeId);
        if (e == null || e.getName() == null || e.getName().isBlank()) {
            return edgeId;
        }
        return e.getName().replace("通道", "").replace("通行", "").replace("通关", "").trim();
    }

    /**
     * 两点间在当前风险口径下的里程/耗时。正常搜不出时降级再搜一次并标 {@code blocked=true}
     * ——折返路线本身也穿过熔断区，前端要如实提示减速慢行；彻底不可达返回 null。
     */
    public Map<String, Object> legSummary(String fromId, String toId) {
        if (fromId == null || toId == null) {
            return null;
        }
        if (fromId.equals(toId)) {
            return Map.of("fromId", fromId, "toId", toId, "distanceKm", 0.0, "hours", 0.0, "blocked", false);
        }
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        try {
            return legOf(shortestPath(fromId, toId, riskMap), fromId, toId, false);
        } catch (IllegalStateException blocked) {
            try {
                return legOf(shortestPath(fromId, toId, softenRiskMap(riskMap)), fromId, toId, true);
            } catch (Exception stillNothing) {
                return null;
            }
        } catch (Exception badRequest) {
            return null;
        }
    }

    private Map<String, Object> legOf(PathResult p, String fromId, String toId, boolean blocked) {
        return Map.of(
                "fromId", fromId, "toId", toId,
                "distanceKm", round(p.totalDistanceKm()), "hours", round(p.hours()),
                "blocked", blocked);
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
        // 与 planCandidates 同一份快照：命中缓存时不再重跑 25 轮 A*，
        // 「按所选候选开始导航」直接取回预览里那一条（2 核服务器上原本要 7~18s）
        CachedCandidates snap = ensureCandidates(originId, destinationId);
        PathResult baseline = snap.baseline;
        PathResult chosen;
        boolean softened = false;
        boolean allFused;
        if ("fastest".equals(choice)) {
            chosen = baseline; // 最快 = 无风险纯时间基准（沿线风险段仍会标注，供司机权衡）
            allFused = roadCorridorsAllFused(originId, destinationId);
        } else {
            CandidatePath picked = snap.byKey.get(choice);
            if (picked == null) {
                picked = snap.byKey.get("recommended"); // choice 缺失/失效时回落到推荐路线，而不是报错
            }
            if (picked == null) {
                // 理论上不会发生：buildCandidates 必产出 recommended。兜底用无风险基线，绝不 500
                picked = new CandidatePath(choice, "", "", baseline, false);
            }
            chosen = picked.path;
            softened = picked.softened;
            // 候选生成时已经跑过同一张风险图的判定，直接复用，不再重复一次注定失败的搜索
            allFused = softened;
        }
        RouteResponse resp = buildResponse(baseline, chosen, cargoType);
        resp.setCorridorAllFused(allFused);
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

    /** 多级绕行：多比例禁边 + 强制经由中间节点 + 局部权重扰动 + 强制不同口岸，最大限度生成不同走廊备选。
     *  各策略的搜索互相独立且只读（邻接表/风险图不可变），并行提交 planExecutor 后按原顺序收集，
     *  addIfNew 去重的输入序与原串行版一致 → 候选池输出完全不变，只是把 ~30 次 Dijkstra 摊到多核。 */
    private List<PathResult> findMultiAlternates(String originId, String destinationId,
                                                  Map<String, RiskSegment> riskMap, PathResult base) {
        List<PathResult> results = new ArrayList<>();
        List<String> ids = base.pathEdgeIds();
        List<String> nodes = base.pathNodeIds();
        int n = ids.size();
        if (n < 2) return results;
    
        List<Callable<PathResult>> tasks = new ArrayList<>();
    
        // 策略组 1: 禁走推荐路线全部边（找完全不同的走廊）
        tasks.add(() -> tryAlternate(originId, destinationId, riskMap, new HashSet<>(ids)));
    
        // 策略组 2: 多种比例禁走前缀（从起点侧绕开）——比例越细，越容易凑出不同走廊
        for (double ratio : new double[]{0.3, 0.5, 0.2, 0.7, 0.4, 0.6}) {
            int cut = Math.max(1, Math.min(n - 1, (int) Math.round(n * ratio)));
            Set<String> forbidden = new HashSet<>(ids.subList(0, cut));
            tasks.add(() -> tryAlternate(originId, destinationId, riskMap, forbidden));
        }
    
        // 策略组 3: 多种比例禁走后缀（从终点侧绕开）
        for (double ratio : new double[]{0.3, 0.5, 0.2, 0.4}) {
            int cut = Math.max(1, Math.min(n - 1, (int) Math.round(n * ratio)));
            Set<String> forbidden = new HashSet<>(ids.subList(n - cut, n));
            tasks.add(() -> tryAlternate(originId, destinationId, riskMap, forbidden));
        }
    
        // 策略组 4: 两端同时禁走（保留中段），逼出“两头都换走廊”的方案
        for (double ratio : new double[]{0.3, 0.45}) {
            int cut = Math.max(1, Math.min(n / 2, (int) Math.round(n * ratio)));
            Set<String> combo = new HashSet<>(ids.subList(0, cut));
            combo.addAll(ids.subList(n - cut, n));
            tasks.add(() -> tryAlternate(originId, destinationId, riskMap, combo));
        }
    
        // 策略组 5: 强制经由推荐路径上的中间节点（每 ~12% 采一个），逼出“同口岸不同走廊”的变体
        int step = Math.max(1, n / 8);
        for (int i = step; i < n; i += step) {
            String via = nodes.get(i);
            tasks.add(() -> pathVia(originId, via, destinationId, riskMap));
        }
    
        // 策略组 6: 局部权重扰动（把路径中段的边权放大），让算法自行寻找局部替代走廊
        int[][] perturbRanges = {{(int) (n * 0.15), (int) (n * 0.85)}, {(int) (n * 0.3), (int) (n * 0.7)}};
        for (int[] range : perturbRanges) {
            Map<String, Double> perturb = new HashMap<>();
            for (int i = Math.max(0, range[0]); i < Math.min(n, range[1]); i++) {
                perturb.put(ids.get(i), 8.0);
            }
            if (!perturb.isEmpty()) {
                tasks.add(() -> tryAlternate(originId, destinationId, riskMap, Set.of(), perturb));
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
            // 全量扫边构建口岸权重表也放进了并行任务（纯读，与搜索串行依赖无关）
            tasks.add(() -> {
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
                return tryAlternate(originId, destinationId, riskMap, Set.of(), portWeights);
            });
        }
    
        // 并行执行 + 按原顺序收集：单任务异常/不可达视为 null（与原 tryAlternate 内部 catch 语义一致）
        List<Future<PathResult>> futures = new ArrayList<>(tasks.size());
        for (Callable<PathResult> t : tasks) {
            futures.add(planExecutor.submit(t));
        }
        for (Future<PathResult> f : futures) {
            PathResult r = null;
            try {
                r = f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // 该策略搜索失败：与串行版返回 null 同样跳过
            }
            addIfNew(results, r);
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
                                            Map<String, RiskSegment> riskMap, String note,
                                            String originId, String destinationId, boolean softened) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("note", note);
        // 是否为「硬熔断降级放行」的兜底路线：此前只存在于后端内部记录里、从不下发，
        // 大屏图例写着「降级兜底（橙色虚线）」却永远不会亮，司机端也看不出自己选的是条穿灾区的路
        m.put("softened", softened);
        m.put("via", routeVia(p.pathNodeIds(), originId, destinationId));
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
    private String routeVia(List<String> nodeIds, String originId, String destinationId) {
        List<String> named = new ArrayList<>();
        for (String id : nodeIds) {
            // 排除起终点自身：此前按「南宁/河内」名称写死排除，
            // 换成 凭祥→河内、南宁→海防 等其它起终点时，起点/终点会漏进「途经」摘要卡。
            if (id.equals(originId) || id.equals(destinationId)) {
                continue;
            }
            RoadNode n = nodeById.get(id);
            if (n == null || n.getName() == null || n.getName().isBlank()) {
                continue;
            }
            named.add(n.getName().trim());
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
    private ResilientPath computeCurrentPath(String originId, String destinationId, PathResult baseline) {
        Map<String, RiskSegment> riskMap = weatherSimulator.currentRiskMap();
        CompletableFuture<ResilientPath> currentFuture = CompletableFuture.supplyAsync(
                () -> resilientPath(originId, destinationId, riskMap, baseline), planExecutor);
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
        // 上一轮拉取还在跑（上游慢、已走到 45s 超时边界）：直接给缓存视图，不再排队第二个任务
        if (!realPullInFlight.compareAndSet(false, true)) {
            return realWeatherView(pathNodeIds);
        }
        // 真实天气拉取放独立线程并设总超时：网络慢/离线时绝不阻塞路径规划（保证"毫秒级重算"卖点）
        // 线程池常驻复用，不再每调一次建一个（见 realPullExecutor 字段注释）
        try {
            List<String> path = pathNodeIds == null || pathNodeIds.isEmpty() ? null : pathNodeIds;
            Future<?> future;
            if (path == null) {
                future = realPullExecutor.submit((Runnable) this::pullRealWeather);
            } else {
                future = realPullExecutor.submit((Runnable) () -> pullRealWeatherForPath(path));
            }
            try {
                future.get(force ? 45 : 2, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
            } catch (Exception ignored) {
            }
        } finally {
            realPullInFlight.set(false);
        }
        lastRealSyncAt = now;
        return realWeatherView(pathNodeIds);
    }

    /** 进程关闭时释放常驻拉取线程（daemon 本不阻止退出，显式收干净便于测试与热重启） */
    @PreDestroy
    public void shutdownRealPullExecutor() {
        realPullExecutor.shutdownNow();
    }

    /**
     * 切换气象数据源后作废“最近一次实时同步”时间戳。
     * <p>
     * 屏幕上的数字属于另一个上游，不能再被 5 分钟 TTL 挡住 15 秒轮询；
     * 这里只放开 RouteService 这一层的 TTL，不绕过 RealWeatherService 的
     * 按源节流窗口与熔断（真正发不发请求仍由那两道闸决定）。
     */
    public void invalidateRealSync() {
        lastRealSyncAt = 0;
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

    /**
     * 只读地取「本程调度员实际选中的那条候选」的沿线节点序列；取不到返回 null，由调用方退回基准线。
     *
     * <p>为什么只读缓存、绝不在这里补算：AMRI 面板每 8~15 秒就要问一次沿线点，而一轮候选生成
     * 在 2 核服务器上实测要 7~18 秒；拖沙盘滑杆还会改变风险图签名、把快照判失效。
     * 在这里触发重算等于把「打分接口」变成「算路接口」，面板会整片卡死。
     * 宁可退回按基准线打分，也不能拖住调用方。</p>
     */
    public List<String> cachedCandidateNodeIds(String originId, String destinationId, String choice) {
        if (originId == null || originId.isBlank() || destinationId == null || destinationId.isBlank()
                || choice == null || choice.isBlank()) {
            return null;
        }
        CachedCandidates snap;
        synchronized (candidatesCache) {
            snap = candidatesCache.get(candidatesKey(originId, destinationId));
        }
        if (snap == null || snap.byKey == null) {
            return null;
        }
        CandidatePath picked = snap.byKey.get(choice);
        // 选中的那条已不在快照里（风险变了、候选重排）：不拿推荐线冒充用户选的那条，交回调用方兜底
        return picked == null ? null : picked.path.pathNodeIds();
    }

    /**
     * 按「本程选定路线」的节点序列取沿线实时天气。
     *
     * <p>与 {@link #syncRealWeatherForRoute} 的分工：那边锚定无天气基准最短路，保证不同时刻纵向可比；
     * 这边跟随调度员真正选中并画在地图上的那条候选——换一条经芒街的线，AMRI 就该给芒街打分。</p>
     *
     * <p>缓存里没有的沿线口岸（换走廊带进来的新口岸，如芒街/东兴）交给 {@link #schedulePathPull}
     * 后台补拉，**请求线程一律不等**：实测在打分接口里 force 同步拉一次要 21~49 秒
     * （AMRI 面板每 8~15 秒问一次，等于把面板与后端线程池一起拖死），
     * 而且 {@code future.get(45s)} 超时后 cancel(true) 会把这次拉取掐死，新点永远进不了缓存，
     * 下一轮又判「没覆盖」再拉一次——变成永久性的 49 秒阻塞。异步之后下一两次轮询就能看见新口岸。</p>
     */
    public List<Map<String, Object>> syncRealWeatherForPath(List<String> pathNodeIds) {
        if (pathNodeIds == null || pathNodeIds.isEmpty()) {
            return syncRealWeather(false);
        }
        if (!cachedPointsCover(pathNodeIds)) {
            schedulePathPull(pathNodeIds);
        }
        return syncRealWeather(false, pathNodeIds);
    }

    /** 同一条新路线的补拉最少隔这么久再试一次：上游拉不到时不能跟着轮询频率无限重试 */
    private static final long PATH_PULL_MIN_MS = 60_000;
    private volatile long lastPathPullAtMs = 0;

    /**
     * 后台补拉选定路线沿线的有名节点（发起后立即返回）。
     *
     * <p>限流靠三层现成机制，不新增上游额度：本方法自身 60 秒限频；与定时守护共用
     * {@code realPullInFlight} 单任务闸门（已有拉取在跑则直接放弃，不排队）；
     * 真不真发请求仍由 {@code RealWeatherService} 的按源最小间隔、跨源间隔与熔断决定。</p>
     */
    private void schedulePathPull(List<String> pathNodeIds) {
        long now = System.currentTimeMillis();
        if (now - lastPathPullAtMs < PATH_PULL_MIN_MS) {
            return;
        }
        if (!realPullInFlight.compareAndSet(false, true)) {
            return;
        }
        lastPathPullAtMs = now;
        List<String> ids = List.copyOf(pathNodeIds);
        try {
            realPullExecutor.submit(() -> {
                try {
                    pullRealWeatherForPath(ids);
                } catch (Exception ignored) {
                    // 补拉失败不影响打分：本轮退回已有沿线点，下个限频窗口再试
                } finally {
                    realPullInFlight.set(false);
                }
            });
        } catch (Exception e) {
            realPullInFlight.set(false); // 提交不上（线程池已关等）必须归还闸门，否则实时气象永久堵死
        }
    }

    /** 沿线有名节点（城市/口岸）是否都已在实时气象缓存里；OSM 无名中间节点本就不取数，不参与判定。 */
    private boolean cachedPointsCover(List<String> pathNodeIds) {
        Set<String> have = realWeatherService.cachedPoints().keySet();
        for (String id : pathNodeIds) {
            RoadNode n = nodeById.get(id);
            if (n == null || n.getName() == null || n.getName().isBlank()) {
                continue;
            }
            if (!have.contains(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 后台气象守护用：把多条关注走廊的沿线节点取并集，只发起一次实时气象同步。
     *
     * <p>为什么要合并：{@link #syncRealWeather} 开头会 clearRealRisks() 清掉全部实时风险再按
     * 本次拉取的节点重填。若逐条走廊各调一次，后一条会把前一条刚写入的风险清空——
     * 多条不同走廊之间会互相“抹掉”。这里取节点并集、一次性写全，规避该问题。</p>
     *
     * <p>单个 O/D 求路径失败（起终点不在路网等）时跳过该走廊，不影响其余；
     * 一条都解析不出来时退回全量有名节点拉取作兜底。</p>
     */
    public List<Map<String, Object>> syncRealWeatherForCorridors(boolean force, List<String[]> odPairs) {
        List<String> union = corridorUnion(odPairs);
        if (union.isEmpty()) {
            return syncRealWeather(force);
        }
        return syncRealWeather(force, union);
    }

    /**
     * 关注走廊的基线沿线节点并集（带缓存）。
     *
     * <p>缓存条件：走廊集合逐项相同且未过 {@code CORRIDOR_UNION_TTL_MS}。基线路径不含风险
     * 惩罚，路网未重载时同一 O/D 必然得到同一串节点，故复用与 correctness 无冲突；
     * 一条都解析不出来时不写缓存，下轮重试（避免走廊暂不可解析时把兜底状态钉住十分钟）。</p>
     */
    private List<String> corridorUnion(List<String[]> odPairs) {
        String key = corridorKey(odPairs);
        long now = System.currentTimeMillis();
        List<String> cached = corridorUnionValue;
        if (cached != null && !cached.isEmpty() && key.equals(corridorUnionKey)
                && now - corridorUnionAtMs < CORRIDOR_UNION_TTL_MS) {
            return cached;
        }
        LinkedHashSet<String> union = new LinkedHashSet<>();
        if (odPairs != null) {
            for (String[] od : odPairs) {
                if (od == null || od.length < 2) {
                    continue;
                }
                String o = od[0];
                String d = od[1];
                if (o == null || o.isBlank() || d == null || d.isBlank()) {
                    continue;
                }
                try {
                    union.addAll(shortestPath(o, d, Map.of()).pathNodeIds);
                } catch (Exception ignored) {
                    // 该走廊暂不可解析：跳过，保留其余走廊的检测
                }
            }
        }
        if (union.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(union);
        corridorUnionValue = out;
        corridorUnionKey = key;
        corridorUnionAtMs = now;
        return out;
    }

    /** 走廊集合签名：排序后拼接，使关注顺序变化（ConcurrentHashMap 遍历序不稳定）不会误判成“走廊变了” */
    private static String corridorKey(List<String[]> odPairs) {
        if (odPairs == null || odPairs.isEmpty()) {
            return "";
        }
        Set<String> norm = new java.util.TreeSet<>();
        for (String[] od : odPairs) {
            if (od == null || od.length < 2 || od[0] == null || od[1] == null) {
                continue;
            }
            norm.add(od[0] + ">" + od[1]);
        }
        return String.join("|", norm);
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
        List<RiskSegment> risks = new ArrayList<>();
        for (RoadEdge edge : edges) {
            RealWeatherService.WeatherPoint w1 = byNode.get(edge.getFromNodeId());
            RealWeatherService.WeatherPoint w2 = byNode.get(edge.getToNodeId());
            RiskSegment risk = buildRealRisk(edge, w1, w2);
            if (risk != null) {
                risks.add(risk);
            }
        }
        // 差异写入：本轮与上轮内容逐边相同时不通知 agent（守护 60s 一轮，
        // 旧的 clearRealRisks + 逐条 applyRealRisk 会每分钟无条件重算＋SSE 广播一整套路线）
        weatherSimulator.syncRealRisks(risks);
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
        // reasons 懒分配：本方法对全网 18.5 万条边逐条调用，绝大多数边无任何风险，
        // 以前每条边都先 new 一个 ArrayList，守护每轮白分配 18.5 万个对象
        StringBuilder reasons = null;
        if (w.precipitationMm() >= 50) {
            penalty = Math.max(penalty, 100);
            reasons = appendReason(reasons, "暴雨(降水" + w.precipitationMm() + "mm/h)");
        } else if (w.precipitationMm() >= 20) {
            penalty = Math.max(penalty, 20);
            reasons = appendReason(reasons, "大雨(降水" + w.precipitationMm() + "mm/h)");
        }
        if (w.windKph() >= 60) {
            penalty = Math.max(penalty, 50);
            reasons = appendReason(reasons, "大风(" + w.windKph() + "km/h)");
        } else if (w.windKph() >= 40) {
            penalty = Math.max(penalty, 15);
            reasons = appendReason(reasons, "阵风(" + w.windKph() + "km/h)");
        }
        if (w.visibilityM() < 100) {
            penalty = Math.max(penalty, 50);
            reasons = appendReason(reasons, "大雾(能见度<100m)");
        } else if (w.visibilityM() < 500) {
            penalty = Math.max(penalty, 20);
            reasons = appendReason(reasons, "轻雾(能见度<500m)");
        }
        if (w.temperatureC() >= 35) {
            penalty = Math.max(penalty, 8);
            reasons = appendReason(reasons, "高温(" + w.temperatureC() + "°C，冷链货损风险)");
        }
        // 台风/强对流：低气压 + 大风同时成立才算（判据集中在 TyphoonRiskRule，与沙盘模拟共用）
        TyphoonRiskRule.Verdict typhoon = TyphoonRiskRule.evaluate(w.pressureHpa(), w.windKph());
        if (typhoon.triggered()) {
            penalty = Math.max(penalty, typhoon.penalty());
            reasons = appendReason(reasons, typhoon.reason());
        }
        if (penalty <= 0) {
            return null;
        }
        String severity = penalty >= 100 ? "CRITICAL" : (penalty >= 30 ? "HIGH" : "MEDIUM");
        return new RiskSegment(edge.getId(), "实时气象:" + reasons, severity, penalty);
    }

    /** 风险原因累加（分号分隔，与旧 String.join(";", reasons) 逐字同格式） */
    private static StringBuilder appendReason(StringBuilder sb, String reason) {
        return sb == null ? new StringBuilder(reason) : sb.append(';').append(reason);
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

    private <T> T joinOrThrow(CompletableFuture<T> future) {
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

    /** Dijkstra 带边权重乘数：对指定边乘以 weightOverrides 中的系数，用于吸引/排斥特定口岸。
     *  实现上为 A*：队列按 f = 已走时间 + 剩余时间下界排序。启发式可采纳（不高估），
     *  因此出队终点时的最优路径与原纯 Dijkstra 完全一致，只是剪掉大部分无向扩散探索。 */
    private PathResult shortestPath(String originId, String destinationId, Map<String, RiskSegment> riskMap,
                                    Set<String> forbiddenEdgeIds, Map<String, Double> weightOverrides) {
        // 启发式缩放因子：本轮搜索可能遇到的最小权重乘数（口岸吸引 0.5 / 理论上的 <1 惩罚），
        // 不除回去会高估剩余代价、破坏可采纳性
        double minFactor = minWeightFactor(riskMap, weightOverrides);
        RoadNode destNode = nodeById.get(destinationId);
        Map<String, Double> distance = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Map<String, RoadEdge> prevEdge = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::priority));
        distance.put(originId, 0.0);
        queue.add(new NodeDistance(originId, 0.0, heuristic(originId, destNode, minFactor)));

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
                    queue.add(new NodeDistance(edge.getToNodeId(), candidate,
                            candidate + heuristic(edge.getToNodeId(), destNode, minFactor)));
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
            // 没有灾害边在当前路线上 → 从当前位置重算（不再从行程起点算，已走过的半截不该再出现在新路线里）
            return planRouteFast(currentNodeId, destinationId);
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
        boolean hitHazardStart = false;
        for (int i = 0; i < currentRouteNodeIds.size(); i++) {
            String nid = currentRouteNodeIds.get(i);
            if (nid.equals(currentNodeId)) foundCurrent = true;
            if (foundCurrent) {
                combinedNodeIds.add(nid);
                if (i < currentRouteEdgeIds.size()) {
                    combinedEdgeIds.add(currentRouteEdgeIds.get(i));
                }
                if (nid.equals(hazardStartNode)) {
                    hitHazardStart = true;
                    break;
                }
            }
        }
        // 防御：灾害段整段在车背后（推送延迟，车已开过灾害边）时，
        // 拼接会出现"先到终点再瞬移回背后绕行"的畸形路线；直接从当前位置全程重算。
        if (!hitHazardStart) {
            return planRouteFast(currentNodeId, destinationId);
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
            // 绕行失败 → 退化为从当前位置全程重算（原实现用行程起点，车在半路会拿到一条从起点出发的线）
            return planRouteFast(currentNodeId, destinationId);
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

    /** 本轮搜索可能遇到的最小权重乘数（口岸吸引可给 0.5，惩罚理论上也可 <1）：启发式必须乘它才不高估 */
    private double minWeightFactor(Map<String, RiskSegment> riskMap, Map<String, Double> weightOverrides) {
        double f = 1.0;
        for (Double v : weightOverrides.values()) {
            if (v != null && v > 0 && v < f) f = v;
        }
        for (RiskSegment r : riskMap.values()) {
            Double p = r == null ? null : r.getPenaltyMultiplier();
            if (p != null && p > 0 && p < f) f = p;
        }
        return f;
    }

    /** 剩余时间下界（h）：到终点的直线距离按全图最快限速折算再乘最小乘数；无坐标时返回 0 退化为纯 Dijkstra */
    private double heuristic(String nodeId, RoadNode destNode, double minFactor) {
        if (destNode == null || minFactor <= 0) {
            return 0;
        }
        RoadNode n = nodeById.get(nodeId);
        if (n == null) {
            return 0;
        }
        return GeocodeService.haversineKm(n.getLatitude(), n.getLongitude(),
                destNode.getLatitude(), destNode.getLongitude()) / maxEdgeSpeedKmh * minFactor;
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

    /** 队列元素：distance = 已走时间（g），priority = g + 剩余时间下界（f，排序键） */
    private record NodeDistance(String nodeId, double distance, double priority) {
    }

    private record PathResult(List<String> pathNodeIds, List<String> pathEdgeIds, double totalDistanceKm, double hours) {
    }
}
