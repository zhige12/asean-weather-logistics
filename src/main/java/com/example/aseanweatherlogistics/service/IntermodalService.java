package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.DemoClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 公水联运方案模型（演示第三/四幕 · 方案B）：
 * 南宁仓库 → 南宁港六景作业区（公路）→ 平陆运河（水运）→ 钦州港 → 海运 → 越南海防港 → 河内仓库。
 * <p>
 * 段时/段成本为策展的演示参数（对齐剧本：总时约 基准+0.5h、总成本约 基准-1200元），
 * 后续接入真实航运时刻表与运价后可直接替换 LEGS 数据源，计算结构不变。
 * 公路侧成本模型：18 吨冷链车 10 元/km（含冷藏机组能耗）+ 口岸杂费 500 元。
 */
@Service
public class IntermodalService {

    /** 越南段路网规划：仅用于取 海防→河内 的真实道路几何（水运段无路网，用策展中心线） */
    private final RouteService routeService;

    public IntermodalService(RouteService routeService) {
        this.routeService = routeService;
    }

    /**
     * 走廊几何策展中心线（[lat,lng]），六景起点与路网节点 LJ 同点（天地图地理编码实测码头岸线），
     * 保证公路线与走廊线无缝相接。
     * 平陆运河真实走向：郁江六景码头顺江下 → 西津库区平塘江口（运河零起点，横州新福镇）→
     * 平塘江谷地南切 → 马道枢纽（灵山旧州镇）→ 企石枢纽（灵山陆屋镇）接钦江 →
     * 钦江干流西南下 → 钦州城区 → 茅尾海 → 钦州港，约 134km。
     * 转折点均取沿江/沿谷城镇实测坐标，避免中心线斜穿山脊。
     */
    private static final double[][] CANAL_PATH = {
            {22.868990, 108.886690},  // 南宁港六景作业区码头（郁江岸线，地理编码实测）
            {22.800000, 108.920000},  // 郁江弯道（西津库区北段）
            {22.700000, 108.950000},  // 西津库区中段
            {22.560000, 108.960000},  // 平塘江口（运河零起点，新福镇北岸）
            {22.524669, 108.958668},  // 新福镇段（平塘江谷地，地理编码实测）
            {22.398740, 108.937196},  // 马道枢纽（灵山县旧州镇，地理编码实测）
            {22.280464, 108.947064},  // 企石枢纽（灵山县陆屋镇，接钦江，地理编码实测）
            {22.100000, 108.860000},  // 钦江干流（青年枢纽段）
            {21.983856, 108.650577},  // 钦江钦州城区段（地理编码实测）
            {21.838333, 108.535000},  // 茅尾海（地理编码实测）
            {21.776000, 108.534000}   // 钦州港出海口
    };

    /** 北部湾海运：钦州港 → 海防港（与路网节点 HP 同点收尾），沿湾中线近似 */
    private static final double[][] SEA_PATH = {
            {21.776000, 108.534000},  // 钦州港码头
            {21.350000, 108.100000},  // 出湾向东南
            {20.980000, 107.550000},  // 湾口航道
            {20.720000, 107.020000},  // 白藤外海进港航道
            {20.846041, 106.691518}   // 越南海防港
    };

    /** 联运段。mode: 公路/换装/运河/海运 */
    public record Leg(String mode, String from, String to, double km, double hours,
                      double costYuan, String note) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode);
            m.put("from", from);
            m.put("to", to);
            m.put("km", km);
            m.put("hours", hours);
            m.put("costYuan", costYuan);
            m.put("note", note);
            return m;
        }
    }

    /** 平陆运河联运段（演示参数，可标定） */
    public static final List<Leg> LEGS = List.of(
            new Leg("公路", "南宁仓库", "南宁港六景作业区", 110, 1.5, 660,
                    "18吨冷链车直取六景港区，{arriveBy} 前抵达即可衔接当班船期"),
            new Leg("换装", "六景作业区", "滚装船", 0, 0.8, 300,
                    "整车滚装上船+加固；滚装段专项运输险已含；司机随船安排休息舱位"),
            new Leg("运河", "六景", "钦州港", 134, 2.5, 400,
                    "平陆运河（马道/企石/青年枢纽），过闸费当前免征"),
            new Leg("海运", "钦州港", "越南海防港", 330, 3.0, 1200,
                    "北部湾近洋支线快船，舱位已锁定"),
            new Leg("公路", "海防港", "河内仓库", 100, 1.2, 600,
                    "越南侧短驳，由越南合作车队承运")
    );

    /** 公路成本模型：元/km（18吨冷链车） */
    public static final double ROAD_COST_PER_KM = 10.0;

    /** 走廊几何懒缓存：除越南段外全部静态，首次计算后复用 */
    private volatile Map<String, Object> corridorCache;

    /**
     * 公水联运「后续走廊」几何总览（司机公路段到南宁港为止，之后的运河/海运/越南公路仅供图上预览）：
     * 水运两段无路网，用上面策展中心线；越南公路段（海防→河内）走路网 Dijkstra 真实道路，
     * 失败退化为 海防-海阳-河内 直线。供 GET /api/canal/corridor 使用。
     */
    public Map<String, Object> corridor() {
        Map<String, Object> cached = corridorCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (corridorCache != null) return corridorCache;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "intermodal-corridor");
            List<Map<String, Object>> legs = new ArrayList<>();
            legs.add(corridorLeg("canal", "平陆运河（六景 → 钦州港）", LEGS.get(2), Arrays.asList(CANAL_PATH)));
            legs.add(corridorLeg("sea", "北部湾海运（钦州港 → 海防港）", LEGS.get(3), Arrays.asList(SEA_PATH)));
            legs.add(corridorLeg("road", "越南侧公路（海防港 → 河内）", LEGS.get(4), vnRoadPath()));
            out.put("legs", legs);
            out.put("nodes", List.of(
                    corridorNode("南宁港六景作业区", "交接·滚装上船", new double[]{22.868990, 108.886690}, "#f39c12", "⚓"),
                    corridorNode("钦州港", "运河转海运", new double[]{21.776000, 108.534000}, "#0ea5e9", "🛳"),
                    corridorNode("海防港", "卸船·海关", new double[]{20.846041, 106.691518}, "#06b6d4", "⚓"),
                    corridorNode("河内仓库", "终点（越方短驳承运）", new double[]{21.028521, 105.853742}, "#f59e0b", "🏭")
            ));
            Map<String, Object> handover = new LinkedHashMap<>();
            handover.put("until", "南宁港六景作业区");
            handover.put("note", "司机公路段仅到南宁港；后续走廊为联运总览，不参与导航播报与进度计算");
            out.put("driverHandover", handover);
            corridorCache = out;
            return out;
        }
    }

    /** 海防→河内真实道路几何；路网不可达时退化为三城直线（海阳为中继点） */
    private List<double[]> vnRoadPath() {
        try {
            List<double[]> coords = routeService.planRouteFast("HP", "HN").getPathCoords();
            if (coords != null && coords.size() > 1) return coords;
        } catch (Exception ignored) {
            // 演示兜底，不阻断走廊返回
        }
        return List.of(
                new double[]{20.846041, 106.691518},
                new double[]{20.940765, 106.336731},
                new double[]{21.028521, 105.853742});
    }

    private Map<String, Object> corridorLeg(String mode, String name, Leg leg, List<double[]> path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("name", name);
        m.put("from", leg.from());
        m.put("to", leg.to());
        m.put("km", leg.km());
        m.put("hours", leg.hours());
        m.put("note", leg.note());
        m.put("path", path);
        return m;
    }

    private Map<String, Object> corridorNode(String name, String role, double[] pos, String color, String icon) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("role", role);
        n.put("pos", pos);
        n.put("color", color);
        n.put("icon", icon);
        return n;
    }
    /** 公路口岸杂费（元/次） */
    public static final double ROAD_CUSTOMS_FEE_YUAN = 500.0;

    public double totalHours() {
        return LEGS.stream().mapToDouble(Leg::hours).sum();
    }

    public double totalCostYuan() {
        return LEGS.stream().mapToDouble(Leg::costYuan).sum();
    }

    /** 公路方案总成本（元） */
    public double roadCostYuan(double roadKm) {
        return roadKm * ROAD_COST_PER_KM + ROAD_CUSTOMS_FEE_YUAN;
    }

    /**
     * 生成方案B（公水联运）对比卡。
     *
     * @param roadBaselineKm    公路基准里程（来自真实路网 Dijkstra）
     * @param roadBaselineHours 公路基准时长（来自真实路网 Dijkstra）
     */
    public Map<String, Object> plan(double roadBaselineKm, double roadBaselineHours) {
        double intermodalHours = totalHours();
        double intermodalCost = totalCostYuan();
        double roadCost = roadCostYuan(roadBaselineKm);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "B");
        m.put("type", "intermodal");
        m.put("name", "公水联运（平陆运河）");
        m.put("route", "南宁 → 南宁港六景作业区 → 平陆运河 → 钦州港 → 海运 → 越南海防港 → 河内");
        List<Map<String, Object>> legs = new ArrayList<>();
        // 段说明里的钟点用占位符在生成卡片时替换：LEGS 是静态模板（类加载时定型），
        // 写死时刻会与演示实际时间脱节，也与通知里的时刻对不上。
        DemoClock.Boarding boarding = DemoClock.boarding();
        for (Leg leg : LEGS) {
            Map<String, Object> lm = leg.toMap();
            lm.put("note", String.valueOf(lm.get("note"))
                    .replace("{arriveBy}", boarding.arriveBy())
                    .replace("{boarding}", boarding.start()));
            legs.add(lm);
        }
        m.put("legs", legs);
        m.put("totalHours", round1(intermodalHours));
        m.put("totalCostYuan", round0(intermodalCost));
        m.put("extraHoursVsRoad", round1(intermodalHours - roadBaselineHours));
        m.put("costDeltaVsRoadYuan", round0(intermodalCost - roadCost));
        // 与方案A/C 对齐的通用字段，前端方案对比卡片可直接读取
        m.put("extraHours", round1(intermodalHours - roadBaselineHours));
        m.put("costDeltaYuan", round0(intermodalCost - roadCost));
        m.put("damageRisk", "低");
        m.put("damageNote", "水运段恒温舱+免颠簸，冷链货损风险低；司机随船按出勤计工时");
        m.put("roadRefKm", round1(roadBaselineKm));
        m.put("roadRefHours", round1(roadBaselineHours));
        m.put("roadRefCostYuan", round0(roadCost));
        m.put("canalTollNote", "平陆运河过闸费当前免征");
        // ---- 多式联运“一口价”（计划书 §4.4）----
        // 货主面对单一打包总价，无需分别对接公路/港口/船公司；按承运方拆分只为展示“一口价”背后的多方构成。
        double roadParty = 0, portParty = 0, waterParty = 0;
        for (Leg leg : LEGS) {
            switch (leg.mode()) {
                case "公路" -> roadParty += leg.costYuan();
                case "换装" -> portParty += leg.costYuan();
                default -> waterParty += leg.costYuan(); // 运河 + 海运
            }
        }
        List<Map<String, Object>> flatPriceParties = new ArrayList<>();
        flatPriceParties.add(party("公路承运车队", "南宁短驳 + 越南短驳", roadParty));
        flatPriceParties.add(party("港口滚装作业", "六景作业区整车滚装+加固", portParty));
        flatPriceParties.add(party("船公司", "平陆运河过闸 + 北部湾海运", waterParty));
        m.put("flatPriceYuan", round0(intermodalCost));
        m.put("flatPriceParties", flatPriceParties);
        m.put("flatPriceNote", "多式联运“一口价”：公路短驳 + 港口滚装 + 运河过闸 + 海运舱位打包结算，"
                + "货主对接单一价格与单一合同，无需分别对接公路/港口/船公司（过闸费免征已含）。");
        return m;
    }

    private static Map<String, Object> party(String name, String item, double costYuan) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("party", name);
        p.put("item", item);
        p.put("costYuan", round0(costYuan));
        return p;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round0(double v) {
        return Math.round(v);
    }
}
