package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.AmriRiskModel;
import com.example.aseanweatherlogistics.service.ContestForecastService;
import com.example.aseanweatherlogistics.service.DecisionSandboxService;
import com.example.aseanweatherlogistics.service.RealWeatherService;
import com.example.aseanweatherlogistics.service.RouteService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 气象专用风险模型 AMRI 的可解释面板接口（决赛任务2·气象专用模型）。
 *
 * <p>三个只读端点，全部「加法式」接入，不改动算路侧 {@code RouteService.buildRealRisk}
 * 的离散运营档位（那套档位参与内容签名退避与场景缓存，改动风险高）：
 * <ol>
 *   <li>{@code GET /api/risk/model}：下发模型规格——名称、公式、各灾种隶属度参数、
 *       严重度分档、标定曲线采样点，供前端绘制「物理量—风险」响应曲线；</li>
 *   <li>{@code GET /api/risk/model/live}：用沿线<b>实时</b>气象点算出各点的连续 AMRI 指数、
 *       档位与逐灾种贡献，并标出当前路线最不利点——把「这条路为什么红」拆成可核验的贡献条；</li>
 *   <li>{@code GET /api/risk/model/nowcast}：以 GOWFS 官方 3h 预报的多时效降雨为驱动量，
 *       逐时效推算 AMRI 指数的<b>短临演变序列</b>（风/能见度/气温沿用该点实况基线，被沙盘滑杆接管的点
 *       则取滑杆基线；只让降雨随预报推进），
 *       用于「未来 3/6 小时这条路线风险怎么变」的曲线。</li>
 * </ol>
 *
 * <p>数据来源与节流沿用既有链路：实时点走 {@link RouteService#syncRealWeatherForRoute}（自带按源节流/退避/熔断），
 * 预报走 {@link ContestForecastService#fetchRain6h}（30 分钟窗口 + 熔断）。上游不可用时如实返回
 * {@code available=false} 与原因，绝不把「没数据」粉饰成「无风险」。
 *
 * <p>与决策沙盘的联动：沙盘处于模拟模式且已推送时，{@link DecisionSandboxService#simWeatherOverlay()}
 * 会给出友谊关/芒街两断面的滑杆模拟值（与 AI 灾种卡同一叠加口径），这里把它叠加到对应气象点上，
 * 使「拖滑杆 → AMRI 工作点沿 S 曲线爬升」真实成立；被叠加的点逐个标 {@code sandboxSim=true}，
 * 绝不把模拟值冒充实测值。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskModelController {

    /** nowcast 面板最多展示的沿线点数（取风险最高的若干个，避免长走廊刷屏）。 */
    private static final int NOWCAST_TOP_N = 4;

    private final RouteService routeService;
    private final RealWeatherService realWeatherService;
    private final ContestForecastService contestForecastService;
    private final DecisionSandboxService sandbox;

    public RiskModelController(RouteService routeService,
                               RealWeatherService realWeatherService,
                               ContestForecastService contestForecastService,
                               DecisionSandboxService sandbox) {
        this.routeService = routeService;
        this.realWeatherService = realWeatherService;
        this.contestForecastService = contestForecastService;
        this.sandbox = sandbox;
    }

    /** 模型规格：名称/公式/参数表/分档/标定曲线。纯静态，无需气象数据即可绘制。 */
    @GetMapping("/model")
    public Map<String, Object> model() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", AmriRiskModel.describe());
        out.put("servedSource", realWeatherService.servedSource());
        out.put("servedFresh", realWeatherService.servedFresh());
        out.put("generatedAt", System.currentTimeMillis());
        return out;
    }

    /**
     * 实时工作点：用沿线当前气象算 AMRI 连续指数 + 档位 + 逐灾种贡献，标出当前路线最不利点。
     * 未传起终点时退回全量有名节点（与 /api/weather/real 一致）。
     * 传了 {@code routeKey}（调度大屏本程选定的那条候选）则按那条线的沿线点打分，
     * 不再固定按基准最短路——否则「派单选了走芒街的线」时 AMRI 仍只看见友谊关。
     */
    @GetMapping("/model/live")
    public Map<String, Object> live(@RequestParam(required = false) String originId,
                                    @RequestParam(required = false) String destinationId,
                                    @RequestParam(required = false) String routeKey) {
        List<String> routeNodes = routeService.cachedCandidateNodeIds(originId, destinationId, routeKey);
        List<Map<String, Object>> weather = weatherOnRoute(originId, destinationId, routeNodes);
        // 沙盘模拟模式下的滑杆叠加（与 AI 灾种卡同一数据通路）：友谊关/芒街两点取模拟值
        Map<String, Map<String, Object>> overlay = overlayByNode();
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> p : weather) {
            Map<String, Object> one = evalPoint(p, overlay.get(str(p.get("nodeId"))));
            if (one != null) {
                ranked.add(one);
            }
        }
        // 按指数降序，最不利点排在最前，界面直接取头部
        ranked.sort((a, b) -> Double.compare(num(b.get("index")), num(a.get("index"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", AmriRiskModel.NAME);
        out.put("count", ranked.size());
        // 本指数按哪条线打分：回显给界面，避免「界面画的是芒街线、分数却是友谊关线」这种对不上
        out.put("scoredRouteKey", routeNodes == null || routeNodes.isEmpty() ? null : routeKey);
        out.put("worst", ranked.isEmpty() ? null : ranked.get(0));
        out.put("points", ranked);
        out.put("servedSource", realWeatherService.servedSource());
        out.put("servedSourceLabel", sourceLabel(realWeatherService.servedSource()));
        out.put("servedFresh", realWeatherService.servedFresh());
        boolean simApplied = ranked.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("sandboxSim")));
        out.put("sandboxSim", simApplied);
        if (simApplied) {
            out.put("sandboxNote", "友谊关/芒街取沙盘滑杆模拟值（降雨为未来6h累计口径，与灾种卡同口径），其余点为实时观测");
        }
        // 沙盘滑杆已接管、但不在本程路线沿线点上的断面（如当前起终点走友谊关 E4、而滑杆推了芒街 E9）：
        // 它们不参与本指数——AMRI 是「路线」风险指数，给不在你路线上的口岸打分反而是错的。
        // 但必须如实列出，否则同一屏会出现「沙盘已熔断 / AMRI 低风险」这种看着自相矛盾的画面。
        List<Map<String, Object>> skipped = skippedSections(overlay, weather);
        if (!skipped.isEmpty()) {
            out.put("skippedSections", skipped);
        }
        out.put("generatedAt", System.currentTimeMillis());
        if (ranked.isEmpty()) {
            out.put("message", "暂无沿线实时气象点（数据源未就绪或已降级），无法计算工作点指数");
        }
        return out;
    }

    /**
     * 沿线气象点取数：已解析出本程选定路线（{@code routeNodes}）就按那条线取，
     * 否则（未选过 / 候选快照已过期）退回无天气基准最短路。
     *
     * <p>两个口径并存是故意的：基准线保证调度员没手动选路时分数纵向可比，
     * 而一旦他选了某条候选并把它画在地图上，「打分点」就必须与地图上那条线一致。</p>
     */
    private List<Map<String, Object>> weatherOnRoute(String originId, String destinationId, List<String> routeNodes) {
        if (routeNodes != null && !routeNodes.isEmpty()) {
            return routeService.syncRealWeatherForPath(routeNodes);
        }
        return routeService.syncRealWeatherForRoute(false, originId, destinationId);
    }

    /**
     * 短临预报序列：以官方 3h 预报的多时效降雨为驱动量，逐时效推算 AMRI 指数演变。
     * 风/能见度/气温沿用该点实况基线（被沙盘滑杆接管的点取滑杆基线），只让降雨随预报推进——
     * 这正是 nowcasting 关注的降水演变。
     */
    @GetMapping("/model/nowcast")
    public Map<String, Object> nowcast(@RequestParam(required = false) String originId,
                                       @RequestParam(required = false) String destinationId,
                                       @RequestParam(required = false) String routeKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", AmriRiskModel.NAME);
        out.put("generatedAt", System.currentTimeMillis());

        List<String> routeNodes = routeService.cachedCandidateNodeIds(originId, destinationId, routeKey);
        List<Map<String, Object>> weather = weatherOnRoute(originId, destinationId, routeNodes);
        out.put("scoredRouteKey", routeNodes == null || routeNodes.isEmpty() ? null : routeKey);
        if (weather.isEmpty()) {
            out.put("available", false);
            out.put("message", "无沿线气象基线，无法起报短临序列");
            return out;
        }
        // 沙盘叠加：被滑杆接管的点其风/能见度/气温基线改用模拟值（降雨仍由官方预报逐时效驱动，
        // 滑杆不能规定未来下多大雨——这点语义在回包注释里向界面说清）
        Map<String, Map<String, Object>> overlay = overlayByNode();
        // 用实况点坐标构造预报取数点位，并保留各点的风/能见度/气温基线，供逐时效合成 AMRI
        List<ContestForecastService.ForecastPoint> fps = new ArrayList<>();
        Map<String, Map<String, Object>> baseById = new LinkedHashMap<>();
        boolean simApplied = false;
        for (Map<String, Object> p : weather) {
            String id = str(p.get("nodeId"));
            if (id == null) {
                continue;
            }
            double lat = num(p.get("latitude"));
            double lon = num(p.get("longitude"));
            Map<String, Object> base = p;
            Map<String, Object> ov = overlay.get(id);
            if (ov != null) {
                base = new LinkedHashMap<>(p);
                base.put("windKph", ov.get("windKph"));
                base.put("visibilityM", ov.get("visibilityM"));
                base.put("temperatureC", ov.get("temperatureC"));
                simApplied = true;
            }
            fps.add(new ContestForecastService.ForecastPoint(id, str(base.get("nodeName")), lat, lon));
            baseById.put(id, base);
        }
        if (fps.isEmpty()) {
            out.put("available", false);
            out.put("message", "沿线气象点缺少有效坐标，无法起报");
            return out;
        }

        ContestForecastService.ForecastResult fr = contestForecastService.fetchRain6h(fps);
        out.put("forecastRunTime", fr.runTime());
        out.put("forecastSource", fr.sourceLabel());
        if (!fr.dataAvailable()) {
            out.put("available", false);
            out.put("message", fr.message());
            out.put("forecastStatus", contestForecastService.status());
            return out;
        }

        List<Integer> hours = fr.hours();
        out.put("leadHours", hours);
        // 每个点位一条序列：逐时效把 PRE(mm/时段) 折成 mm/h 平均，叠加实况基线风/能见度/气温算 AMRI
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : fr.hourlyMm().entrySet()) {
            String id = e.getKey();
            Map<String, Object> base = baseById.get(id);
            if (base == null) {
                continue;
            }
            double baseWind = num(base.get("windKph"));
            double baseVis = num(base.get("visibilityM"));
            double baseTemp = num(base.get("temperatureC"));
            List<Double> perHour = e.getValue();
            List<Map<String, Object>> steps = new ArrayList<>();
            double peakIndex = 0;
            String peakSeverity = AmriRiskModel.severity(0);
            for (int h = 0; h < hours.size() && h < perHour.size(); h++) {
                Double mm = perHour.get(h);
                // 该时效代表其后 3 小时时段：累计降水 / 3 = 平均雨强 mm/h
                double rainMmH = (mm == null || !Double.isFinite(mm))
                        ? Double.NaN : Math.max(0.0, mm / 3.0);
                AmriRiskModel.Result r = AmriRiskModel.evaluate(rainMmH, baseWind, baseVis, baseTemp);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("leadHour", hours.get(h));
                step.put("rainMm", round(mm));
                step.put("rainMmH", Double.isFinite(rainMmH) ? round(rainMmH) : null);
                step.put("index", r.index());
                step.put("severity", r.severity());
                steps.add(step);
                if (r.index() > peakIndex) {
                    peakIndex = r.index();
                    peakSeverity = r.severity();
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("nodeId", id);
            s.put("nodeName", str(base.get("nodeName")));
            s.put("peakIndex", round(peakIndex));
            s.put("peakSeverity", peakSeverity);
            s.put("steps", steps);
            series.add(s);
        }
        // 峰值指数降序，仅展示最值得关注的前若干点
        series.sort((a, b) -> Double.compare(num(b.get("peakIndex")), num(a.get("peakIndex"))));
        if (series.size() > NOWCAST_TOP_N) {
            series = new ArrayList<>(series.subList(0, NOWCAST_TOP_N));
        }
        out.put("available", true);
        out.put("series", series);
        out.put("sandboxSim", simApplied);
        if (simApplied) {
            out.put("sandboxNote", "沙盘滑杆只接管叠加过的点的现在工作点与风/能见度/气温基线；+3/+6h 降雨仍由官方预报驱动");
        }
        // 走廊级逐时效峰值（各时效取所有点的最大 AMRI），用于一根线看整条走廊的短临走势
        out.put("corridorPeak", corridorPeak(fr, baseById, hours));
        out.put("servedFresh", fr.servedFresh());
        out.put("forecastStatus", contestForecastService.status());
        return out;
    }

    /** 单点：取四要素算 AMRI，返回可展示的工作点视图；无有效要素则返回 null。
     *  simOverlay 非空时该点四要素改取沙盘滑杆值（逐点标 sandboxSim，不冒充实测）。 */
    private Map<String, Object> evalPoint(Map<String, Object> p, Map<String, Object> simOverlay) {
        Map<String, Object> src = p;
        if (simOverlay != null) {
            src = new LinkedHashMap<>(p);
            src.put("precipitationMm", simOverlay.get("precipitationMm"));
            src.put("windKph", simOverlay.get("windKph"));
            src.put("visibilityM", simOverlay.get("visibilityM"));
            src.put("temperatureC", simOverlay.get("temperatureC"));
        }
        double rain = num(src.get("precipitationMm"));
        double wind = num(src.get("windKph"));
        double vis = num(src.get("visibilityM"));
        double temp = num(src.get("temperatureC"));
        AmriRiskModel.Result r = AmriRiskModel.evaluate(rain, wind, vis, temp);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("nodeId", src.get("nodeId"));
        one.put("nodeName", src.get("nodeName"));
        one.put("rainMmH", round(rain));
        one.put("windKph", round(wind));
        one.put("visibilityM", round(vis));
        one.put("temperatureC", round(temp));
        one.put("index", r.index());
        one.put("severity", r.severity());
        one.put("contributions", r.contributions());
        one.put("sandboxSim", simOverlay != null);
        return one;
    }

    /** 走廊级逐时效峰值：每个时效取所有点位里最高的 AMRI 指数，画一根「整条走廊最不利」走势线。 */
    private List<Map<String, Object>> corridorPeak(ContestForecastService.ForecastResult fr,
                                                   Map<String, Map<String, Object>> baseById,
                                                   List<Integer> hours) {
        List<Map<String, Object>> peak = new ArrayList<>();
        for (int h = 0; h < hours.size(); h++) {
            double best = 0;
            String bestNode = null;
            for (Map.Entry<String, List<Double>> e : fr.hourlyMm().entrySet()) {
                Map<String, Object> base = baseById.get(e.getKey());
                List<Double> perHour = e.getValue();
                if (base == null || h >= perHour.size()) {
                    continue;
                }
                Double mm = perHour.get(h);
                double rainMmH = (mm == null || !Double.isFinite(mm)) ? Double.NaN : Math.max(0.0, mm / 3.0);
                AmriRiskModel.Result r = AmriRiskModel.evaluate(rainMmH,
                        num(base.get("windKph")), num(base.get("visibilityM")), num(base.get("temperatureC")));
                if (r.index() > best) {
                    best = r.index();
                    bestNode = str(base.get("nodeName"));
                }
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("leadHour", hours.get(h));
            one.put("index", round(best));
            one.put("severity", AmriRiskModel.severity(best));
            one.put("worstNode", bestNode);
            peak.add(one);
        }
        return peak;
    }

    /** 沙盘模拟叠加按 nodeId 索引（仅模拟模式且已推送时非空）；取数失败退回空图，不影响实况结果。 */
    private Map<String, Map<String, Object>> overlayByNode() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> w : sandbox.simWeatherOverlay().values()) {
                Object id = w.get("nodeId");
                if (id != null) {
                    out.put(String.valueOf(id), w);
                }
            }
        } catch (Exception ignored) {
            // 沙盘不可用时静默退回纯实况口径
        }
        return out;
    }

    /**
     * 找出「沙盘滑杆已接管、但不在本程路线沿线气象点里」的断面：overlay 有、weather 无。
     *
     * <p>典型场景：起终点走友谊关（E4）而调度员拖了芒街（E9）的滑杆——芒街那边确实会熔断，
     * 但熔断的是另一条边的通行性，与本程路线的 AMRI 无关，所以界面必须单独交代而不是静默丢弃，
     * 否则会被读成「调了没反应」或「面板坏了」。措辞与 {@code DecisionOrchestrator} 的
     * 「已熔断，但不在本程路线上」保持同一口径。
     */
    private static List<Map<String, Object>> skippedSections(Map<String, Map<String, Object>> overlay,
                                                             List<Map<String, Object>> weather) {
        Set<String> onRoute = new LinkedHashSet<>();
        for (Map<String, Object> p : weather) {
            String id = str(p.get("nodeId"));
            if (id != null) {
                onRoute.add(id);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : overlay.entrySet()) {
            if (onRoute.contains(e.getKey())) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("nodeId", e.getKey());
            one.put("nodeName", e.getValue().get("nodeName"));
            out.add(one);
        }
        return out;
    }

    private String sourceLabel(String src) {
        if (src == null || src.isBlank() || "none".equals(src)) {
            return "无（缓存或降级）";
        }
        return src;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Object round(Object v) {
        if (v == null) {
            return null;
        }
        double d = v instanceof Number n ? n.doubleValue() : Double.NaN;
        if (!Double.isFinite(d)) {
            return null;
        }
        return Math.round(d * 10.0) / 10.0;
    }
}
