package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.TransportTask;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运输任务派单（演示第一/四幕「公司给我派了单，我点开 App 自动切换到物流任务模式」）。
 * <p>
 * 剧本里阿明之所以从"普通导航用户"变成"执行调度指令的司机"，全靠派单这一下：
 * 派单前司机端是免费的普通导航模式，派单到达后才出现车牌、货物与路线状态条。
 * <p>
 * 实现要点：
 * <ul>
 *   <li>内存态单任务（演示足够）；全部状态变化经
 *       {@link RouteAgentService#broadcastEvent} 以 {@code task-assigned} 事件广播，
 *       大屏派单面板与司机端共用一条 SSE 流；</li>
 *   <li>派单同时调用 {@link RouteAgentService#registerActiveRoute}，
 *       让实时守护中枢改盯这单的起终点，后续灾害重算才作用在正确的线上；</li>
 *   <li>司机端启动时用 {@code GET /api/task/current} 拉取，
 *       刷新页面 / 断线重连仍能回到物流任务模式（"拔网线照样跑"的一部分）。</li>
 * </ul>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    /** 默认派单内容（剧本：公司派阿明从南宁拉 18 吨火龙果去河内） */
    public static final String DEFAULT_PLATE = "桂A·D12345";
    public static final String DEFAULT_DRIVER = "阿明";
    public static final String DEFAULT_DRIVER_VN = "阿雄";
    public static final String DEFAULT_ORIGIN = "NN";
    public static final String DEFAULT_DESTINATION = "HN";

    private final DemoConfigService config;
    private final DecisionLogService decisionLog;
    private final RouteAgentService routeAgentService;

    /** 任务号自增序号 */
    private final AtomicLong seq = new AtomicLong();

    /** 当前进行中的运输任务（null = 无任务，司机端处于普通导航模式） */
    private volatile TransportTask current;

    public TaskService(DemoConfigService config, DecisionLogService decisionLog,
                       RouteAgentService routeAgentService) {
        this.config = config;
        this.decisionLog = decisionLog;
        this.routeAgentService = routeAgentService;
    }

    /**
     * 公司派单：创建运输任务并广播给司机端（司机端收到即切物流任务模式）。
     * body 各字段均可选，缺省取剧本默认值。
     */
    public synchronized Map<String, Object> dispatch(Map<String, Object> body) {
        // 货物类型决定货损敏感度模型，缺省沿用平台配置器当前选中的货物
        String cargoTypeId = str(body, "cargoType", null);
        DemoConfigService.CargoType cargo = cargoTypeId != null
                && DemoConfigService.CARGO_TYPES.containsKey(cargoTypeId)
                ? DemoConfigService.CARGO_TYPES.get(cargoTypeId)
                : config.cargo();

        TransportTask t = new TransportTask();
        t.setTaskId("TASK-" + seq.incrementAndGet());
        t.setPlate(str(body, "plate", DEFAULT_PLATE));
        t.setDriverName(str(body, "driverName", DEFAULT_DRIVER));
        t.setDriverNameVn(str(body, "driverNameVn", DEFAULT_DRIVER_VN));
        t.setCargoType(cargo.id());
        t.setCargoName(str(body, "cargoName", cargo.name()));
        t.setWeightT(num(body, "weightT", 18.0));
        t.setTemp(str(body, "temp", cargo.coldChain() ? "冷鲜 2~6°C" : "常温"));
        t.setOriginId(str(body, "originId", DEFAULT_ORIGIN));
        t.setDestinationId(str(body, "destinationId", DEFAULT_DESTINATION));
        t.setDeadline(str(body, "deadline", "次日 18:00 前送达河内仓库"));
        t.setStatus(STATUS_DISPATCHED);
        t.setDispatchedAt(System.currentTimeMillis());
        this.current = t;

        // 实时守护中枢改盯这一单的起终点
        routeAgentService.registerActiveRoute(t.getOriginId(), t.getDestinationId());

        decisionLog.log("TASK", "公司派单",
                String.format("%s 承运 %s %s（%s），%s → %s，任务号 %s",
                        t.getPlate(), fmtWeight(t.getWeightT()), t.getCargoName(),
                        t.getDriverName(), t.getOriginId(), t.getDestinationId(), t.getTaskId()));
        log.info("task dispatched: {} {} {}t", t.getTaskId(), t.getCargoName(), t.getWeightT());

        broadcast();
        return current();
    }

    /**
     * 司机接单确认。司机端进入物流任务模式后点「确认接单」调用。
     *
     * @param driverName 接单司机（缺省用任务上的承运司机）
     */
    public synchronized Map<String, Object> accept(String taskId, String driverName) {
        TransportTask t = this.current;
        if (t == null || (taskId != null && !taskId.isBlank() && !taskId.equals(t.getTaskId()))) {
            throw new IllegalArgumentException("无匹配的运输任务：" + taskId);
        }
        if (!STATUS_ACCEPTED.equals(t.getStatus())) {
            t.setStatus(STATUS_ACCEPTED);
            t.setAcceptedAt(System.currentTimeMillis());
            String who = (driverName == null || driverName.isBlank()) ? t.getDriverName() : driverName;
            decisionLog.log("TASK", "司机接单", who + " 已接单，任务号 " + t.getTaskId());
            broadcast();
        }
        return current();
    }

    /** 司机端启动 / 重连时拉取当前任务（无任务时 status=NONE，司机端保持普通导航模式）。 */
    public Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        TransportTask t = this.current;
        if (t == null) {
            m.put("task", null);
            m.put("status", STATUS_NONE);
            return m;
        }
        m.put("task", t);
        m.put("status", t.getStatus());
        m.put("cargoTypeName", cargoNameOf(t));
        return m;
    }

    /** 演示复位：撤销当前任务，司机端退回普通导航模式。 */
    public synchronized Map<String, Object> reset() {
        TransportTask t = this.current;
        this.current = null;
        if (t != null) {
            decisionLog.log("TASK", "派单撤销",
                    "已撤销任务 " + t.getTaskId() + "，司机端退回普通导航模式");
        }
        broadcast();
        return current();
    }

    private void broadcast() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "task-assigned");
            payload.putAll(current());
            payload.put("ts", System.currentTimeMillis());
            routeAgentService.broadcastEvent("task-assigned", payload);
        } catch (Exception e) {
            log.debug("task-assigned broadcast failed: {}", e.toString());
        }
    }

    private static String cargoNameOf(TransportTask t) {
        DemoConfigService.CargoType c = DemoConfigService.CARGO_TYPES.get(t.getCargoType());
        return c == null ? t.getCargoName() : c.name();
    }

    private static String fmtWeight(Double w) {
        if (w == null) {
            return "";
        }
        return (w == Math.floor(w) ? String.format("%.0f", w) : String.format("%.1f", w)) + " 吨";
    }

    private static String str(Map<String, Object> body, String key, String def) {
        if (body != null && body.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        return def;
    }

    private static Double num(Map<String, Object> body, String key, Double def) {
        if (body != null && body.get(key) instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }
}