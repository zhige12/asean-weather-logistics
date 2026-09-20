package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.TaskService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运输任务派单端点（演示第一/四幕）：
 * - POST /api/task/dispatch  公司派单 → SSE 广播 task-assigned，司机端自动切物流任务模式
 * - GET  /api/task/current   司机端启动/重连拉取当前任务（无任务则保持普通导航模式）
 * - POST /api/task/accept    司机接单确认
 * - POST /api/task/reset     演示复位，撤销任务
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/dispatch")
    public Map<String, Object> dispatch(@RequestBody(required = false) Map<String, Object> body) {
        return taskService.dispatch(body);
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return taskService.current();
    }

    @PostMapping("/accept")
    public Map<String, Object> accept(@RequestBody(required = false) Map<String, Object> body) {
        String taskId = body != null && body.get("taskId") instanceof String s ? s : null;
        String driverName = body != null && body.get("driverName") instanceof String s ? s : null;
        return taskService.accept(taskId, driverName);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return taskService.reset();
    }
}