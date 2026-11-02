package com.example.aseanweatherlogistics.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图形验证码服务的确定性行为固化：签发（PNG data URL + 答案入会话）、
 * 校验大小写不敏感、单次消费（用一次即作废）、错码拒绝、null 会话拒绝。
 * 答案从会话里读回，无需对图片做 OCR。
 */
class CaptchaServiceTest {

    private static String[] stored(MockHttpSession s) {
        return (String[]) s.getAttribute("portalCaptcha");
    }

    @Test
    void issue_returnsPngDataUrl_andStoresAnswer() {
        CaptchaService cap = new CaptchaService();
        MockHttpSession s = new MockHttpSession();
        String img = cap.issue(s);
        assertTrue(img.startsWith("data:image/png;base64,"), "应回 PNG data URL");
        String[] pair = stored(s);
        assertNotNull(pair, "答案应写入会话");
        assertEquals(4, pair[0].length(), "4 位字符");
    }

    @Test
    void verify_caseInsensitive_andSingleUse() {
        CaptchaService cap = new CaptchaService();
        MockHttpSession s = new MockHttpSession();
        cap.issue(s);
        String code = stored(s)[0];
        // 大小写不敏感
        assertTrue(cap.verifyAndConsume(s, code.toLowerCase()), "小写应通过");
        // 单次消费：用完即作废，同一码再用应失败
        assertFalse(cap.verifyAndConsume(s, code), "重复使用应被拒（已消费）");
    }

    @Test
    void verify_rejectsWrongBlankAndNullSession() {
        CaptchaService cap = new CaptchaService();
        MockHttpSession s = new MockHttpSession();
        cap.issue(s);
        assertFalse(cap.verifyAndConsume(s, "0000"), "错码应被拒");
        // 错码这次也已消费掉：会话里已无答案，再验任何值都应失败
        assertNull(stored(s), "消费后会话不应再留答案");
        assertFalse(cap.verifyAndConsume(s, "ABCD"), "消费后无答案应被拒");
        MockHttpSession s2 = new MockHttpSession();
        cap.issue(s2);
        assertFalse(cap.verifyAndConsume(s2, "  "), "空白应被拒");
        assertFalse(cap.verifyAndConsume(null, "ABCD"), "null 会话应被拒");
    }
}
