package com.example.aseanweatherlogistics.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Service;

/**
 * 演示级账号服务：门户首页的登录/注册壳。
 * <p>
 * 刻意做简单（用户要求「先简单做一下」）：
 * <ul>
 *   <li><b>纯内存存储、不引入 Spring Security、不建库表</b>——服务重启即清空，
 *       只承担「门户有身份壳」的演示职责，不是生产鉴权。</li>
 *   <li><b>口令不落明文</b>：PBKDF2WithHmacSHA256 + 每人随机盐，迭代 10000 次，
 *       校验走常量时间比较——即使是演示系统，也不写「明文密码进内存 Map」这种坏示范。</li>
 *   <li>会话由控制器存进 HttpSession（容器管 Cookie），本服务只负责账密校验。</li>
 * </ul>
 */
@Service
public class AuthService {

    /** 账号记录：只显示名 + 盐与散列（Base64）。 */
    private record User(String name, String saltB64, String hashB64) {
    }

    private static final int ITERATIONS = 10_000;
    private static final int KEY_BITS = 256;
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 注册：用户名去空格、最短 2 字；重名直接拒绝。成功即返回可展示的身份。 */
    public synchronized Map<String, Object> register(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 2) {
            return fail("用户名至少 2 个字符");
        }
        if (password == null || password.length() < 4) {
            return fail("口令至少 4 位");
        }
        String key = name.toLowerCase();
        if (users.containsKey(key)) {
            return fail("该用户名已注册");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String hash = hash(password, salt);
        users.put(key, new User(name, Base64.getEncoder().encodeToString(salt), hash));
        return ok(name);
    }

    /** 登录：用户名不区分大小写；失败不区分「无此人/口令错」，避免账号枚举。 */
    public Map<String, Object> login(String username, String password) {
        String name = username == null ? "" : username.trim();
        User u = name.isEmpty() ? null : users.get(name.toLowerCase());
        if (u == null || password == null) {
            return fail("用户名或口令不正确");
        }
        byte[] salt = Base64.getDecoder().decode(u.saltB64());
        String candidate = hash(password, salt);
        // 常量时间比较：演示系统也不留时序侧信道这种坏示范
        if (!MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                u.hashB64().getBytes(StandardCharsets.UTF_8))) {
            return fail("用户名或口令不正确");
        }
        return ok(u.name());
    }

    /** 已注册的账号数（仅用于界面提示「本机演示库当前有 N 个账号」，重启归零）。 */
    public int count() {
        return users.size();
    }

    private static Map<String, Object> ok(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", message);
        return m;
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
