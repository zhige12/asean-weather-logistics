package com.example.aseanweatherlogistics.service;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演示级账号服务的确定性行为固化：注册校验、重名拒绝、口令校验（PBKDF2+盐）、
 * 用户名大小写不敏感、失败话术不泄露「账号是否存在」。内存存储无外部依赖，直接 new。
 */
class AuthServiceTest {

    private static boolean ok(Map<String, Object> r) {
        return Boolean.TRUE.equals(r.get("ok"));
    }

    @Test
    void register_thenLogin_succeeds() {
        AuthService auth = new AuthService();
        assertTrue(ok(auth.register("调度员", "abc123")));
        assertEquals(1, auth.count());
        Map<String, Object> login = auth.login("调度员", "abc123");
        assertTrue(ok(login));
        assertEquals("调度员", login.get("name"));
    }

    @Test
    void register_rejectsWeakInput() {
        AuthService auth = new AuthService();
        assertFalse(ok(auth.register("a", "abc123")), "单字符用户名应拒绝");
        assertFalse(ok(auth.register("合法名", "123")), "短口令应拒绝");
        assertTrue(ok(auth.register("合法名", "abcd")));
        assertFalse(ok(auth.register("合法名", "another")), "重名应拒绝");
        // 大小写视为同一账号，防止「Admin/admin」两个壳
        assertFalse(ok(auth.register("合法名".toUpperCase(), "abcd")));
    }

    @Test
    void login_wrongPasswordOrUnknownUser_sameMessage() {
        AuthService auth = new AuthService();
        assertTrue(ok(auth.register("Dispatcher", "abcd1234")));
        // 用户名不区分大小写
        assertTrue(ok(auth.login("dispatcher", "abcd1234")));
        Map<String, Object> wrongPass = auth.login("Dispatcher", "wrong-pass");
        Map<String, Object> noUser = auth.login("NoSuchUser", "abcd1234");
        assertFalse(ok(wrongPass));
        assertFalse(ok(noUser));
        // 两种失败回同一句话，不给账号枚举留口子
        assertEquals(wrongPass.get("message"), noUser.get("message"));
        assertFalse(String.valueOf(noUser.get("message")).contains("不存在"));
    }
}
