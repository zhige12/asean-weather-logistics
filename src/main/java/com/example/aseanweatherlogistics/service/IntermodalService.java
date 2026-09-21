package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.DemoClock;
import java.util.ArrayList;
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
