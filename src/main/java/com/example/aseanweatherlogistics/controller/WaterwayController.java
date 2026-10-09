package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.waterway.WaterwayDataService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运河通航观测数据接入端点（预留「广西水运江河海一体化调度平台」对接）：
 * - GET  /api/waterway/status      接入层状态（当前数据源/最近观测/官方接口是否已配置）
 * - POST /api/waterway/push        模拟一轮平台回传（比赛演示：传 visibilityM=900 即触发禁航链路）
 * - POST /api/waterway/poll        立即执行一轮拉取（source=official 且接口已配置时真实回源）
 * <p>
 * 与沙盘 /api/sandbox/water 的区别：沙盘是"调度员手动调参"入口；
 * 本端点是"官方平台数据进来"的入口——参数直接透传给禁航红线引擎，
 * 走同一套 evaluate → canal-block / canal-recover 广播链路。
 */
@RestController
@RequestMapping("/api/waterway")
public class WaterwayController {

    private final WaterwayDataService waterwayDataService;

    public WaterwayController(WaterwayDataService waterwayDataService) {
        this.waterwayDataService = waterwayDataService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return waterwayDataService.status();
    }

    /** 模拟平台推送：四类参数均可选（m / m·s⁻¹），缺省项沿用正常基准值。 */
    @PostMapping("/push")
    public Map<String, Object> push(@RequestParam(required = false) Double visibilityM,
                                    @RequestParam(required = false) Double currentMs,
                                    @RequestParam(required = false) Double waveHeightM,
                                    @RequestParam(required = false) Double windMs) {
        return waterwayDataService.simulatePlatformPush(visibilityM, currentMs, waveHeightM, windMs);
    }

    @PostMapping("/poll")
    public Map<String, Object> poll() {
        return waterwayDataService.pollOnce();
    }
}
