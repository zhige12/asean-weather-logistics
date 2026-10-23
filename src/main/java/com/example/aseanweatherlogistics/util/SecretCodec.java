package com.example.aseanweatherlogistics.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭证的文件层加密：外发演示包的 .env.local 里写 ENC(base64(iv|密文|tag))，
 * 读取处经本类还原为明文再使用；不带 ENC() 前缀的明文值原样直通（兼容开发机）。
 *
 * <p>算法：AES-256-GCM，密钥为 SHA-256(代码内置口令)。口令只存在于 jar 代码中，
 * 不写入任何外发配置文件。</p>
 *
 * <p>边界（如实声明）：本类只消除"交付物文件里的明文密钥"——自动化扫描与人为翻包
 * 都看不到凭证原文；但演示必须在评委机器真实调用官方接口，能拆包逆向 jar 的
 * 专业攻击者仍可还原，这一物理边界无法用任何纯分发端手段突破。轮换承诺见包内 README。</p>
 */
public final class SecretCodec {

    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";

    /** 内置口令：仅代码持有。改动它会让所有已加密的 ENC() 值失效，需同步重生成。 */
    private static final String PASSPHRASE = "asean-demo-offline/v1|weather-logistics";

    private static final byte[] AES_KEY = sha256(PASSPHRASE.getBytes(StandardCharsets.UTF_8));

    private SecretCodec() {
    }

    /**
     * ENC(...) → 解密返回明文；普通明文（含 null/空串）原样返回（null 归一为空串）。
     * 解密失败（口令不符/密文损坏）返回空串并打一行告警：调用方均有"未配置凭证→
     * 走降级链"的既有分支，空串即触发降级，绝不抛出中断服务。
     */
    public static String resolve(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() <= PREFIX.length() + SUFFIX.length()
                || !s.startsWith(PREFIX) || !s.endsWith(SUFFIX)) {
            return raw;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(s.substring(PREFIX.length(), s.length() - SUFFIX.length()));
            if (blob.length < 12 + 16 + 1) {
                System.err.println("[secret] ENC 密文长度异常，该凭证按未配置处理（自动降级）");
                return "";
            }
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, 12);
            byte[] ctWithTag = new byte[blob.length - 12];
            System.arraycopy(blob, 12, ctWithTag, 0, ctWithTag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[secret] ENC 解密失败（口令不符或密文损坏），该凭证按未配置处理（自动降级）："
                    + e.getClass().getSimpleName());
            return "";
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
