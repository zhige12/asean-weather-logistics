package com.example.aseanweatherlogistics.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把参数错误/非法输入转成友好的 400 响应（而不是 500 堆栈），
 * 保证司机端/调度大屏在误操作或异常输入时不白屏、不弹系统错误。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数非法 / 节点不存在 / 场景不存在：400 + 可读提示 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "参数错误", ex.getMessage());
    }

    /** 无可用路径等业务性异常：409（冲突），提示需要人工介入 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, "当前无法规划", ex.getMessage());
    }

    /** 缺少必填参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "缺少参数", "缺少必填参数：" + ex.getParameterName());
    }

    /** 兜底：任何未捕获异常都记日志并返回结构化 500，避免前端拿到 HTML 错误页 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("未捕获异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务异常",
                ex.getMessage() == null ? "服务器内部错误" : ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("ts", System.currentTimeMillis());
        return ResponseEntity.status(status).body(body);
    }
}
