package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        /** 断面代表点坐标：GOWFS 预报按点位取值用（取口岸所在格点） */
        public final double lat;
        public final double lon;
        /** 未来6h累计降雨预测（mm），由"官方接口推送"或调度员滑块设置 */
        public volatile double forecastMm;
        public volatile boolean fused;
        /**
         * 台风模拟状态：{@code typhoonActive=false} 表示未模拟台风。
         * 触发后与降雨熔断共用同一个 edgeId 注入——两者取更严重者，
         * 解除时需确认另一个成因也已消失，避免"清台风把降雨熔断一起清掉"。
         */
        public volatile boolean typhoonActive = false;
        public volatile double typhoonPressureHpa = 1013.0;
        public volatile double typhoonWindKph = 0.0;
        /** 边坡土质 */
        public final String slopeSoil;
        /** 历史灾害记录 */
        public final String history;
        /** 当前边坡含水量（%） */
        public final double moisturePct;

        Section(String id, String name, String edgeId, double lat, double lon,
                String slopeSoil, String history, double moisturePct) {
            this.id = id;
            this.name = name;
            this.edgeId = edgeId;
            this.lat = lat;
            this.lon = lon;
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

    /** 断面代表点坐标取自 data/place-names.json（友谊关 / 芒街），与地图标注同源 */
    private final Section ygg = new Section("YGG", "友谊关口岸", "E4", 21.977838, 106.714466,
            "松散堆积体边坡", "历史同期（2023年8月）曾发生2次滑坡", 78.0);
    private final Section mc = new Section("MC", "芒街口岸", "E9", 21.5275, 107.9675,
            "岩质边坡，稳定性较好", "近3年无地质灾害记录", 45.0);

    /** 降雨数据来源：真实 GOWFS 预报 / 模拟参数。界面如实标注，不把模拟值说成真实数据 */
    public static final String RAIN_SOURCE_LIVE = "GOWFS 预报（真实）";
    public static final String RAIN_SOURCE_SIM = "模拟参数（可调）";
    @Deprecated
    public static final String RAIN_SOURCE_SCRIPT = RAIN_SOURCE_SIM;
    @Deprecated
    public static final String RAIN_SOURCE_GOWFS = RAIN_SOURCE_LIVE;

    /**
     * 沙盘模式：{@code live}=真实数据（只读，不可调）；{@code sim}=模拟模式（参数可调）。
     * <p>
     * 真实是默认态：初始与每次拉取 GOWFS 后都回到 live，气象参数只读不可拖动。
     * 只有显式点击"模拟"才进入 sim，此时参数可拖动，拖到阈值即自动涌现灾害
     * （公路熔断 / 水运禁航），判定逻辑与真实数据走完全相同的规则。
     */
    public static final String MODE_LIVE = "live";
    public static final String MODE_SIM = "sim";
    private volatile String mode = MODE_LIVE;

    /** 可调气象参数（模拟模式下生效） */
    public record SimParams(double yggRainMm, double mcRainMm,
                            double yggPressureHpa, double yggWindKph,
                            double mcPressureHpa, double mcWindKph) {
    }

    /** 模拟参数初值：取"什么都不触发"的正常状态，避免一进模拟就满屏熔断 */
    private volatile SimParams sim = new SimParams(0, 0, 1013, 20, 1013, 20);
    private volatile String rainSource = RAIN_SOURCE_SCRIPT;
    private volatile String rainDetail = null;
    /**
     * 本次会话是否成功拉到过 GOWFS 真实预报。
     * 前端「模拟」按钮据此显隐：没点过/没拉到 GOWFS 前不出现，
     * 避免没见过真实值就直接进模拟、把模拟值当成真实值。
     */
    private volatile boolean liveLoaded = false;
    /** 最近一次 GOWFS 真实预报返回的全要素（断面id → 要素名 → 值），仅真实取数时存在 */
    private volatile Map<String, Map<String, Double>> liveElements = Map.of();
    /** 最近一次真实预报的风向中文方位（断面id → 方位名），仅真实取数时存在 */
    private volatile Map<String, String> liveWindText = Map.of();

    private final WeatherSimulator weatherSimulator;
    private final DemoConfigService config;
    private final DecisionLogService decisionLog;
    private final WaterRiskEngine waterRiskEngine;
    private final RouteAgentService routeAgentService;
    private final TributaryRiskService tributaryRiskService;
    private final ContestForecastService forecastService;

    /** 水运禁航是否已"熔断"（用于恢复判定，避免重复注入） */
    private volatile boolean waterFused = false;
    /** 水运禁航时给联运方案施加的惩罚系数（软熔断：抬高方案B成本而非删除） */
    public static final double WATER_FUSE_PENALTY = 100.0;

    /** 是否已收到"官方推送"（未推送前沙盘为空态，地图不注入任何风险） */
    private volatile boolean pushed = false;

    public DecisionSandboxService(WeatherSimulator weatherSimulator, DemoConfigService config,
                                  DecisionLogService decisionLog, WaterRiskEngine waterRiskEngine,
                                  RouteAgentService routeAgentService, TributaryRiskService tributaryRiskService,
                                  ContestForecastService forecastService) {
        this.weatherSimulator = weatherSimulator;
        this.config = config;
        this.decisionLog = decisionLog;
        this.waterRiskEngine = waterRiskEngine;
        this.routeAgentService = routeAgentService;
        this.tributaryRiskService = tributaryRiskService;
        this.forecastService = forecastService;
    }

    /**
     * 模拟官方气象接口推送（第一幕 14:00 触发点）。
     * 剧本默认：友谊关 85mm（>80 熔断），芒街 62mm（未熔断）。
     * <p>
     * 注意：这是<b>演示剧本值</b>，不是上游真实数据；真实取数走 {@link #pushLiveForecast()}。
     * 界面会按 {@link #rainSource} 如实标注来源，不把剧本值说成官方数据。
     */
    public synchronized Map<String, Object> pushOfficialForecast(double yggMm, double mcMm) {
        this.pushed = true;
        ygg.forecastMm = yggMm;
        mc.forecastMm = mcMm;
        this.rainSource = RAIN_SOURCE_SCRIPT;
        this.rainDetail = "剧本值 友谊关" + yggMm + "mm / 芒街" + mcMm + "mm";
        decisionLog.log("WEATHER", "官方气象接口推送（剧本）",
                String.format("友谊关未来6h累计降雨 %.0fmm，芒街 %.0fmm（演示剧本值，非上游真实数据）",
                        yggMm, mcMm));
        return evaluate("官方气象接口推送（剧本）");
    }

    /**
     * 切换到模拟模式并应用当前模拟参数。
     * <p>
     * 进入模拟后所有气象参数变为可调，拖到阈值即自动涌现灾害：
     * 降雨≥80mm(友谊关)/≥70mm(芒街) → 公路熔断；气压≤990 且 风速≥60 → 台风熔断；
     * 水运侧能见度<1000m / 流速>2.0 / 浪高>2.0 / 风速≥13.9m/s → 禁航。
     */
    public synchronized Map<String, Object> enterSimMode() {
        this.mode = MODE_SIM;
        applySimParams();
        decisionLog.log("WEATHER", "进入模拟模式",
                "气象参数转为可调；当前值：友谊关" + ygg.forecastMm + "mm、芒街" + mc.forecastMm + "mm");
        return evaluate("进入模拟模式");
    }

    /** 退出模拟模式：回到真实数据（重新拉取 GOWFS，拉不到则保持原值并说明原因） */
    public synchronized Map<String, Object> exitSimMode() {
        this.mode = MODE_LIVE;
        Map<String, Object> st = pushLiveForecast();
        decisionLog.log("WEATHER", "退出模拟模式", "气象参数恢复为 GOWFS 真实数据（只读）");
        return st;
    }

    /**
     * 模拟模式下调整气象参数（null 表示该项不变）。
     * 只改参数，灾害由 {@link #applySection} / 水运红线自动涌现——不在这里硬编码"触发某灾害"。
     */
    public synchronized Map<String, Object> setSimParams(Double yggRainMm, Double mcRainMm,
                                                         Double yggPressureHpa, Double yggWindKph,
                                                         Double mcPressureHpa, Double mcWindKph) {
        this.mode = MODE_SIM;
        this.sim = new SimParams(
                yggRainMm == null ? sim.yggRainMm() : yggRainMm,
                mcRainMm == null ? sim.mcRainMm() : mcRainMm,
                yggPressureHpa == null ? sim.yggPressureHpa() : yggPressureHpa,
                yggWindKph == null ? sim.yggWindKph() : yggWindKph,
                mcPressureHpa == null ? sim.mcPressureHpa() : mcPressureHpa,
                mcWindKph == null ? sim.mcWindKph() : mcWindKph);
        applySimParams();
        return evaluate("调整模拟气象参数");
    }

    /** 把模拟参数落到断面：降雨直接赋值，气压/风速统一走 TyphoonRiskRule 判定 */
    private void applySimParams() {
        this.pushed = true;
        ygg.forecastMm = sim.yggRainMm();
        mc.forecastMm = sim.mcRainMm();
        // 台风状态由参数驱动：气压与风速同时达标才算台风，与真实气象同一判据
        ygg.typhoonActive = true;
        ygg.typhoonPressureHpa = sim.yggPressureHpa();
        ygg.typhoonWindKph = sim.yggWindKph();
        mc.typhoonActive = true;
        mc.typhoonPressureHpa = sim.mcPressureHpa();
        mc.typhoonWindKph = sim.mcWindKph();
        this.rainSource = RAIN_SOURCE_SIM;
        this.rainDetail = String.format("模拟 雨%.0f/%.0fmm 气压%.0f/%.0fhPa 风%.0f/%.0fkm/h",
                sim.yggRainMm(), sim.mcRainMm(),
                sim.yggPressureHpa(), sim.mcPressureHpa(),
                sim.yggWindKph(), sim.mcWindKph());
        // 模拟参数是手工设定的，官方实况要素不再适用
        this.liveElements = Map.of();
        this.liveWindText = Map.of();
    }

    /**
     * 剧本场景推送（整合版入口）：一个下拉切换「暴雨 / 台风 / 暴雨+台风」三种剧本。
     * <p>
     * 与 {@link #pushOfficialForecast} 的区别：那个只设降雨、保留既有台风状态；
     * 这个会<b>先把台风状态清干净再按场景重设</b>，所以从"台风"切回"暴雨"能真正解除台风，
     * 不会出现"切了场景却还熔断着"的残留。
     */
    public synchronized Map<String, Object> pushScripted(String scenario) {
        String sc = (scenario == null || scenario.isBlank())
                ? SCENARIO_RAIN : scenario.trim().toLowerCase(Locale.ROOT);
        boolean typhoon = SCENARIO_TYPHOON.equals(sc) || SCENARIO_BOTH.equals(sc);
        boolean rain = SCENARIO_RAIN.equals(sc) || SCENARIO_BOTH.equals(sc);
        // 先清台风，避免上一幕的台风状态残留把新场景也压成熔断
        resetTyphoonState(ygg);
        resetTyphoonState(mc);
        // 剧本是手工设定的，官方实况要素不再适用
        this.liveElements = Map.of();
        this.liveWindText = Map.of();
        this.pushed = true;
        // 纯台风场景把降雨归零，否则熔断分不清是雨还是风造成的
        ygg.forecastMm = rain ? SCRIPT_YGG_MM : 0;
        mc.forecastMm = rain ? SCRIPT_MC_MM : 0;
        this.rainSource = RAIN_SOURCE_SIM;
        this.mode = MODE_SIM;
        this.scriptedScenario = sc;
        if (typhoon) {
            // 台风打在芒街：知识库记载"广宁省沿海（下龙、芒街方向）6-10月为台风季"，
            // 芒街靠海，气象上比内陆的友谊关更合理
            mc.typhoonActive = true;
            mc.typhoonPressureHpa = SCRIPT_TYPHOON_PRS_HPA;
            mc.typhoonWindKph = SCRIPT_TYPHOON_WIND_KPH;
        }
        if (typhoon && rain) {
            this.rainDetail = String.format("剧本值 降雨%.0f/%.0fmm + 芒街台风%.0fhPa/%.0fkm/h",
                    ygg.forecastMm, mc.forecastMm, SCRIPT_TYPHOON_PRS_HPA, SCRIPT_TYPHOON_WIND_KPH);
        } else if (typhoon) {
            this.rainDetail = String.format("剧本值 芒街台风%.0fhPa/%.0fkm/h",
                    SCRIPT_TYPHOON_PRS_HPA, SCRIPT_TYPHOON_WIND_KPH);
        } else {
            this.rainDetail = String.format("剧本值 友谊关%.0fmm/芒街%.0fmm",
                    ygg.forecastMm, mc.forecastMm);
        }
        decisionLog.log("WEATHER", "官方气象接口推送（剧本·" + scenarioLabel(sc) + "）",
                rainDetail + "（演示剧本值，非上游真实数据）");
        Map<String, Object> st = evaluate("剧本推送：" + scenarioLabel(sc));
        st.put("scriptedScenario", sc);
        return st;
    }

    /** 剧本场景：暴雨 / 台风 / 暴雨+台风 */
    public static final String SCENARIO_RAIN = "rain";
    public static final String SCENARIO_TYPHOON = "typhoon";
    public static final String SCENARIO_BOTH = "both";
    /** 剧本降雨值（友谊关 85mm 触发 80mm 熔断，芒街 62mm 不触发 70mm） */
    public static final double SCRIPT_YGG_MM = 85.0;
    public static final double SCRIPT_MC_MM = 62.0;
    /** 剧本台风参数：985hPa + 75km/h（台风外围 8 级风，实测可触发熔断） */
    public static final double SCRIPT_TYPHOON_PRS_HPA = 985.0;
    public static final double SCRIPT_TYPHOON_WIND_KPH = 75.0;

    /** 当前剧本场景（供界面回显下拉选中项） */
    private volatile String scriptedScenario = SCENARIO_RAIN;

    private static String scenarioLabel(String sc) {
        return switch (sc) {
            case SCENARIO_TYPHOON -> "台风";
            case SCENARIO_BOTH -> "暴雨+台风";
            default -> "暴雨";
        };
    }

    /** 清除某断面的台风状态（内部用，不触发 evaluate） */
    private static void resetTyphoonState(Section s) {
        s.typhoonActive = false;
        s.typhoonPressureHpa = 1013.0;
        s.typhoonWindKph = 0.0;
    }

    /**
     * 拉取 GOWFS 真实预报作为沙盘降雨输入（取代手填剧本值）。
     * <p>
     * 取数失败（未配置/节流/熔断/上游异常）时<b>保持沙盘原值不变</b>并把失败原因返回前端，
     * 避免把"没拉到"错报成"无降雨"——这与支流实况联动的降级策略一致。
     */
    public synchronized Map<String, Object> pushLiveForecast() {
        List<ContestForecastService.ForecastPoint> points = List.of(
                new ContestForecastService.ForecastPoint(ygg.id, ygg.name, ygg.lat, ygg.lon),
                new ContestForecastService.ForecastPoint(mc.id, mc.name, mc.lat, mc.lon));
        ContestForecastService.ForecastResult r = forecastService.fetchRain6h(points);
        if (r == null || !r.dataAvailable()) {
            String msg = r == null ? "预报服务不可用" : r.message();
            decisionLog.log("WEATHER", "GOWFS 预报拉取失败",
                    msg + "，沙盘沿用当前数值（未取到真实预报）");
            // 拉不到真实数据就不能宣称处于真实模式：保持原模式，由前端如实提示
            Map<String, Object> st = state();
            st.put("forecastMessage", msg);
            return st;
        }
        // 真实数据到位 → 回到真实模式（只读），模拟参数不再生效
        this.mode = MODE_LIVE;
        Double yggMm = r.rain6hMm().get(ygg.id);
        Double mcMm = r.rain6hMm().get(mc.id);
        if (yggMm == null && mcMm == null) {
            decisionLog.log("WEATHER", "GOWFS 预报无断面数据",
                    "上游未返回友谊关/芒街格点降雨，沙盘沿用当前数值");
            Map<String, Object> st = state();
            st.put("forecastMessage", "上游未返回断面格点降雨");
            return st;
        }
        this.pushed = true;
        this.liveLoaded = true;
        if (yggMm != null) {
            ygg.forecastMm = yggMm;
        }
        if (mcMm != null) {
            mc.forecastMm = mcMm;
        }
        this.rainSource = RAIN_SOURCE_LIVE;
        this.rainDetail = r.message();
        // 官方返回的全部要素一并留存并下发界面（不只显示降雨量）
        this.liveElements = r.pointElements();
        this.liveWindText = r.windDirectionText();
        decisionLog.log("WEATHER", "GOWFS 预报推送",
                String.format("%s：友谊关未来6h累计降雨 %.1fmm，芒街 %.1fmm（真实格点取数）",
                        r.message(), ygg.forecastMm, mc.forecastMm));
        Map<String, Object> st = evaluate("GOWFS 预报推送");
        st.put("forecastDetail", r.toMap());
        return st;
    }

    /**
     * 模拟台风（演示第三幕入口）：给指定断面设置气压/风速，走与真实气象完全相同的
     * {@link TyphoonRiskRule} 判据——达标即熔断（penalty=100），不达标则只记状态不熔断。
     * <p>
     * 默认参数 985hPa / 75km/h 对应台风外围 8 级风，实测可触发熔断；
     * 台风季真实数据（如 202409062000，最低 995.3hPa）达不到 990 阈值，
     * 所以"模拟台风"是现场展示该能力的入口，真实触发则留给极端场景。
     */
    public synchronized Map<String, Object> setTyphoon(String sectionId, double pressureHpa, double windKph) {
        Section s = sectionOf(sectionId);
        if (s == null) {
            Map<String, Object> st = state();
            st.put("forecastMessage", "未知断面：" + sectionId);
            return st;
        }
        this.pushed = true;
        s.typhoonActive = true;
        s.typhoonPressureHpa = pressureHpa;
        s.typhoonWindKph = windKph;
        // 台风是模拟输入，官方实况要素不再适用，清掉避免界面显示过期要素
        this.liveElements = Map.of();
        this.liveWindText = Map.of();
        TyphoonRiskRule.Verdict v = TyphoonRiskRule.evaluate(pressureHpa, windKph);
        decisionLog.log("WEATHER", "模拟台风",
                String.format("%s 气压 %.0fhPa / 风速 %.0fkm/h → %s",
                        s.name, pressureHpa, windKph,
                        v.triggered() ? v.reason() : "未达台风判据（气压>990hPa 或 风速<60km/h）"));
        return evaluate("模拟台风");
    }

    /** 解除某断面的模拟台风（回到仅按降雨判定）。 */
    public synchronized Map<String, Object> clearTyphoon(String sectionId) {
        Section s = sectionOf(sectionId);
        if (s == null) {
            Map<String, Object> st = state();
            st.put("forecastMessage", "未知断面：" + sectionId);
            return st;
        }
        s.typhoonActive = false;
        s.typhoonPressureHpa = 1013.0;
        s.typhoonWindKph = 0.0;
        decisionLog.log("WEATHER", "解除模拟台风", s.name + " 台风状态已清除，回到按降雨量判定");
        return evaluate("解除模拟台风");
    }

    private Section sectionOf(String sectionId) {
        if (sectionId == null || sectionId.isBlank()) {
            return null;
        }
        String key = sectionId.trim().toUpperCase();
        if (key.startsWith("YGG")) {
            return ygg;
        }
        if (key.startsWith("MC")) {
            return mc;
        }
        return null;
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
        this.rainSource = RAIN_SOURCE_SIM;
        this.mode = MODE_SIM;
        this.rainDetail = "调度员调整滑块";
        return evaluate("调度员调整决策沙盘");
    }

    /** 调度员调整水运通航条件（场景B：模拟水运禁航）。null 表示该项不变。 */
    public synchronized Map<String, Object> setWaterConditions(Double visibilityM, Double currentMs, Double waveM) {
        return setWaterConditions(visibilityM, currentMs, waveM, null, "调度员调整运河通航条件");
    }

    /**
     * 四参数 + 触发源版：供水运观测数据接入适配层（WaterwayDataService）透传
     * 调度平台回传的能见度/流速/浪高/风力，判定与 canal-block 广播链路完全复用。
     */
    public synchronized Map<String, Object> setWaterConditions(Double visibilityM, Double currentMs,
                                                               Double waveM, Double windMs, String trigger) {
        this.pushed = true;
        waterRiskEngine.setConditions(visibilityM, currentMs, waveM, windMs);
        Map<String, Object> now = waterRiskEngine.state();
        decisionLog.log("WEATHER", "运河通航条件更新（" + trigger + "）",
                String.format("能见度 %.0fm / 流速 %.1fm/s / 浪高 %.1fm / 风速 %.1fm/s（%d级）",
                        ((Number) now.get("visibilityM")).doubleValue(),
                        ((Number) now.get("currentSpeedMs")).doubleValue(),
                        ((Number) now.get("waveHeightM")).doubleValue(),
                        ((Number) now.get("windSpeedMs")).doubleValue(),
                        ((Number) now.get("windForce")).intValue()));
        return evaluate(trigger);
    }

    /**
     * 支流风险联动干流（调度大屏支流风险卡"参与决策"入口）：
     * 把各支流差异化概率聚合为顶托设计流速写入 {@link WaterRiskEngine}，
     * 再走同一套 evaluate → 禁航红线判定 → canal-block / canal-recover 广播链路，
     * 正在导航去运河的司机/普通用户因此能收到支流暴雨引发的禁航反馈。
     * rainfallMm 传 0 即解除联动，回到人工基准主导。
     * <p>
     * 注意：不改 pushed 空态——支流联动只影响水运断面，不代表官方公路推送已到达。
     */
    public synchronized Map<String, Object> syncTributaryRisk(double rainfallMm) {
        Map<String, Object> impact = tributaryRiskService.mainlineImpact(rainfallMm);
        double induced = ((Number) impact.get("inducedCurrentMs")).doubleValue();
        waterRiskEngine.setTributaryImpact(rainfallMm <= 0 ? 0 : induced);
        decisionLog.log("WEATHER", "支流风险联动干流",
                String.format("支流流域降雨 %.0fmm → 顶托设计流速 %.2fm/s，干流判定流速 %.1fm/s（红线 %.1fm/s）",
                        rainfallMm, induced, waterRiskEngine.effectiveCurrentMs(), WaterRiskEngine.CURRENT_MAX_MS));
        Map<String, Object> state = evaluate("支流风险联动");
        state.put("tributaryImpact", impact);
        return state;
    }

    /**
     * 支流风险实况联动干流：降雨输入来自真实气象源（非滑杆剧本值），
     * 按各支流口实时雨强等效过程降雨量逐支流算概率→聚合顶托流速→同一套
     * evaluate → canal-block / canal-recover 广播链路。
     * 气象拉取失败（无任何支流口数据）时不注入：保留干流当前联动状态，
     * 避免把“没网”错报成“无降雨→解除顶托”。
     */
    public synchronized Map<String, Object> syncTributaryRiskLive() {
        Map<String, Object> live = tributaryRiskService.liveAssessment();
        if (!Boolean.TRUE.equals(live.get("dataAvailable"))) {
            decisionLog.log("WEATHER", "支流实况联动失败",
                    "当前气象源（" + live.get("sourceLabel") + "）未返回任何支流口降雨数据，保持原联动状态不变");
            Map<String, Object> state = evaluate("支流实况联动（无数据）");
            state.put("tributaryImpact", live);
            return state;
        }
        double induced = ((Number) live.get("inducedCurrentMs")).doubleValue();
        waterRiskEngine.setTributaryImpact(induced);
        decisionLog.log("WEATHER", "支流实况风险联动干流",
                String.format("气象源：%s，平均等效降雨 %.0fmm → 顶托设计流速 %.2fm/s，干流判定流速 %.1fm/s（红线 %.1fm/s）",
                        live.get("sourceLabel"), ((Number) live.get("avgEquivalentMm")).doubleValue(),
                        induced, waterRiskEngine.effectiveCurrentMs(), WaterRiskEngine.CURRENT_MAX_MS));
        Map<String, Object> state = evaluate("支流实况气象联动");
        state.put("tributaryImpact", live);
        return state;
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
        // 状态翻转才广播：正在导航去平陆运河的司机/普通用户需要即时反馈
        // （司机端走调度大屏换线，普通用户自助弹替代路线，见 DriverApp/App.vue 的 canal-block 监听）
        broadcastCanalChange(nowBlocked, trigger);
    }

    /** 经 SSE 通道广播 canal-block / canal-recover，携带受影响运河模式关注路线清单。 */
    private void broadcastCanalChange(boolean blocked, String trigger) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", blocked ? "canal-block" : "canal-recover");
            payload.put("blocked", blocked);
            payload.put("reason", blocked ? waterRiskEngine.blockedReason() : "平陆运河通航条件恢复红线之上");
            payload.put("trigger", trigger);
            payload.put("state", waterRiskEngine.state());
            payload.put("canalWatchers", routeAgentService.canalWatchersSnapshot());
            payload.put("ts", System.currentTimeMillis());
            routeAgentService.broadcastEvent(blocked ? "canal-block" : "canal-recover", payload);
        } catch (Exception e) {
            // 广播失败不影响禁航判定本身
            org.slf4j.LoggerFactory.getLogger(DecisionSandboxService.class)
                    .warn("canal state broadcast failed: {}", e.toString());
        }
    }

    private void applySection(Section s, double thresholdMm, String trigger) {
        // 熔断判定：达到阈值熔断；已熔断时需降至 阈值-滞后（含）以下才恢复
        // （剧本第二幕：85mm 熔断，拖回 75mm 即恢复通行）
        boolean rainFused = s.forecastMm >= thresholdMm
                || (s.fused && s.forecastMm > thresholdMm - HYSTERESIS_MM);
        // 台风成因：与降雨并列，取更严重者。判据复用 TyphoonRiskRule，
        // 保证"模拟台风"与"真实气象触发台风"结论一致（不会模拟能熔断、真实同量级却不熔断）
        TyphoonRiskRule.Verdict typhoon = s.typhoonActive
                ? TyphoonRiskRule.evaluate(s.typhoonPressureHpa, s.typhoonWindKph)
                : new TyphoonRiskRule.Verdict(0, null);
        boolean nowFused = rainFused || typhoon.blocking();
        if (nowFused == s.fused) {
            return;
        }
        if (nowFused) {
            s.fused = true;
            String reason;
            if (typhoon.blocking()) {
                reason = String.format("沙盘熔断:%s%s（%s）", s.name, typhoon.reason(), trigger);
            } else {
                reason = String.format("沙盘熔断:%s未来6h累计降雨量%.0fmm > %.0fmm熔断阈值（%s）",
                        s.name, s.forecastMm, thresholdMm, trigger);
            }
            weatherSimulator.injectRisk(new RiskSegment(s.edgeId, reason, "CRITICAL", FUSE_PENALTY));
            decisionLog.log("FUSE", s.name + "熔断",
                    (typhoon.blocking()
                            ? String.format("%s，penalty=100（%s）", typhoon.reason(), trigger)
                            : String.format("未来6h累计降雨量 %.0fmm > %.0fmm 阈值，penalty=100（%s）",
                                    s.forecastMm, thresholdMm, trigger)));
        } else {
            s.fused = false;
            weatherSimulator.clearRisk(s.edgeId);
            decisionLog.log("UNFUSE", s.name + "恢复通行",
                    String.format("降雨 %.0fmm 低于 %.0fmm 恢复线且无台风判据（%s）",
                            s.forecastMm, thresholdMm - HYSTERESIS_MM, trigger));
        }
    }

    /** 清空沙盘（撤掉沙盘注入的全部风险 + 恢复运河正常通航，回到推送前状态）。 */
    public synchronized Map<String, Object> reset() {
        this.pushed = false;
        // 重置回到“未拉取”空态：清掉真实预报已加载标记，否则刷新后「模拟」仍会解锁
        this.liveLoaded = false;
        boolean wasWaterFused = waterFused;
        ygg.forecastMm = 0;
        mc.forecastMm = 0;
        ygg.fused = false;
        mc.fused = false;
        // 台风状态一并清除，否则重置后断面仍被台风判据压着无法恢复
        ygg.typhoonActive = false;
        mc.typhoonActive = false;
        ygg.typhoonPressureHpa = 1013.0;
        mc.typhoonPressureHpa = 1013.0;
        ygg.typhoonWindKph = 0.0;
        mc.typhoonWindKph = 0.0;
        this.rainSource = RAIN_SOURCE_SIM;
        this.rainDetail = null;
        this.scriptedScenario = SCENARIO_RAIN;
        // 重置后回到真实模式与"什么都不触发"的模拟初值
        this.mode = MODE_LIVE;
        this.sim = new SimParams(0, 0, 1013, 20, 1013, 20);
        this.liveElements = Map.of();
        this.liveWindText = Map.of();
        waterFused = false;
        waterRiskEngine.setConditions(1200.0, 1.8, 0.8, 6.0);
        waterRiskEngine.setTributaryImpact(0);   // 解除支流风险联动
        weatherSimulator.clearRisk(ygg.edgeId);
        weatherSimulator.clearRisk(mc.edgeId);
        decisionLog.log("SANDBOX", "沙盘重置", "已撤销沙盘注入的全部风险并恢复运河通航");
        // 重置前处于禁航：补发一次恢复事件，清掉大屏/司机端的禁航告警
        if (wasWaterFused) {
            broadcastCanalChange(false, "沙盘重置");
        }
        return state();
    }

    /** GOWFS 预报通道自检（不含密钥）：供界面解释"为何没拉到真实预报" */
    public Map<String, Object> forecastStatus() {
        return forecastService == null ? Map.of("enabled", false) : forecastService.status();
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

    /**
     * 任一公路口岸段是否已熔断（含台风致熔断）。
     * <p>
     * 与 {@link #roadBlocked()} 的区别：那个只认主通道友谊关，用于"要不要切水运"；
     * 这个覆盖两个口岸，用于"要不要触发绕路决策"。台风打在芒街时主通道没断，
     * 但若只看 roadBlocked，方案智能体会判定为常态、不触发任何绕行分析——
     * 而实际上芒街口岸已经不能走了，路线必须绕开。
     */
    public boolean anyPortFused() {
        return roadBlocked() || mc.fused || injectedBlocking(mc);
    }

    /**
     * 当前熔断的口岸名（多个用"、"连接）；无熔断返回 null。
     * 供方案智能体如实描述"断的是哪一段、还能绕哪里"，避免台风熔断芒街时
     * 仍对模型说"熔断路段：友谊关，可用绕行：芒街"（说反了会让模型给出错误推荐）。
     */
    public String blockedPortNames() {
        List<String> names = new ArrayList<>();
        if (roadBlocked()) {
            names.add(ygg.name);
        }
        if (mc.fused || injectedBlocking(mc)) {
            names.add(mc.name);
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /** 熔断成因：typhoon / rain / rain+typhoon；未熔断返回 null */
    public String fuseCause() {
        TyphoonRiskRule.Verdict ty = mc.typhoonActive
                ? TyphoonRiskRule.evaluate(mc.typhoonPressureHpa, mc.typhoonWindKph) : null;
        TyphoonRiskRule.Verdict yy = ygg.typhoonActive
                ? TyphoonRiskRule.evaluate(ygg.typhoonPressureHpa, ygg.typhoonWindKph) : null;
        boolean typhoon = (ty != null && ty.blocking()) || (yy != null && yy.blocking());
        boolean rain = ygg.forecastMm > 0 || mc.forecastMm > 0;
        if (typhoon && rain) {
            return "rain+typhoon";
        }
        if (typhoon) {
            return "typhoon";
        }
        return rain ? "rain" : null;
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
        // 模式：live=真实只读 / sim=模拟可调；前端据此决定滑杆是否可拖
        m.put("mode", mode);
        // 是否拉到过 GOWFS 真实预报（前端「模拟」按钮的显隐开关）
        m.put("liveLoaded", liveLoaded);
        m.put("simParams", Map.of(
                "yggRainMm", sim.yggRainMm(), "mcRainMm", sim.mcRainMm(),
                "yggPressureHpa", sim.yggPressureHpa(), "yggWindKph", sim.yggWindKph(),
                "mcPressureHpa", sim.mcPressureHpa(), "mcWindKph", sim.mcWindKph()));
        // 如实标注降雨来源：剧本值就说剧本值，只有真的从 GOWFS 拉到才标官方预报
        m.put("dataSource", rainSource);
        m.put("dataSourceDetail", rainDetail);
        // 当前剧本场景（供界面回显下拉选中项）
        m.put("scriptedScenario", scriptedScenario);
        m.put("forecastStatus", forecastService == null ? null : forecastService.status());
        m.put("fuseThresholdMm", config.fuseThresholdMm());
        m.put("riskProfile", config.riskProfile());
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(sectionMap(ygg, config.fuseThresholdMm()));
        sections.add(sectionMap(mc, MC_THRESHOLD_MM));
        m.put("sections", sections);
        // 水运断面（场景B：陆水联运的另一半）
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
            default -> "等待气象数据";
        });
        m.put("boundaryText", s.fused
                ? String.format("降雨量需降至 %.0fmm 以下才恢复通行", thresholdMm - HYSTERESIS_MM)
                : String.format("降雨量升至 %.0fmm 以上将熔断", thresholdMm));
        // 台风模拟状态（供界面显示"当前是否处于台风判据下"）
        m.put("typhoonActive", s.typhoonActive);
        m.put("typhoonPressureHpa", s.typhoonPressureHpa);
        m.put("typhoonWindKph", s.typhoonWindKph);
        TyphoonRiskRule.Verdict tv = s.typhoonActive
                ? TyphoonRiskRule.evaluate(s.typhoonPressureHpa, s.typhoonWindKph)
                : new TyphoonRiskRule.Verdict(0, null);
        m.put("typhoonVerdict", tv.triggered() ? tv.reason() : null);
        m.put("typhoonBlocking", tv.blocking());
        // 真实预报取到的官方气象要素（仅 GOWFS 取数时非空），剧本场景下为 null
        m.put("elements", liveElements.get(s.id));
        m.put("windDirectionText", liveWindText.get(s.id));
        // 风险研判元数据
        m.put("slopeSoil", s.slopeSoil);
        m.put("history", s.history);
        m.put("moisturePct", s.moisturePct);
        m.put("riskLevel", s.fused ? "高" : (s.forecastMm >= CAUTION_MM ? "中" : "低"));
        return m;
    }
}
