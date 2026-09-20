package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 决策沙盘（演示第一/二幕）：
 * <p>
 * 官方气象接口推送「未来6h累计降雨」→ 规则引擎按熔断阈值自动熔断/恢复口岸路段；
 * 调度员可拖动滑块调整预测降雨量，实时观察决策边界（不是黑箱，是可交互的决策边界）。
 * <p>
 * 熔断 = 向 {@link WeatherSimulator} 注入 penalty=100（硬熔断，等效道路中断），
 * 注入后走既有链路：风险变化 → RouteAgentService 300ms 防抖 → 毫秒级重算 → SSE 推送大屏/司机端。
 * 滞后恢复（hysteresis）：熔断后需降至 阈值-5mm 以下才恢复，避免临界值附近抖动。
 * <p>
 * 注意：未熔断但降雨偏高（CAUTION）只作为沙盘叙事状态展示，不向风险图注入软惩罚——
 * 现有引擎的 penalty 是乘数（会连口岸通关时间一起放大），乘 10 会让绕行方案时效失真。
 */
@Service
public class DecisionSandboxService {

    /** 沙盘断面（口岸通道）。元数据用于风险研判智能体的决策解释。 */
    public static class Section {
        public final String id;
        public final String name;
        public final String edgeId;
        /** 未来6h累计降雨预测（mm），由"官方接口推送"或调度员滑块设置 */
        public volatile double forecastMm;
        public volatile boolean fused;
        /** 边坡土质 */
        public final String slopeSoil;
        /** 历史灾害记录 */
        public final String history;
        /** 当前边坡含水量（%） */
        public final double moisturePct;

        Section(String id, String name, String edgeId,
                String slopeSoil, String history, double moisturePct) {
            this.id = id;
            this.name = name;
            this.edgeId = edgeId;
            this.slopeSoil = slopeSoil;
            this.history = history;
            this.moisturePct = moisturePct;
        }
    }

    /** 芒街断面熔断阈值（剧本：芒街降雨量升至 70mm 以上将同步熔断） */
    public static final double MC_THRESHOLD_MM = 70.0;
    /** 滞后恢复幅度：熔断后需降至 阈值-5mm 以下（剧本：85熔断 / 75恢复） */
    public static final double HYSTERESIS_MM = 5.0;
    /** 降雨达到该值即进入"关注"状态（未熔断，仅叙事展示） */
    public static final double CAUTION_MM = 50.0;
    private static final double FUSE_PENALTY = 100.0;

    private final Section ygg = new Section("YGG", "友谊关口岸", "E4",
            "松散堆积体边坡", "历史同期（2023年8月）曾发生2次滑坡", 78.0);
    private final Section mc = new Section("MC", "芒街口岸", "E9",
            "岩质边坡，稳定性较好", "近3年无地质灾害记录", 45.0);

    private final WeatherSimulator weatherSimulator;
    private final DemoConfigService config;
    private final DecisionLogService decisionLog;
    private final WaterRiskEngine waterRiskEngine;

    /** 水运禁航是否已"熔断"（用于恢复判定，避免重复注入） */
    private volatile boolean waterFused = false;
    /** 水运禁航时给联运方案施加的惩罚系数（软熔断：抬高方案B成本而非删除） */
    public static final double WATER_FUSE_PENALTY = 100.0;

    /** 是否已收到"官方推送"（未推送前沙盘为空态，地图不注入任何风险） */
    private volatile boolean pushed = false;

    public DecisionSandboxService(WeatherSimulator weatherSimulator, DemoConfigService config,
                                  DecisionLogService decisionLog, WaterRiskEngine waterRiskEngine) {
        this.weatherSimulator = weatherSimulator;
        this.config = config;
        this.decisionLog = decisionLog;
        this.waterRiskEngine = waterRiskEngine;
    }

    /**
     * 模拟官方气象接口推送（第一幕 14:00 触发点）。
     * 剧本默认：友谊关 85mm（>80 熔断），芒街 62mm（未熔断）。
     */
    public synchronized Map<String, Object> pushOfficialForecast(double yggMm, double mcMm) {
        this.pushed = true;
        ygg.forecastMm = yggMm;
        mc.forecastMm = mcMm;
        decisionLog.log("WEATHER", "官方气象接口推送",
                String.format("友谊关未来6h累计降雨 %.0fmm，芒街 %.0fmm（数据来源：大赛官方气象接口）",
                        yggMm, mcMm));
        return evaluate("官方气象接口推送");
    }

    /** 调度员拖动滑块（第二幕）：null 表示该断面不变。 */
    public synchronized Map<String, Object> setRainfall(Double yggMm, Double mcMm) {
        this.pushed = true;
        if (yggMm != null) {
            ygg.forecastMm = yggMm;
        }
        if (mcMm != null) {
            mc.forecastMm = mcMm;
        }
        return evaluate("调度员调整决策沙盘");
    }

    /** 调度员调整水运通航条件（场景B：模拟水运禁航）。null 表示该项不变。 */
    public synchronized Map<String, Object> setWaterConditions(Double visibilityM, Double currentMs, Double waveM) {
        this.pushed = true;
        waterRiskEngine.setConditions(visibilityM, currentMs, waveM);
        decisionLog.log("WEATHER", "运河通航条件更新",
                String.format("能见度 %.0fm / 流速 %.1fm/s / 浪高 %.1fm",
                        visibilityM != null ? visibilityM : waterRiskEngine.state().get("visibilityM"),
                        currentMs != null ? currentMs : waterRiskEngine.state().get("currentSpeedMs"),
                        waveM != null ? waveM : waterRiskEngine.state().get("waveHeightM")));
        return evaluate("调度员调整运河通航条件");
    }

    /** 重新评估全部断面（平台配置调整阈值后也会调用）。 */
    public synchronized Map<String, Object> evaluate(String trigger) {
        applySection(ygg, config.fuseThresholdMm(), trigger);
        applySection(mc, MC_THRESHOLD_MM, trigger);
        applyWaterSection(trigger);
        return state();
    }

    /** 水运断面：禁航红线触发 → 抬高联运方案成本（软熔断），解除红线 → 恢复。 */
    private void applyWaterSection(String trigger) {
        boolean nowBlocked = waterRiskEngine.isWaterBlocked();
        if (nowBlocked == waterFused) {
            return;
        }
        if (nowBlocked) {
            waterFused = true;
            decisionLog.log("FUSE", "平陆运河禁航",
                    waterRiskEngine.blockedReason() + "（" + trigger + "），联运方案不可用");
        } else {
            waterFused = false;
            decisionLog.log("UNFUSE", "平陆运河恢复通航",
                    "通航条件回到禁航红线之上（" + trigger + "），联运方案恢复可用");
        }
    }

    private void applySection(Section s, double thresholdMm, String trigger) {
        // 熔断判定：达到阈值熔断；已熔断时需降至 阈值-滞后（含）以下才恢复
        // （剧本第二幕：85mm 熔断，拖回 75mm 即恢复通行）
        boolean nowFused = s.forecastMm >= thresholdMm
                || (s.fused && s.forecastMm > thresholdMm - HYSTERESIS_MM);
        if (nowFused == s.fused) {
            return;
        }
        if (nowFused) {
            s.fused = true;
            String reason = String.format("沙盘熔断:%s未来6h累计降雨量%.0fmm > %.0fmm熔断阈值（%s）",
                    s.name, s.forecastMm, thresholdMm, trigger);
            weatherSimulator.injectRisk(new RiskSegment(s.edgeId, reason, "CRITICAL", FUSE_PENALTY));
            decisionLog.log("FUSE", s.name + "熔断",
                    String.format("未来6h累计降雨量 %.0fmm > %.0fmm 阈值，penalty=100（%s）",
                            s.forecastMm, thresholdMm, trigger));
        } else {
            s.fused = false;
            weatherSimulator.clearRisk(s.edgeId);
            decisionLog.log("UNFUSE", s.name + "恢复通行",
                    String.format("当前预测降雨量 %.0fmm，低于 %.0fmm 恢复线（%s）",
                            s.forecastMm, thresholdMm - HYSTERESIS_MM, trigger));
        }
    }

    /** 清空沙盘（撤掉沙盘注入的全部风险 + 恢复运河正常通航，回到推送前状态）。 */
    public synchronized Map<String, Object> reset() {
        this.pushed = false;
        ygg.forecastMm = 0;
        mc.forecastMm = 0;
        ygg.fused = false;
        mc.fused = false;
        waterFused = false;
        waterRiskEngine.setConditions(1200.0, 1.8, 0.8);
        weatherSimulator.clearRisk(ygg.edgeId);
        weatherSimulator.clearRisk(mc.edgeId);
        decisionLog.log("SANDBOX", "沙盘重置", "已撤销沙盘注入的全部风险并恢复运河通航");
        return state();
    }

    public Section ygg() {
        return ygg;
    }

    public Section mc() {
        return mc;
    }

    public boolean pushed() {
        return pushed;
    }

    /** 水运是否禁航（供方案生成判定方向） */
    public boolean waterBlocked() {
        return waterRiskEngine.isWaterBlocked();
    }

    /** 水运禁航原因（弹窗文案） */
    public String waterBlockedReason() {
        return waterRiskEngine.blockedReason();
    }

    /**
     * 公路是否熔断（友谊关）。
     * 两种触发等价：① 沙盘雨量达阈值熔断；② 灾害场景注入（暴雨·友谊关 penalty=100 等效中断）。
     * 之前只看 ①，导致点击"模拟灾害注入"后 AI 分析/双向切换仍按未熔断处理。
     */
    public boolean roadBlocked() {
        return ygg.fused || injectedBlocking(ygg);
    }

    /** 某断面是否被灾害场景注入"等效熔断"（penalty≥100） */
    private boolean injectedBlocking(Section s) {
        com.example.aseanweatherlogistics.model.dto.RiskSegment injected =
                weatherSimulator.currentRiskMap().get(s.edgeId);
        return injected != null && injected.getPenaltyMultiplier() != null
                && injected.getPenaltyMultiplier() >= FUSE_PENALTY;
    }

    /** 断面上的注入风险（可能为 null）：供风险智能体解释用 */
    public com.example.aseanweatherlogistics.model.dto.RiskSegment injectedRisk(Section s) {
        return weatherSimulator.currentRiskMap().get(s.edgeId);
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pushed", pushed);
        m.put("dataSource", "大赛官方气象接口（GOWFS 3h 预报格点）");
        m.put("fuseThresholdMm", config.fuseThresholdMm());
        m.put("riskProfile", config.riskProfile());
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(sectionMap(ygg, config.fuseThresholdMm()));
        sections.add(sectionMap(mc, MC_THRESHOLD_MM));
        m.put("sections", sections);
        // 水运断面（场景B：公水联运的另一半）
        Map<String, Object> water = new LinkedHashMap<>(waterRiskEngine.state());
        water.put("id", "CANAL");
        water.put("name", "平陆运河");
        water.put("pushed", pushed);
        m.put("water", water);
        // 双向切换方向判定
        m.put("roadBlocked", roadBlocked());
        m.put("waterBlocked", waterBlocked());
        m.put("direction", direction());
        return m;
    }

    /** 双向切换方向：road_to_water（公路断了）/ water_to_road（水运禁航）/ dual_risk / normal。 */
    public String direction() {
        boolean rb = roadBlocked();
        boolean wb = waterBlocked();
        if (rb && wb) return "dual_risk";
        if (rb) return "road_to_water";
        if (wb) return "water_to_road";
        return "normal";
    }

    private Map<String, Object> sectionMap(Section s, double thresholdMm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("name", s.name);
        m.put("edgeId", s.edgeId);
        m.put("forecastMm", s.forecastMm);
        m.put("thresholdMm", thresholdMm);
        m.put("recoverBelowMm", thresholdMm - HYSTERESIS_MM);
        m.put("fused", s.fused);
        String status = !pushed ? "NO_DATA"
                : s.fused ? "FUSED" : (s.forecastMm >= CAUTION_MM ? "CAUTION" : "CLEAR");
        m.put("status", status);
        m.put("statusText", switch (status) {
            case "FUSED" -> "已熔断";
            case "CAUTION" -> "可用（降雨偏高，保持关注）";
            case "CLEAR" -> "通行正常";
            default -> "等待官方气象数据";
        });
        m.put("boundaryText", s.fused
                ? String.format("降雨量需降至 %.0fmm 以下才恢复通行", thresholdMm - HYSTERESIS_MM)
                : String.format("降雨量升至 %.0fmm 以上将熔断", thresholdMm));
        // 风险研判元数据
        m.put("slopeSoil", s.slopeSoil);
        m.put("history", s.history);
        m.put("moisturePct", s.moisturePct);
        m.put("riskLevel", s.fused ? "高" : (s.forecastMm >= CAUTION_MM ? "中" : "低"));
        return m;
    }
}
