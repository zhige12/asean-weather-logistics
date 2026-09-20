package com.example.aseanweatherlogistics.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点（计划书阶段一验收标准）：
 * 浏览器访问 http://localhost:8080/ping 返回 "OK"。
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    public String ping() {
        return "OK";
    }
}
