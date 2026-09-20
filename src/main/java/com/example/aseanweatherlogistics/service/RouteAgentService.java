package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.dto.RiskSegment;
import com.example.aseanweatherlogistics.model.dto.RouteResponse;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 实时守护中枢：
 * 订阅风险变化（灾害注入/清除、实时气象同步），防抖后毫秒级重算当前活跃路线，
 * 并通过 SSE 推送给调度大屏与司机端，实现「突发灾害马上出新路线」。
 */
@Service
public class RouteAgentService {
    private static final Logger log = LoggerFactory.getLogger(RouteAgentService.class);

    private final RouteService routeService;
    private final WeatherSimulator weatherSimulator;

    /** SSE 订阅者（调度大屏 / 司机端） */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 关注的路线集合（origin|destination → origin,destination）：
     * 任何一方注册关注即加入——正在导航的司机、已选线但未开始的司机、
     * 目的地是越南端的调度/司机、调度大屏本身。风险变化时对集合内每条路线重算并广播。
     * 已停止导航的司机通过 unregister 移除，避免被无关路线打扰。
     */
    private final Map<String, String[]> watchedRoutes = new ConcurrentHashMap<>();

    /** 兜底主路线（无关注者时也保证大屏能收到一条） */
    private volatile String activeOrigin = "NN";
    private volatile String activeDestination = "HN";

    /** 防抖：300ms 窗口内多次风险变化合并为一次重算 */
    private volatile boolean recomputeScheduled = false;
    private volatile String pendingReason = "风险变化";

    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-debounce");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-recompute");
        t.setDaemon(true);
        return t;
    });

    public RouteAgentService(RouteService routeService, WeatherSimulator weatherSimulator) {
        this.routeService = routeService;
        this.weatherSimulator = weatherSimulator;
    }

    @PostConstruct
    public void init() {
        // 任何风险增删改（灾害注入/清除/实时气象同步）都触发 agent 重算
        weatherSimulator.setChangeListener(() -> notifyRiskChanged("风险变化"));
    }

    /**
     * 注册关注某条路线（正在导航 / 已选线未开始 / 目的地为越南端 / 调度大屏）。
     * 一旦风险变化，该路线会随其它关注路线一起被重算并广播。
     */
    public void registerActiveRoute(String originId, String destinationId) {
        if (originId != null && destinationId != null
                && !originId.isBlank() && !destinationId.isBlank()) {
            this.activeOrigin = originId;
            this.activeDestination = destinationId;
            watchedRoutes.put(key(originId, destinationId), new String[]{originId, destinationId});
        }
    }

    /** 停止关注某条路线（司机结束导航 / 退出，避免被无关路线打扰） */
    public void unregisterActiveRoute(String originId, String destinationId) {
        if (originId != null && destinationId != null) {
            watchedRoutes.remove(key(originId, destinationId));
        }
    }

    private String key(String originId, String destinationId) {
        return originId + "|" + destinationId;
    }

    /** 需要重算并广播的关注路线清单（含兜底主路线） */
    private List<String[]> watchedSnapshot() {
        Map<String, String[]> merged = new LinkedHashMap<>(watchedRoutes);
        merged.putIfAbsent(key(activeOrigin, activeDestination), new String[]{activeOrigin, activeDestination});
        return new ArrayList<>(merged.values());
    }

    public Map<String, Object> status() {
        return Map.of(
                "activeOrigin", activeOrigin,
                "activeDestination", activeDestination,
                "watchedRoutes", watchedRoutes.keySet(),
                "subscribers", emitters.size(),
                "lastReason", pendingReason);
    }

    /** 新客户端订阅 SSE 推送流 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由客户端断开驱动
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("init").data(Map.of(
                    "origin", activeOrigin,
                    "destination", activeDestination)));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** 风险变化入口：防抖合并后异步重算并广播 */
    public void notifyRiskChanged(String reason) {
        this.pendingReason = reason;
        if (recomputeScheduled) {
            return;
        }
        synchronized (this) {
            if (recomputeScheduled) {
                return;
            }
            recomputeScheduled = true;
        }
        debouncer.schedule(this::debouncedRecompute, 300, TimeUnit.MILLISECONDS);
    }

    private void debouncedRecompute() {
        recomputeScheduled = false;
        String reason = pendingReason;
        worker.submit(() -> recomputeAndBroadcast(reason));
    }

    /** 风险变化：对所有关注路线（导航中 / 已选线未开始 / 目的地越南端 / 调度大屏）逐条重算并广播 */
    private void recomputeAndBroadcast(String reason) {
        // 提取当前风险边 ID 列表，前端可用此列表调用 /api/route/detour 做局部绕行
        List<String> hazardEdgeIds = weatherSimulator.currentRisks().stream()
                .map(com.example.aseanweatherlogistics.model.dto.RiskSegment::getEdgeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        List<com.example.aseanweatherlogistics.model.dto.RiskSegment> risks = weatherSimulator.currentRisks();
        for (String[] od : watchedSnapshot()) {
            broadcastForRoute(reason, od[0], od[1], hazardEdgeIds, risks);
        }
    }

    /** 重算单条关注路线并广播（payload 带 origin/destination，各端据此过滤自己的路线） */
    private void broadcastForRoute(String reason, String originId, String destinationId,
                                   List<String> hazardEdgeIds,
                                   List<com.example.aseanweatherlogistics.model.dto.RiskSegment> risks) {
        try {
            RouteResponse resp = routeService.planRouteFast(originId, destinationId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "route-update");
            payload.put("origin", originId);
            payload.put("destination", destinationId);
            payload.put("reason", reason);
            payload.put("advice", buildAdvice(resp));
            payload.put("route", resp);
            payload.put("risks", risks);
            payload.put("hazardEdgeIds", hazardEdgeIds);
            payload.put("ts", System.currentTimeMillis());
            broadcast(payload);
        } catch (Exception e) {
            log.warn("agent recompute failed [{}→{}]: {}", originId, destinationId, e.toString());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "route-update");
            payload.put("origin", originId);
            payload.put("destination", destinationId);
            payload.put("reason", reason);
            payload.put("advice", "当前风险配置下无可用路径，请人工介入或解除部分封路。");
            payload.put("error", String.valueOf(e.getMessage()));
            payload.put("risks", risks);
            payload.put("hazardEdgeIds", hazardEdgeIds);
            payload.put("ts", System.currentTimeMillis());
            broadcast(payload);
        }
    }

    /** 规则化 agent 建议文案（离线可用、毫秒级返回） */
    private String buildAdvice(RouteResponse resp) {
        if (resp.isRerouted()) {
            List<RiskSegment> risks = resp.getRiskSegments();
            String top = (risks == null || risks.isEmpty()) ? "突发灾害" : risks.get(0).getReason();
            return String.format(
                    "⚠ %s。已自动重算绕行路线：基准 %.0f h → 当前 %.0f h，预计延误 %.0f h。请调度中心确认后推送司机端。",
                    top, resp.getBaselineHours(), resp.getCurrentHours(), resp.getExtraHours());
        }
        if (resp.getRiskSegments() != null && !resp.getRiskSegments().isEmpty()) {
            return String.format(
                    "沿线存在 %d 处风险（如 %s），当前路线已避开或风险可控，请谨慎驾驶、实时关注气象。",
                    resp.getRiskSegments().size(), resp.getRiskSegments().get(0).getReason());
        }
        return "风险已解除，路线恢复正常通行，预计 %.0f h 到达。".formatted(resp.getCurrentHours());
    }

    private void broadcast(Map<String, Object> payload) {
        broadcastEvent("route-update", payload);
    }

    /**
     * 供其他业务复用 SSE 通道广播自定义事件（如多角色触达 outreach-update、决策沙盘更新），
     * 大屏与司机端只需订阅一条流即可接收全部实时事件。
     */
    public void broadcastEvent(String name, Map<String, Object> payload) {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已断开
                }
            }
        }
    }
}
