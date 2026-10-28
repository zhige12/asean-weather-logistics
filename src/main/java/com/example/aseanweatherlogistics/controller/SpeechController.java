package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.SpeechService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音输入接口：浏览器/WebView 用 MediaRecorder 录音上传，服务端转阿里云一句话识别。
 *
 * <p>存在的原因见 {@link SpeechService} 类注释——Web Speech API 的识别端在 Google，
 * 国内不可达，识别必须挪到服务端。前端只面对这一对同源接口，密钥不出后端。
 *
 * <p>降级纪律：未配置凭证 / 缺 ffmpeg / 上游失败都回 {@code ok:false + message}
 * 的可读 JSON（HTTP 200，业务失败与传输失败分开），前端原样上屏，不抛 500 堆栈。
 */
@RestController
@RequestMapping("/api/ai/speech")
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);

    /** 录音上传上限：16k opus 说满 12 秒也才几十 KB，2MB 已极宽裕，防被当免费存储 */
    private static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024;

    private final SpeechService speechService;

    public SpeechController(SpeechService speechService) {
        this.speechService = speechService;
    }

    /** 前端启动时探测：enabled=false 时语音按钮如实置灰，而不是点了才报错。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean enabled = speechService.enabled();
        boolean ffmpeg = enabled && speechService.ffmpegAvailable();
        String message = null;
        if (!enabled) {
            message = "云端语音识别未启用：请在服务器环境变量配置 ALIYUN_NLS_APPKEY / ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET";
        } else if (!ffmpeg) {
            message = "服务器缺少 ffmpeg，无法转码浏览器录音（apt-get install ffmpeg 后重启服务）";
        }
        return Map.of(
                "ok", true,
                "enabled", enabled && ffmpeg,
                "engine", "aliyun-nls",
                "message", message == null ? "" : message);
    }

    /**
     * 一句话识别：multipart 字段 audio（webm/opus、mp4/aac 等浏览器产物）+ 可选 lang。
     *
     * @return {ok:true, text} 或 {ok:false, message}
     */
    @PostMapping
    public Map<String, Object> recognize(@RequestParam("audio") MultipartFile audio,
                                         @RequestParam(value = "lang", defaultValue = "zh-CN") String lang) {
        if (!speechService.enabled()) {
            return Map.of("ok", false,
                    "message", "云端语音识别未启用（缺少阿里云语音凭证），请改用文字输入");
        }
        // 一句话识别目前只配了中文普通话模型；越南语等直接如实说，不硬凑结果
        if (lang != null && !lang.isBlank() && !lang.startsWith("zh") && !lang.startsWith("en")) {
            return Map.of("ok", false, "message", "云端识别暂不支持 " + lang + "，请说中文或改用文字输入");
        }
        if (audio == null || audio.isEmpty()) {
            return Map.of("ok", false, "message", "没收到音频");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            return Map.of("ok", false, "message", "录音过大（>2MB），请缩短说话时长");
        }
        Path tmp = null;
        try {
            // 落临时文件喂 ffmpeg：容器格式杂（webm/mp4/ogg），按后缀给它的 demuxer 提示即可，
            // ffmpeg 实际按内容嗅探
            long t0 = System.currentTimeMillis();
            tmp = Files.createTempFile("speech-in-", ".bin");
            audio.transferTo(tmp.toFile());
            byte[] wav = speechService.transcodeToWav16k(tmp);
            long tTranscode = System.currentTimeMillis() - t0;
            String text = speechService.recognizeWav(wav);
            long tRecognize = System.currentTimeMillis() - t0 - tTranscode;
            // 耗时分解：上传字节 / 转码 / 阿里云往返。手机反馈“识别慢”时，靠这行
            // 区分是上传链路慢还是识别慢（服务端两段正常合计 <1s，超出即网络/上游问题）
            log.info("语音识别完成 上传={}B 转码={}ms 识别={}ms 总={}ms ok={}",
                    audio.getSize(), tTranscode, tRecognize, tTranscode + tRecognize, text != null);
            if (text == null) {
                return Map.of("ok", false, "message", "云端识别失败，请看服务端日志（凭证/token/网络）");
            }
            if (text.isBlank()) {
                return Map.of("ok", false, "message", "没听清，请再说一遍");
            }
            return Map.of("ok", true, "text", text, "engine", "aliyun-nls");
        } catch (Exception e) {
            log.warn("语音识别处理失败：{}", e.toString());
            return Map.of("ok", false, "message", "识别处理失败：" + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {
                    // 临时文件删除失败不影响响应
                }
            }
        }
    }
}
