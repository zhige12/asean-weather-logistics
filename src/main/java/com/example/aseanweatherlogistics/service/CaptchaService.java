package com.example.aseanweatherlogistics.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpSession;

/**
 * 演示级图形验证码：本地用 Java2D 画 4 位字符（去掉易混的 0/O/1/I），答案与签发时间存 HttpSession。
 * <p>
 * 与账号体系同样的「演示级」取舍：不接任何外部短信/邮箱服务（离线环境也接不了），
 * 纯服务端画图 + 会话记忆，够挡手滑、给「登录要验证码」这个交互一个真实闭环。
 * 校验走「单次消费」——无论对错都立即作废会话里的答案，防止同一张码被重放，
 * 失败后前端必须重新取一张。
 */
@Service
public class CaptchaService {

    /** 会话里存验证码答案的键。 */
    private static final String SESSION_KEY = "portalCaptcha";
    /** 去掉了易混淆的 0/O/1/I。 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LEN = 4;
    /** 有效期：签发后 5 分钟内未使用即作废。 */
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int W = 120;
    private static final int H = 40;

    private final SecureRandom random = new SecureRandom();

    /** 生成新验证码：画 PNG → Base64 data URL 返回，答案（原文）+ 时间戳写入会话。 */
    public String issue(HttpSession session) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < LEN; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        String text = code.toString();

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(246, 249, 255));
        g.fillRect(0, 0, W, H);
        // 干扰线
        for (int i = 0; i < 6; i++) {
            g.setColor(randomColor(150, 225));
            g.drawLine(random.nextInt(W), random.nextInt(H), random.nextInt(W), random.nextInt(H));
        }
        // 字符：逐字轻微旋转 + 随机深色
        for (int i = 0; i < LEN; i++) {
            g.setColor(randomColor(20, 130));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26 + random.nextInt(4)));
            int x = 14 + i * 26;
            int y = 30 + random.nextInt(5);
            double angle = (random.nextDouble() - 0.5) * 0.5;
            g.rotate(angle, x, y);
            g.drawString(String.valueOf(text.charAt(i)), x, y);
            g.rotate(-angle, x, y);
        }
        // 噪点
        for (int i = 0; i < 120; i++) {
            g.setColor(randomColor(90, 230));
            g.fillRect(random.nextInt(W), random.nextInt(H), 1, 1);
        }
        g.dispose();

        session.setAttribute(SESSION_KEY, new String[] { text, String.valueOf(System.currentTimeMillis()) });
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }

    /**
     * 校验并消费：无论成败都先清除会话里的验证码（单次使用，防重放）。
     * 大小写不敏感、超过 TTL 视为过期。session 为 null（没取过码）直接判失败。
     */
    public boolean verifyAndConsume(HttpSession session, String input) {
        if (session == null) {
            return false;
        }
        Object attr = session.getAttribute(SESSION_KEY);
        session.removeAttribute(SESSION_KEY);
        if (!(attr instanceof String[] pair) || input == null || input.isBlank()) {
            return false;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(pair[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (System.currentTimeMillis() - issuedAt > TTL_MS) {
            return false;
        }
        return pair[0].equalsIgnoreCase(input.trim());
    }

    private Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + random.nextInt(range), min + random.nextInt(range), min + random.nextInt(range));
    }
}
