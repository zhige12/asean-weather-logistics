package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.controller.SpeechController;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云端语音识别的确定性用例。
 * <p>
 * 这块的纪律与 AI 计量一致：<b>没配凭证就如实说不可用，绝不假装识别成功</b>。
 * 锁三件事：
 * ① 三件套凭证任一为空/占位符 → enabled()=false，status 接口回 enabled=false 带可读原因；
 * ② 未启用时上传音频 → ok:false + 引导文字输入，不抛异常、不吞成空串；
 * ③ 非中英文语种（如司机端越南语）→ 明确回不支持，而不是硬塞给中文模型出一个错字结果。
 * 真实联网识别不放进单测（依赖外部凭证与网络，属不稳定断言）。
 */
class SpeechServiceTest {

    private SpeechService fresh() {
        return new SpeechService();
    }

    private void configure(SpeechService s, String appKey, String id, String secret) {
        ReflectionTestUtils.setField(s, "appKey", appKey);
        ReflectionTestUtils.setField(s, "accessKeyId", id);
        ReflectionTestUtils.setField(s, "accessKeySecret", secret);
    }

    @Test
    void disabledWhenAnyCredentialMissing() {
        assertFalse(fresh().enabled(), "全空视为未启用");

        SpeechService partial = fresh();
        configure(partial, "ak", "id", "");
        assertFalse(partial.enabled(), "缺 secret 也算未启用");

        SpeechService placeholder = fresh();
        configure(placeholder, "<your-appkey>", "id", "secret");
        assertFalse(placeholder.enabled(), "仍是占位符不得当作已配置");

        SpeechService full = fresh();
        configure(full, "ak", "id", "secret");
        assertTrue(full.enabled(), "三件套齐全才算启用");
    }

    @Test
    void statusReportsDisabledWithReason() {
        SpeechController controller = new SpeechController(fresh());
        Map<String, Object> status = controller.status();

        assertEquals(Boolean.TRUE, status.get("ok"));
        assertEquals(Boolean.FALSE, status.get("enabled"), "未配凭证时 enabled 必须是 false");
        assertTrue(String.valueOf(status.get("message")).contains("ALIYUN_NLS_APPKEY"),
                "禁用原因要点名缺哪几项，方便部署方照配");
    }

    @Test
    void uploadRejectedWhenDisabled() {
        SpeechController controller = new SpeechController(fresh());
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "speech.webm", "audio/webm", new byte[]{1, 2, 3});
        Map<String, Object> resp = controller.recognize(audio, "zh-CN");

        assertEquals(Boolean.FALSE, resp.get("ok"), "未启用时不得回一个空的识别结果冒充成功");
        assertTrue(String.valueOf(resp.get("message")).contains("文字输入"));
    }

    @Test
    void unsupportedLanguageRejected() {
        SpeechService service = fresh();
        configure(service, "ak", "id", "secret");
        SpeechController controller = new SpeechController(service);
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "speech.webm", "audio/webm", new byte[]{1, 2, 3});

        Map<String, Object> vi = controller.recognize(audio, "vi-VN");
        assertEquals(Boolean.FALSE, vi.get("ok"), "越南语没有对应模型，不能硬转中文识别");
        assertTrue(String.valueOf(vi.get("message")).contains("不支持"));
    }

    @Test
    void oversizedAudioRejected() {
        SpeechService service = fresh();
        configure(service, "ak", "id", "secret");
        SpeechController controller = new SpeechController(service);
        byte[] big = new byte[(int) (2L * 1024 * 1024 + 10)];
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "speech.webm", "audio/webm", big);

        Map<String, Object> resp = controller.recognize(audio, "zh-CN");
        assertEquals(Boolean.FALSE, resp.get("ok"));
        assertTrue(String.valueOf(resp.get("message")).contains("录音过大"));
    }
}
