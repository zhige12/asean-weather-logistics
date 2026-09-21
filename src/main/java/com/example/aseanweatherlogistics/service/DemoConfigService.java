package com.example.aseanweatherlogistics.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 开放平台配置器（演示第十幕）：同一套决策引擎，千企千面。
 * <ul>
 *   <li>风险容忍度：保守 80mm 熔断 / 平衡 100mm 熔断（未来6h累计降雨）</li>
 *   <li>货物类型：冷链火龙果（延误 4h 起算货损）/ 电子元件（2h 起算）</li>
 *   <li>触达对象：调度员 / 司机 / 船东 / 沿岸百姓 可勾选</li>
 * </ul>
 * 全部为内存态，改动即生效（沙盘阈值、方案对比面板、触达名单实时联动）。
 */
@Service
public class DemoConfigService {

    public static final String TARGET_DISPATCHER = "DISPATCHER";
    public static final String TARGET_DRIVER = "DRIVER";
    /** 越南侧接力司机（剧本第⑨幕「越南同事也收到通知」），指令以越南语为主文案 */
    public static final String TARGET_DRIVER_VN = "DRIVER_VN";
    public static final String TARGET_SHIPOWNER = "SHIPOWNER";
    public static final String TARGET_PUBLIC = "PUBLIC";

    public static final List<String> ALL_TARGETS = List.of(
            TARGET_DISPATCHER, TARGET_DRIVER, TARGET_DRIVER_VN, TARGET_SHIPOWNER, TARGET_PUBLIC);

    public static final Map<String, String> TARGET_NAMES = Map.of(
            TARGET_DISPATCHER, "调度员",
            TARGET_DRIVER, "货车司机",
            TARGET_DRIVER_VN, "越南司机",
            TARGET_SHIPOWNER, "船东/船员",
            TARGET_PUBLIC, "沿岸百姓");

    /**
     * 货物类型：延误敏感度模型。
     * damageRatePerHour = 超过起算时间后每小时的货损率（占货值比例）。
     */
    public record CargoType(String id, String name, boolean coldChain, double cargoValueYuan,
                            double damageStartHours, double damageRatePerHour) {
        /** 延误 delayHours 后的货损率（0-1） */
        public double damageRate(double delayHours) {
            if (delayHours <= damageStartHours) {
                return 0.0;
            }
            return damageRatePerHour * (delayHours - damageStartHours);
        }

        /** 延误 delayHours 后的货损金额（元） */
        public double damageLossYuan(double delayHours) {
            return cargoValueYuan * damageRate(delayHours);
        }
    }

    public static final Map<String, CargoType> CARGO_TYPES = new LinkedHashMap<>();

    static {
        // 冷链火龙果：延误 >4h 起算，+2.4%/h → 延误 6h 货损率 4.8%（剧本数值）
        CARGO_TYPES.put("DRAGON_FRUIT",
                new CargoType("DRAGON_FRUIT", "冷链火龙果", true, 360_000, 4.0, 0.024));
        // 电子元件：延误 >2h 起算，+1.8%/h → 延误 6h 货损率 7.2%（剧本数值）
        CARGO_TYPES.put("ELECTRONICS",
                new CargoType("ELECTRONICS", "电子元件", false, 800_000, 2.0, 0.018));
    }

    /** 风险容忍度 → 熔断阈值（未来6h累计降雨 mm） */
    public static final Map<String, Double> RISK_PROFILES = new LinkedHashMap<>();

    static {
        RISK_PROFILES.put("CONSERVATIVE", 80.0);
        RISK_PROFILES.put("BALANCED", 100.0);
        RISK_PROFILES.put("AGGRESSIVE", 120.0);
    }

    public static final Map<String, String> RISK_PROFILE_NAMES = Map.of(
            "CONSERVATIVE", "保守",
            "BALANCED", "平衡",
            "AGGRESSIVE", "激进");

    /**
     * 预设智能体模板（计划书 §3.3 开放点一 · 智能体模板库）：
     * 一键套用「风险偏好 + 货物类型 + 触达范围」的专属智能体，证明“同一引擎、千企千面”。
     * 物流公司可基于自身货类与路线特征选择模板，正式落地时可自定义扩展。
     */
    public record AgentTemplate(String id, String name, String desc, String riskProfile,
                                String cargoTypeId, List<String> targets) {}

    public static final Map<String, AgentTemplate> TEMPLATES = new LinkedHashMap<>();

    static {
        // 冷链火龙果智能体：生鲜冷链、保守熔断、全角色触达（当前示范场景）
        TEMPLATES.put("COLD_DRAGONFRUIT", new AgentTemplate("COLD_DRAGONFRUIT",
                "冷链火龙果智能体", "生鲜冷链·保守熔断80mm·全角色触达（示范场景）",
                "CONSERVATIVE", "DRAGON_FRUIT", List.copyOf(ALL_TARGETS)));
        // 电子元件防潮智能体：高货值、延误2h起算货损、不扰沿岸百姓
        TEMPLATES.put("ELECTRONICS_MOISTURE", new AgentTemplate("ELECTRONICS_MOISTURE",
                "电子元件防潮智能体", "高货值防潮·延误2h起算货损·调度/司机/船东触达",
                "CONSERVATIVE", "ELECTRONICS",
                List.of(TARGET_DISPATCHER, TARGET_DRIVER, TARGET_DRIVER_VN, TARGET_SHIPOWNER)));
        // 大宗普货智能体：耐储运、激进放宽阈值、仅调度员与司机
        TEMPLATES.put("BULK_GENERAL", new AgentTemplate("BULK_GENERAL",
                "大宗普货智能体", "耐储运普货·激进放宽120mm·仅调度员与司机",
                "AGGRESSIVE", "DRAGON_FRUIT", List.of(TARGET_DISPATCHER, TARGET_DRIVER)));
    }

    /** 当前选中的模板 id（null=用户手动微调后的自定义配置） */
    private volatile String activeTemplate = "COLD_DRAGONFRUIT";

    private volatile String riskProfile = "CONSERVATIVE";
    private volatile String cargoTypeId = "DRAGON_FRUIT";
    private volatile Set<String> outreachTargets =
            new LinkedHashSet<>(ALL_TARGETS);

    public double fuseThresholdMm() {
        return RISK_PROFILES.getOrDefault(riskProfile, 80.0);
    }

    public String riskProfile() {
        return riskProfile;
    }

    public CargoType cargo() {
        return CARGO_TYPES.getOrDefault(cargoTypeId, CARGO_TYPES.get("DRAGON_FRUIT"));
    }

    public Set<String> outreachTargets() {
        return Set.copyOf(outreachTargets);
    }

    public boolean targetEnabled(String target) {
        return outreachTargets.contains(target);
    }

    /** 部分更新配置；非法值忽略。手动微调即视为自定义配置（清除模板高亮）。返回更新后的完整配置。 */
    public synchronized Map<String, Object> update(String newRiskProfile, String newCargoTypeId,
                                                   Set<String> newTargets) {
        boolean changed = false;
        if (newRiskProfile != null && RISK_PROFILES.containsKey(newRiskProfile)) {
            this.riskProfile = newRiskProfile;
            changed = true;
        }
        if (newCargoTypeId != null && CARGO_TYPES.containsKey(newCargoTypeId)) {
            this.cargoTypeId = newCargoTypeId;
            changed = true;
        }
        if (newTargets != null) {
            Set<String> cleaned = new LinkedHashSet<>();
            for (String t : newTargets) {
                if (ALL_TARGETS.contains(t)) {
                    cleaned.add(t);
                }
            }
            this.outreachTargets = cleaned;
            changed = true;
        }
        if (changed) {
            this.activeTemplate = null;
        }
        return toMap();
    }

    /** 一键套用预设智能体模板（计划书 §3.3 开放点一）。 */
    public synchronized Map<String, Object> applyTemplate(String templateId) {
        AgentTemplate t = TEMPLATES.get(templateId);
        if (t == null) {
            return toMap();
        }
        this.riskProfile = t.riskProfile();
        this.cargoTypeId = t.cargoTypeId();
        this.outreachTargets = new LinkedHashSet<>(t.targets());
        this.activeTemplate = t.id();
        return toMap();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("riskProfile", riskProfile);
        m.put("riskProfileName", RISK_PROFILE_NAMES.getOrDefault(riskProfile, riskProfile));
        m.put("fuseThresholdMm", fuseThresholdMm());
        m.put("riskProfiles", RISK_PROFILES);
        m.put("cargoType", cargo().id());
        m.put("cargoTypeName", cargo().name());
        m.put("cargo", cargo());
        m.put("cargoTypes", CARGO_TYPES);
        m.put("outreachTargets", outreachTargets());
        m.put("allTargets", ALL_TARGETS);
        m.put("targetNames", TARGET_NAMES);
        m.put("activeTemplate", activeTemplate);
        m.put("templates", TEMPLATES);
        return m;
    }
}
