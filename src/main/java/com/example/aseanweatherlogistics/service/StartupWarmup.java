package com.example.aseanweatherlogistics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热：应用启动后后台跑一次 南宁(NN)→河内(HN) 规划，
 * 提前触发 JIT 编译 Dijkstra 热点方法，保证演示时"第一次点击规划"也是毫秒级。
 * 预热失败不影响启动（daemon 线程 + try/catch）。
 */
@Component
public class StartupWarmup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    private final RouteService routeService;

    public StartupWarmup(RouteService routeService) {
        this.routeService = routeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                routeService.planRoute("NN", "HN");
                log.info("[warmup] NN→HN 规划预热完成，耗时 {}ms", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("[warmup] 预热失败（不影响启动）: {}", e.getMessage());
            }
        }, "startup-warmup");
        t.setDaemon(true);
        t.start();
    }
}
