package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.AmriRiskModel;
import com.example.aseanweatherlogistics.service.ContestForecastService;
import com.example.aseanweatherlogistics.service.RealWeatherService;
import com.example.aseanweatherlogistics.service.RouteService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       档位与逐灾种贡献，并标出全走廊最不利点——把「这条路为什么红」拆成可核验的贡献条；</li>
 *   <li>{@code GET /api/risk/model/nowcast}：以 GOWFS 官方 3h 预报的多时效降雨为驱动量，
 *       逐时效推算 AMRI 指数的<b>短临演变序列</b>（风/能见度/气温沿用实况基线，只让降雨随预报推进），
 *       用于「未来 3/6 小时这条走廊风险怎么变」的曲线。</li>
 * </ol>
 *
 * <p>数据来源与节流沿用既有链路：实时点走 {@link RouteService#syncRealWeatherForRoute}（自带按源节流/退避/熔断），
 * 预报走 {@link ContestForecastService#fetchRain6h}（30 分钟窗口 + 熔断）。上游不可用时如实返回
 * {@code available=false} 与原因，绝不把「没数据」粉饰成「无风险」。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskModelController {

    /** nowcast 面板最多展示的沿线点数（取风险最高的若干个，避免长走廊刷屏）。 */
    private static final int NOWCAST_TOP_N = 4;

    private final RouteService routeService;
    private final RealWeatherService realWeatherService;
    private final ContestForecastService contestForecastService;

    public RiskModelController(RouteService routeService,
                               RealWeatherService realWeatherService,
                               ContestForecastService contestForecastService) {
        this.routeService = routeService;
        this.realWeatherService = realWeatherService;
        this.contestForecastService = contestForecastService;
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
     * 实时工作点：用沿线当前气象算 AMRI 连续指数 + 档位 + 逐灾种贡献，标出全走廊最不利点。
     * 未传起终点时退回全量有名节点（与 /api/weather/real 一致）。
     */
    @GetMapping("/model/live")
    public Map<String, Object> live(@RequestParam(required = false) String originId,
                                    @RequestParam(required = false) String destinationId) {
        List<Map<String, Object>> weather = routeService.syncRealWeatherForRoute(false, originId, destinationId);
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> p : weather) {
            Map<String, Object> one = evalPoint(p);
            if (one != null) {
                ranked.add(one);
            }
        }
        // 按指数降序，最不利点排在最前，界面直接取头部
        ranked.sort((a, b) -> Double.compare(num(b.get("index")), num(a.get("index"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", AmriRiskModel.NAME);
        out.put("count", ranked.size());
        out.put("worst", ranked.isEmpty() ? null : ranked.get(0));
        out.put("points", ranked);
        out.put("servedSource", realWeatherService.servedSource());
        out.put("servedSourceLabel", sourceLabel(realWeatherService.servedSource()));
        out.put("servedFresh", realWeatherService.servedFresh());
        out.put("generatedAt", System.currentTimeMillis());
        if (ranked.isEmpty()) {
            out.put("message", "暂无沿线实时气象点（数据源未就绪或已降级），无法计算工作点指数");
        }
        return out;
    }

    /**
     * 短临预报序列：以官方 3h 预报的多时效降雨为驱动量，逐时效推算 AMRI 指数演变。
     * 风/能见度/气温沿用该点实况基线，只让降雨随预报推进——这正是 nowcasting 关注的降水演变。
     */
    @GetMapping("/model/nowcast")
    public Map<String, Object> nowcast(@RequestParam(required = false) String originId,
                                       @RequestParam(required = false) String destinationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", AmriRiskModel.NAME);
        out.put("generatedAt", System.currentTimeMillis());

        List<Map<String, Object>> weather = routeService.syncRealWeatherForRoute(false, originId, destinationId);
        if (weather.isEmpty()) {
            out.put("available", false);
            out.put("message", "无沿线气象基线，无法起报短临序列");
            return out;
        }
        // 用实况点坐标构造预报取数点位，并保留各点的风/能见度/气温基线，供逐时效合成 AMRI
        List<ContestForecastService.ForecastPoint> fps = new ArrayList<>();
        Map<String, Map<String, Object>> baseById = new LinkedHashMap<>();
        for (Map<String, Object> p : weather) {
            String id = str(p.get("nodeId"));
            if (id == null) {
                continue;
            }
            double lat = num(p.get("latitude"));
            double lon = num(p.get("longitude"));
            fps.add(new ContestForecastService.ForecastPoint(id, str(p.get("nodeName")), lat, lon));
            baseById.put(id, p);
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
        // 走廊级逐时效峰值（各时效取所有点的最大 AMRI），用于一根线看整条走廊的短临走势
        out.put("corridorPeak", corridorPeak(fr, baseById, hours));
        out.put("servedFresh", fr.servedFresh());
        out.put("forecastStatus", contestForecastService.status());
        return out;
    }

    /** 单点：取四要素算 AMRI，返回可展示的工作点视图；无有效要素则返回 null。 */
    private Map<String, Object> evalPoint(Map<String, Object> p) {
        double rain = num(p.get("precipitationMm"));
        double wind = num(p.get("windKph"));
        double vis = num(p.get("visibilityM"));
        double temp = num(p.get("temperatureC"));
        AmriRiskModel.Result r = AmriRiskModel.evaluate(rain, wind, vis, temp);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("nodeId", p.get("nodeId"));
        one.put("nodeName", p.get("nodeName"));
        one.put("rainMmH", round(rain));
        one.put("windKph", round(wind));
        one.put("visibilityM", round(vis));
        one.put("temperatureC", round(temp));
        one.put("index", r.index());
        one.put("severity", r.severity());
        one.put("contributions", r.contributions());
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
