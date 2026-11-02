package com.example.aseanweatherlogistics.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.aseanweatherlogistics.service.AuthService;
import com.example.aseanweatherlogistics.service.CaptchaService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 门户登录/注册端点（演示级身份壳，纯内存账号，见 AuthService 的取舍说明）：
 * - POST /api/auth/register 注册（成功即登录）
 * - POST /api/auth/login    登录（会话写入 HttpSession，容器发 Cookie）
 * - POST /api/auth/logout   退出（invalidate 会话）
 * - GET  /api/auth/session  查询当前身份（未登录 ok=false，前端据此弹登录壳）
 * 刻意不做服务端强制拦截：调度大屏/司机端保持免登录可达（比赛演示链路不能断），
 * 登录壳只作用于门户首页的身份展示。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 会话里存用户名用的键。 */
    private static final String SESSION_USER = "portalUser";

    private final AuthService authService;
    private final CaptchaService captchaService;

    public AuthController(AuthService authService, CaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        if (!captchaOk(request, body)) {
            return withCount(captchaFail());
        }
        Map<String, Object> r = authService.register(str(body, "username"), str(body, "password"));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            request.getSession(true).setAttribute(SESSION_USER, r.get("name"));
        }
        return withCount(r);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        if (!captchaOk(request, body)) {
            return withCount(captchaFail());
        }
        Map<String, Object> r = authService.login(str(body, "username"), str(body, "password"));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            request.getSession(true).setAttribute(SESSION_USER, r.get("name"));
        }
        return withCount(r);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("name", null);
        return r;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        Object name = s == null ? null : s.getAttribute(SESSION_USER);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", name != null);
        r.put("name", name);
        r.put("accounts", authService.count());
        return r;
    }

    /** 取一张图形验证码：建会话存答案，回 Base64 data URL（前端直接当 img src）。 */
    @GetMapping("/captcha")
    public Map<String, Object> captcha(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("image", captchaService.issue(session));
        return r;
    }

    /** 登录/注册前置闸门：验证码单次消费，错/过期/没取过都拒（verify 内已作废）。 */
    private boolean captchaOk(HttpServletRequest request, Map<String, Object> body) {
        HttpSession session = request.getSession(false);
        return captchaService.verifyAndConsume(session, str(body, "captcha"));
    }

    private static Map<String, Object> captchaFail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", "验证码错误或已过期，请重新输入");
        return m;
    }

    private Map<String, Object> withCount(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("accounts", authService.count());
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
