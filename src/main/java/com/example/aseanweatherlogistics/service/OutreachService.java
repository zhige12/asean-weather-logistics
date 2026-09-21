package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.DemoClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 多角色触达与分级叫应（演示第四/五/六幕）：
 * <ul>
 *   <li>任务变更指令按角色定制：调度员（决策回执）、货车司机（执行指令+权益保障包，中越双语）、
 *       船东/船员（过闸建议）、沿岸百姓（公众预警）。</li>
 *   <li>触达状态闭环：已送达 → 已确认；未确认的自动升级触达方式（语音外呼），
 *       不是"发了预警就不管"。</li>
 *   <li>全部状态变化通过 SSE（outreach-update 事件）实时推送到大屏触达状态面板。</li>
 * </ul>
 * 司机收到的是执行指令和权益保障，不是"你想走公路还是水运"——切换运输方式是调度端的决策权限。
 */
@Service
public class OutreachService {

    private static final Logger log = LoggerFactory.getLogger(OutreachService.class);

    /** 沿岸百姓模拟人数（剧本：12人，8人确认、4人未确认→二次呼叫） */
    public static final int PUBLIC_COUNT = 12;
    /** 百姓自动确认节奏（演示编排）：首批确认延迟 / 升级延迟 / 升级后确认延迟 */
    private static final int PUBLIC_FIRST_CONFIRM_DELAY_S = 4;
    private static final int ESCALATE_DELAY_S = 4;
    private static final int ESCALATED_CONFIRM_DELAY_S = 3;
    /** 首批自动确认的百姓人数（其余走二次呼叫演示） */
    private static final int PUBLIC_FIRST_CONFIRM_COUNT = 8;

    /** 分级叫应等级 → 中文名（计划书模块四：黄=关注 / 橙=准备 / 红=立即行动） */
    public static final Map<String, String> LEVEL_NAMES = Map.of(
            "NORMAL", "常规通知", "YELLOW", "黄色关注", "ORANGE", "橙色准备", "RED", "红色立即行动");
    /** 分级叫应等级 → 触达通道（黄=App推送 / 橙=App+短信 / 红=App+语音外呼） */
    public static final Map<String, String> LEVEL_CHANNELS = Map.of(
            "NORMAL", "App推送", "YELLOW", "App推送", "ORANGE", "App+短信", "RED", "App+语音外呼");
    /** 分级叫应等级 → 确认方式（黄=点击确认 / 橙=回复确认 / 红=语音确认） */
    public static final Map<String, String> LEVEL_CONFIRM = Map.of(
            "NORMAL", "点击确认", "YELLOW", "点击确认", "ORANGE", "回复确认", "RED", "语音确认");
    /** 分级叫应等级 → 未确认升级策略 */
    public static final Map<String, String> LEVEL_ESCALATION = Map.of(
            "NORMAL", "—", "YELLOW", "App二次提醒", "ORANGE", "短信+App二次提醒", "RED", "未确认自动上报调度端");

    public record TargetStatus(String id, String role, String roleName, String name,
                               boolean delivered, boolean confirmed, boolean escalated,
                               String channel, Long confirmedAt) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("role", role);
            m.put("roleName", roleName);
            m.put("name", name);
            m.put("delivered", delivered);
            m.put("confirmed", confirmed);
            m.put("escalated", escalated);
            m.put("channel", channel);
            if (confirmedAt != null) {
                m.put("confirmedAt", confirmedAt);
            }
            return m;
        }
    }

    private final DemoConfigService config;
    private final DecisionLogService decisionLog;
    private final RouteAgentService routeAgentService;
    private final DecisionSandboxService sandbox;

    /** 当前触达批次的目标状态（id → status） */
    private final Map<String, TargetStatus> targets = new ConcurrentHashMap<>();
    /** 当前批次下发的消息（id → {message, messageVi?}） */
    private final Map<String, Map<String, String>> messages = new ConcurrentHashMap<>();
    private volatile String activePlanId;
    private volatile long dispatchedAt;
    /** 当前批次的分级叫应等级（NORMAL/YELLOW/ORANGE/RED），由沙盘风险状态研判 */
    private volatile String alertLevel = "NORMAL";
    /** 红色级：叫应窗口结束仍未确认、已自动上报调度端的目标 id */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outreach-scheduler");
        t.setDaemon(true);
        return t;
    });

    public OutreachService(DemoConfigService config, DecisionLogService decisionLog,
                           RouteAgentService routeAgentService, DecisionSandboxService sandbox) {
        this.config = config;
        this.decisionLog = decisionLog;
        this.routeAgentService = routeAgentService;
        this.sandbox = sandbox;
    }

    /**
     * 调度员确认方案后下发任务变更指令（第四幕）。
     *
     * @param planId   A=公路绕行芒街 / B=公水联运 / C=原地等待
     * @param planName 方案名（日志用）
     */
    public synchronized Map<String, Object> dispatch(String planId, String planName) {
        targets.clear();
        messages.clear();
        this.activePlanId = planId;
        this.dispatchedAt = System.currentTimeMillis();
        this.alertLevel = computeAlertLevel();
        this.reported.clear();

        // 双向切换方向（3.4）：road_to_water / water_to_road / dual_risk / normal
        boolean waterBlocked = sandbox.waterBlocked();
        boolean roadBlocked = sandbox.roadBlocked();
        String direction = sandbox.direction();

        decisionLog.log("DISPATCH", "确认切换方案",
                String.format("调度员确认方案%s（%s），任务变更指令生成并下发（切换方向：%s，叫应等级：%s）",
                        planId, planName, direction, LEVEL_NAMES.getOrDefault(alertLevel, alertLevel)));

        // ---- 调度员（PC大屏）：送达即确认（本人操作） ----
        if (config.targetEnabled(DemoConfigService.TARGET_DISPATCHER)) {
            put(new TargetStatus("dispatcher-1", DemoConfigService.TARGET_DISPATCHER,
                    "调度员", "调度员", true, true, false, "PC大屏", System.currentTimeMillis()),
                    Map.of("message", dispatcherMessage(planId, planName, direction)));
        }

        // ---- 货车司机阿明：执行指令 + 权益保障包（中越双语） ----
        if (config.targetEnabled(DemoConfigService.TARGET_DRIVER)) {
            put(new TargetStatus("driver-1", DemoConfigService.TARGET_DRIVER,
                    "货车司机", "阿明（桂A·D12345）", true, false, false, LEVEL_CHANNELS.get(alertLevel), null),
                    driverMessages(planId, waterBlocked));
        }

        // ---- 越方接力司机阿雄（第⑨幕「越南同事也收到通知」）：越南语为主文案 ----
        if (config.targetEnabled(DemoConfigService.TARGET_DRIVER_VN)) {
            put(new TargetStatus("driver-vn-1", DemoConfigService.TARGET_DRIVER_VN,
                    "越南司机", "阿雄（越籍车队）", true, false, false, LEVEL_CHANNELS.get(alertLevel) + "（越语）", null),
                    driverMessagesVn(planId, waterBlocked));
        }

        // ---- 船东/船员：按切换方向生成（公路切水运=准备装船 / 水运切公路=取消装船 / 正常=过闸建议） ----
        if (config.targetEnabled(DemoConfigService.TARGET_SHIPOWNER)) {
            put(new TargetStatus("ship-1", DemoConfigService.TARGET_SHIPOWNER,
                    "船东/船员", "船东/船员", true, false, false, LEVEL_CHANNELS.get(alertLevel), null),
                    Map.of("message", shipMessage(planId, waterBlocked, roadBlocked)));
        }

        // ---- 沿岸百姓：公众预警（12人） ----
        if (config.targetEnabled(DemoConfigService.TARGET_PUBLIC)) {
            for (int i = 1; i <= PUBLIC_COUNT; i++) {
                String id = String.format("pub-%02d", i);
                put(new TargetStatus(id, DemoConfigService.TARGET_PUBLIC,
                        "沿岸百姓", "沿岸百姓 " + i, true, false, false, "App公众模式", null),
                        Map.of("message", "📢 气象预警\n"
                                + "平陆运河横州段未来1小时有雷雨大风，请沿岸渔民立即回港避风，点击确认。"));
            }
            // 演示编排：首批 8 人确认 → 剩余 4 人升级语音外呼 → 升级后确认
            scheduler.schedule(() -> autoConfirmPublic(PUBLIC_FIRST_CONFIRM_COUNT),
                    PUBLIC_FIRST_CONFIRM_DELAY_S, TimeUnit.SECONDS);
            scheduler.schedule(this::escalateUnconfirmed,
                    PUBLIC_FIRST_CONFIRM_DELAY_S + ESCALATE_DELAY_S, TimeUnit.SECONDS);
            scheduler.schedule(this::confirmEscalated,
                    PUBLIC_FIRST_CONFIRM_DELAY_S + ESCALATE_DELAY_S + ESCALATED_CONFIRM_DELAY_S,
                    TimeUnit.SECONDS);
        }

        // 红色立即行动：叫应窗口结束仍未确认的关键角色 → 自动上报调度端人工介入（分级叫应闭环）
        if ("RED".equals(alertLevel)) {
            scheduler.schedule(this::reportUnconfirmedToDispatcher,
                    PUBLIC_FIRST_CONFIRM_DELAY_S + ESCALATE_DELAY_S + ESCALATED_CONFIRM_DELAY_S + 3,
                    TimeUnit.SECONDS);
        }

        broadcast();
        return status();
    }

    /** 司机/船东 App 点击"确认接收"（第五幕）。 */
    public synchronized Map<String, Object> confirm(String targetId) {
        TargetStatus t = targets.get(targetId);
        if (t == null) {
            throw new IllegalArgumentException("Unknown target: " + targetId);
        }
        if (!t.confirmed()) {
            targets.put(targetId, new TargetStatus(t.id(), t.role(), t.roleName(), t.name(),
                    true, true, t.escalated(), t.channel(), System.currentTimeMillis()));
            decisionLog.log("CONFIRM", t.roleName() + "已确认", t.name() + " 确认接收任务变更指令");
            broadcast();
        }
        return status();
    }

    /** 取某目标的消息（司机端拉取任务变更通知，含越南语）。 */
    public Map<String, Object> message(String targetId) {
        TargetStatus t = targets.get(targetId);
        if (t == null) {
            throw new IllegalArgumentException("Unknown target: " + targetId);
        }
        Map<String, Object> m = new LinkedHashMap<>(t.toMap());
        m.putAll(messages.getOrDefault(targetId, Map.of()));
        m.put("planId", activePlanId == null ? "" : activePlanId);
        // 批次时间戳：司机端据此识别"同一会话内调度端二次下发"（id 不变但方案已变）
        m.put("dispatchedAt", dispatchedAt);
        m.put("alertLevel", alertLevel);
        m.put("alertLevelName", LEVEL_NAMES.getOrDefault(alertLevel, alertLevel));
        m.put("confirmMode", LEVEL_CONFIRM.getOrDefault(alertLevel, "点击确认"));
        m.put("escalationPolicy", LEVEL_ESCALATION.getOrDefault(alertLevel, "—"));
        return m;
    }

    public synchronized Map<String, Object> status() {
        List<Map<String, Object>> rows = new ArrayList<>();
        int confirmed = 0;
        int escalated = 0;
        for (TargetStatus t : targets.values()) {
            rows.add(t.toMap());
            if (t.confirmed()) {
                confirmed++;
            }
            if (t.escalated()) {
                escalated++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", activePlanId == null ? "" : activePlanId);
        m.put("dispatchedAt", dispatchedAt);
        m.put("targets", rows);
        m.put("total", rows.size());
        m.put("confirmed", confirmed);
        m.put("pending", rows.size() - confirmed);
        m.put("escalated", escalated);
        // 分级叫应等级（黄/橙/红）：驱动大屏与司机端的分级标识与升级策略展示
        m.put("alertLevel", alertLevel);
        m.put("alertLevelName", LEVEL_NAMES.getOrDefault(alertLevel, alertLevel));
        m.put("alertChannel", LEVEL_CHANNELS.getOrDefault(alertLevel, "App推送"));
        m.put("confirmMode", LEVEL_CONFIRM.getOrDefault(alertLevel, "点击确认"));
        m.put("escalationPolicy", LEVEL_ESCALATION.getOrDefault(alertLevel, "—"));
        m.put("reported", new ArrayList<>(reported));
        return m;
    }

    public synchronized Map<String, Object> reset() {
        targets.clear();
        messages.clear();
        activePlanId = null;
        dispatchedAt = 0;
        alertLevel = "NORMAL";
        reported.clear();
        broadcast();
        return status();
    }

    // ==================== 内部 ====================

    private void put(TargetStatus status, Map<String, String> msg) {
        targets.put(status.id(), status);
        messages.put(status.id(), msg);
    }

    private void autoConfirmPublic(int count) {
        int done = 0;
        for (int i = 1; i <= PUBLIC_COUNT && done < count; i++) {
            String id = String.format("pub-%02d", i);
            TargetStatus t = targets.get(id);
            if (t != null && !t.confirmed()) {
                targets.put(id, new TargetStatus(t.id(), t.role(), t.roleName(), t.name(),
                        true, true, false, t.channel(), System.currentTimeMillis()));
                done++;
            }
        }
        if (done > 0) {
            decisionLog.log("CONFIRM", "沿岸百姓确认", done + " 名沿岸百姓已确认预警");
            broadcast();
        }
    }

    /** 分级叫应：对未确认目标自动发起二次呼叫（语音外呼） */
    private void escalateUnconfirmed() {
        int n = 0;
        for (Map.Entry<String, TargetStatus> e : targets.entrySet()) {
            TargetStatus t = e.getValue();
            if (!t.confirmed()
                    && DemoConfigService.TARGET_PUBLIC.equals(t.role())) {
                targets.put(e.getKey(), new TargetStatus(t.id(), t.role(), t.roleName(), t.name(),
                        true, false, true, "语音外呼", null));
                n++;
            }
        }
        if (n > 0) {
            decisionLog.log("ESCALATE", "二次呼叫", n + " 名沿岸百姓未确认，已自动升级为语音外呼");
            broadcast();
        }
    }

    private void confirmEscalated() {
        int n = 0;
        for (Map.Entry<String, TargetStatus> e : targets.entrySet()) {
            TargetStatus t = e.getValue();
            if (t.escalated() && !t.confirmed()) {
                targets.put(e.getKey(), new TargetStatus(t.id(), t.role(), t.roleName(), t.name(),
                        true, true, true, t.channel(), System.currentTimeMillis()));
                n++;
            }
        }
        if (n > 0) {
            decisionLog.log("CONFIRM", "二次呼叫确认", n + " 名沿岸百姓通过语音外呼完成确认");
            broadcast();
        }
    }

    private void broadcast() {
        try {
            routeAgentService.broadcastEvent("outreach-update", status());
        } catch (Exception e) {
            log.debug("outreach broadcast failed: {}", e.toString());
        }
    }

    /**
     * 分级叫应等级研判：由沙盘风险状态推导。
     * 红=已触发熔断/禁航；橙=降雨达阈值 80% 以上（逼近）；黄=达阈值 50% 以上（关注）。
     * r 取友谊关、芒街两断面「预报降雨 / 各自熔断阈值」的最大值。
     */
    private String computeAlertLevel() {
        if (sandbox.roadBlocked() || sandbox.waterBlocked()) {
            return "RED";
        }
        double r = Math.max(
                ratio(sandbox.ygg().forecastMm, config.fuseThresholdMm()),
                ratio(sandbox.mc().forecastMm, DecisionSandboxService.MC_THRESHOLD_MM));
        if (r >= 0.8) {
            return "ORANGE";
        }
        if (r >= 0.5) {
            return "YELLOW";
        }
        return "NORMAL";
    }

    private static double ratio(double mm, double threshold) {
        return threshold <= 0 ? 0.0 : mm / threshold;
    }

    /** 红色级叫应闭环：叫应窗口结束仍未确认的关键角色（司机/船东）自动上报调度端。 */
    private void reportUnconfirmedToDispatcher() {
        List<String> names = new ArrayList<>();
        for (TargetStatus t : targets.values()) {
            if (!t.confirmed()
                    && !DemoConfigService.TARGET_DISPATCHER.equals(t.role())
                    && !DemoConfigService.TARGET_PUBLIC.equals(t.role())
                    && reported.add(t.id())) {
                names.add(t.roleName());
            }
        }
        if (!names.isEmpty()) {
            decisionLog.log("ESCALATE", "红色叫应上报调度端",
                    "红色立即行动级：" + String.join("、", names) + " 超时未确认，已自动上报调度端人工介入");
            broadcast();
        }
    }

    private String enabledTargetNames() {
        List<String> names = new ArrayList<>();
        for (String t : DemoConfigService.ALL_TARGETS) {
            if (config.targetEnabled(t) && !DemoConfigService.TARGET_DISPATCHER.equals(t)) {
                names.add(DemoConfigService.TARGET_NAMES.get(t));
            }
        }
        return names.isEmpty() ? "各执行端" : String.join("、", names);
    }

    /** 调度员回执：按双向切换方向说明系统已自动触达的相关方（3.4 决策输出）。 */
    private String dispatcherMessage(String planId, String planName, String direction) {
        String base = String.format("✅ 已确认切换%s方案。", planName);
        if ("B".equals(planId) || "road_to_water".equals(direction)) {
            // 公路切水运：通知司机去港口、船长准备装船、港口安排泊位
            return base + "【公路→水运·平陆运河】任务变更指令已推送：司机前往南宁港六景作业区、船长准备装船、港口安排泊位。";
        }
        if ("water_to_road".equals(direction)) {
            // 水运切公路：通知船长取消装船、司机准备接货、口岸安排通关
            return base + "【水运→公路】任务变更指令已推送：船长取消装船、司机准备接货、口岸安排通关。";
        }
        if ("dual_risk".equals(direction)) {
            return base + "【双线风险】原地等待指令已推送至各执行端，待任一通道解除红线后再发车。";
        }
        return base + String.format("任务变更指令已推送至%s。", enabledTargetNames());
    }

    /** 船东/船员指令：公路切水运=准备装船；水运切公路=取消装船；双线风险=锚泊等待；正常=过闸建议。 */
    private String shipMessage(String planId, boolean waterBlocked, boolean roadBlocked) {
        if ("B".equals(planId)) {
            // 装船时刻跟真实时钟走（原写死 15:30，与演示实际时间脱节）
            return "🚢 装船准备通知\n"
                    + "友谊关公路熔断，调度中心已切换公水联运方案，改走平陆运河。\n"
                    + "请做好装船准备：车辆滚装预计" + DemoClock.boarding().start() + "开始，港口已安排六景作业区泊位。\n"
                    + "请检查冷链恒温舱供电接口，装船完成后回报离泊时间。";
        }
        if (waterBlocked && "A".equals(planId)) {
            return "🚢 取消装船通知\n"
                    + "平陆运河禁航（" + (sandbox.waterBlockedReason() == null ? "通航条件超限" : sandbox.waterBlockedReason())
                    + "），调度中心已切换公路运输方案。\n"
                    + "请取消本次装船计划，已装货车辆协助卸船交接，船舶就地锚泊待命。\n"
                    + "恢复通航后调度中心将重新下发装船窗口。";
        }
        if (waterBlocked && roadBlocked) {
            return "🚢 锚泊等待通知\n"
                    + "公路与水运双线风险叠加，调度中心决定原地等待。\n"
                    + "请船舶就近锚泊，保持值守，关注能见度/流速变化，等待调度中心进一步指令。";
        }
        return "🚢 过闸建议\n"
                + "当前平陆运河马道枢纽上游流速1.8m/s，能见度1200m，未触发禁航红线。\n"
                + "建议船舶于" + DemoClock.slot(60) + "前完成装船，抢在下一波降雨前过闸。\n"
                + "若" + DemoClock.slot(180) + "后能见度降至1000m以下，建议锚泊等待，预计等待时间3小时。";
    }

    /** 司机任务变更通知：执行指令 + 权益保障包。越南语文案为人工撰写底稿（待母语者校验）。 */
    private Map<String, String> driverMessages(String planId, boolean waterBlocked) {
        String zh;
        String vi;
        if ("A".equals(planId) && waterBlocked) {
            // 水运切公路：司机准备接货、口岸安排通关
            zh = "📱 任务变更通知\n"
                    + "平陆运河禁航（" + (sandbox.waterBlockedReason() == null ? "通航条件超限" : sandbox.waterBlockedReason())
                    + "），调度中心已切换公路运输方案。\n"
                    + "请前往指定货场准备接货，新路线已下发，口岸已安排优先通关。\n\n"
                    + "您的权益保障：\n"
                    + "· 接驳与绕行增加工时按出勤计算，发放专项补贴\n"
                    + "· 本次变更属于不可抗力导致的调度调整，不视为司机违约\n\n"
                    + "请确认接收。如有异议，点击\"联系调度\"。";
            vi = "📱 Thông báo thay đổi nhiệm vụ\n"
                    + "Kênh đào Bình Lục cấm hành do tầm nhìn thấp. "
                    + "Trung tâm điều độ đã chuyển sang phương án vận tải đường bộ.\n"
                    + "Anh vui lòng đến bãi hàng chỉ định để nhận hàng, lộ trình mới đã được gửi, "
                    + "cửa khẩu đã bố trí thông quan ưu tiên.\n\n"
                    + "Quyền lợi của anh:\n"
                    + "· Thởi gian tăng thêm được tính công tác, có phụ cấp chuyên dụng\n"
                    + "· Thay đổi lần này do bất khả kháng, không tính là vi phạm hợp đồng\n\n"
                    + "Vui lòng xác nhận đã nhận. Nếu có thắc mắc, bấm \"Liên hệ điều độ\".";
        } else if ("B".equals(planId)) {
            // 抵达截止时刻用同一次计算结果，中越两版文案随之统一（原写死 15:30）
            DemoClock.Boarding boarding = DemoClock.boarding();
            zh = "📱 任务变更通知\n"
                    + "原路线友谊关段因暴雨熔断，调度中心已切换公水联运方案，改走平陆运河。\n"
                    + "请于" + boarding.arriveBy() + "前抵达南宁港六景作业区，将车辆交由代驾上船。\n"
                    + "您本人作为乘客随船，经平陆运河至越南海防港，船上已安排休息舱位。\n\n"
                    + "您的权益保障：\n"
                    + "· 随船期间按出勤计算工时，额外发放随船补贴\n"
                    + "· 车辆滚装段已购买专项运输险\n"
                    + "· 船上安排司机休息舱位，含餐饮\n"
                    + "· 抵达海防港后公司安排返程交通\n"
                    + "· 本次变更属于不可抗力导致的调度调整，不视为司机违约\n\n"
                    + "请确认接收。如有异议，点击\"联系调度\"。";
            vi = "📱 Thông báo thay đổi nhiệm vụ\n"
                    + "Tuyến qua cửa khẩu Hữu Nghị Quan bị phong tỏa do mưa lớn. "
                    + "Trung tâm điều độ đã chuyển sang phương án vận tải liên hợp đường bộ - đường thủy, qua kênh đào Bình Lục.\n"
                    + "Anh vui lòng có mặt tại khu vực Lục Cảnh, cảng Nam Ninh trước " + boarding.arriveBy() + ", "
                    + "bàn giao xe cho tài xế thay để đưa lên tàu.\n"
                    + "Anh đi cùng tàu với tư cách hành khách, qua kênh đào Bình Lục đến cảng Hải Phòng, "
                    + "trên tàu đã bố trí khoang nghỉ ngơi.\n\n"
                    + "Quyền lợi của anh:\n"
                    + "· Thởi gian đi cùng tàu được tính công tác, có thêm phụ cấp đi tàu\n"
                    + "· Đoạn xe lên tàu (ro-ro) đã mua bảo hiểm vận tải chuyên dụng\n"
                    + "· Khoang nghỉ ngơi trên tàu có kèm bữa ăn\n"
                    + "· Sau khi đến Hải Phòng, công ty bố trí phương tiện đưa về\n"
                    + "· Thay đổi lần này do bất khả kháng, không tính là vi phạm hợp đồng của tài xế\n\n"
                    + "Vui lòng xác nhận đã nhận. Nếu có thắc mắc, bấm \"Liên hệ điều độ\".";
        } else if ("A".equals(planId)) {
            zh = "📱 任务变更通知\n"
                    + "原路线友谊关段因暴雨熔断，调度中心已切换公路绕行方案：改走芒街口岸入境。\n"
                    + "新路线已下发，请按导航行驶，芒街段降雨偏高请减速慢行。\n\n"
                    + "您的权益保障：\n"
                    + "· 绕行增加工时按出勤计算，发放绕行补贴\n"
                    + "· 本次变更属于不可抗力导致的调度调整，不视为司机违约\n\n"
                    + "请确认接收。如有异议，点击\"联系调度\"。";
            vi = "📱 Thông báo thay đổi nhiệm vụ\n"
                    + "Tuyến qua cửa khẩu Hữu Nghị Quan bị phong tỏa do mưa lớn. "
                    + "Trung tâm điều độ đã chuyển sang phương án đường bộ vòng qua cửa khẩu Móng Cái.\n"
                    + "Lộ trình mới đã được gửi, anh vui lòng đi theo chỉ đường, "
                    + "đoạn Móng Cái có mưa lớn vui lòng giảm tốc độ.\n\n"
                    + "Quyền lợi của anh:\n"
                    + "· Thởi gian tăng thêm do đi vòng được tính công tác, có phụ cấp\n"
                    + "· Thay đổi lần này do bất khả kháng, không tính là vi phạm hợp đồng\n\n"
                    + "Vui lòng xác nhận đã nhận. Nếu có thắc mắc, bấm \"Liên hệ điều độ\".";
        } else {
            zh = "📱 任务变更通知\n"
                    + "原路线友谊关段因暴雨熔断，调度中心决定原地等待，预计等待6小时。\n"
                    + "请就近停靠安全区域，保持冷链机组运行，每小时汇报一次车厢温度。\n\n"
                    + "您的权益保障：\n"
                    + "· 等待期间按出勤计算工时\n"
                    + "· 本次变更属于不可抗力导致的调度调整，不视为司机违约\n\n"
                    + "请确认接收。如有异议，点击\"联系调度\"。";
            vi = "📱 Thông báo thay đổi nhiệm vụ\n"
                    + "Tuyến qua cửa khẩu Hữu Nghị Quan bị phong tỏa do mưa lớn. "
                    + "Trung tâm điều độ quyết định chờ tại chỗ, dự kiến 6 giờ.\n"
                    + "Anh vui lòng đỗ xe tại khu vực an toàn gần nhất, giữ máy lạnh hoạt động, "
                    + "báo cáo nhiệt độ thùng xe mỗi giờ.\n\n"
                    + "Quyền lợi của anh:\n"
                    + "· Thởi gian chờ được tính công tác\n"
                    + "· Thay đổi lần này do bất khả kháng, không tính là vi phạm hợp đồng\n\n"
                    + "Vui lòng xác nhận đã nhận. Nếu có thắc mắc, bấm \"Liên hệ điều độ\".";
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("message", zh);
        m.put("messageVi", vi);
        return m;
    }

    /**
     * 越南司机（越方接力）指令：与中文司机同一套权益保障语义，但**以越南语为主文案**。
     * <p>
     * 复用 {@link #driverMessages} 的人工底稿，只把中越两段调换主次——
     * 避免同一套保障条款维护两份译文而产生不一致。
     * 返回 {@code message}=越南语（司机端默认显示）、{@code messageZh}=中文（供切换查看）。
     */
    private Map<String, String> driverMessagesVn(String planId, boolean waterBlocked) {
        Map<String, String> bilingual = driverMessages(planId, waterBlocked);
        String vi = bilingual.get("messageVi");
        String zh = bilingual.get("message");
        Map<String, String> m = new LinkedHashMap<>();
        // 越南语底稿缺失时退回中文，保证任何情况下都有可下发的内容
        m.put("message", vi != null ? vi : zh);
        if (vi != null) {
            m.put("messageZh", zh);
        }
        return m;
    }
}
