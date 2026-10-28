package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.util.SecretCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 云端语音识别（STT）：浏览器录的音走这里转成文字。
 *
 * <p>为什么不用浏览器 Web Speech：Chrome 的实现把音频送到 Google 服务器识别，
 * 国内网络（演示现场 WiFi 与手机流量都一样）到不了它，权限给到位也只会报
 * network 错。本服务改走<b>阿里云一句话识别</b>（NLS 网关，境内可达），前端
 * 用 MediaRecorder 录音上传，识别端点由服务器代理——密钥只留在后端环境变量，
 * 与天气 Token 同一套管理纪律（不入库、支持 ENC() 密文）。
 *
 * <p>调用链两步（均为裸 HTTP，不引第三方 SDK）：
 * <ol>
 *   <li>CreateToken：AK/SK 按阿里云 RPC 签名（HMAC-SHA1）换临时 token，
 *       有效期约 24h，进程内缓存、提前 5 分钟续期；</li>
 *   <li>一句话识别：POST 音频二进制到 NLS 网关（X-NLS-Token 头），
 *       响应 header.status==20000000 时 payload.result 即识别文本。</li>
 * </ol>
 *
 * <p>音频格式：浏览器 MediaRecorder 产物是 webm/opus（安卓/桌面）或 mp4/aac（iOS），
 * NLS 只吃 pcm/wav/opus(ogg)/aac 等定死容器，统一用 ffmpeg 转 16k 单声道 wav 再送，
 * 免得在前端按浏览器分叉。服务器缺 ffmpeg 时如实报错，不静默吞。
 */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);

    @Value("${speech.aliyun.appkey:}")
    private String appKey;

    @Value("${speech.aliyun.access-key-id:}")
    private String accessKeyId;

    @Value("${speech.aliyun.access-key-secret:}")
    private String accessKeySecret;

    /** CreateToken 端点（阿里云 RPC 风格，nls-meta 上海）。 */
    @Value("${speech.aliyun.token-url:https://nls-meta.cn-shanghai.aliyuncs.com/}")
    private String tokenUrl;

    /** 一句话识别网关（境内可达）。 */
    @Value("${speech.aliyun.gateway-url:https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr}")
    private String gatewayUrl;

    /** ffmpeg 可执行文件：服务器 PATH 里有就叫 ffmpeg，Windows 开发机可指绝对路径。 */
    @Value("${speech.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ---- token 进程内缓存（CreateToken 有频控，不能每次识别都换）----
    private volatile String cachedToken;
    private volatile long tokenExpireEpochSec;

    /** 凭证三件套齐了才算启用；任一为空/占位符则整个功能如实关闭。 */
    public boolean enabled() {
        return configured(appKey) && configured(accessKeyId) && configured(accessKeySecret);
    }

    private static boolean configured(String v) {
        return v != null && !v.isBlank() && !v.contains("<");
    }

    /**
     * 识别一段 16k 单声道 wav。
     *
     * @return 识别文本；失败返回 null（原因进日志，调用方给前端可读文案）
     */
    public String recognizeWav(byte[] wavBytes) {
        if (!enabled()) {
            return null;
        }
        String token = token();
        if (token == null) {
            return null;
        }
        try {
            String url = gatewayUrl + "?appkey=" + enc(appKey)
                    + "&format=wav&sample_rate=16000"
                    + "&enable_punctuation_prediction=false"
                    + "&enable_inverse_text_normalization=true";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/octet-stream")
                    .header("X-NLS-Token", token)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wavBytes))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            // 实测 GET 参数式调网关回的是扁平结构 {"status":20000000,"result":"...","message":"SUCCESS"}，
            // 官方文档里的 header/payload 嵌套格式是 WebSocket/SDK 路径的，只按嵌套解会把成功的识别判成失败
            long status = root.has("status")
                    ? root.path("status").asLong() : root.path("header").path("status").asLong();
            if (resp.statusCode() == 200 && status == 20000000L) {
                String text = root.path("result").asText("");
                if (text.isBlank()) {
                    text = root.path("payload").path("result").asText("");
                }
                return text.trim();
            }
            // 403/40000010 之类多半是 token 过期或凭证无效：清缓存让下次重新换
            if (status == 40000004L || status == 40000010L) {
                cachedToken = null;
            }
            log.warn("一句话识别失败 http={} status={} message={} body={}", resp.statusCode(), status,
                    root.path("message").asText(root.path("header").path("status_message").asText()),
                    resp.body().length() > 400 ? resp.body().substring(0, 400) : resp.body());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("一句话识别调用异常：{}", e.toString());
            return null;
        }
    }

    /**
     * ffmpeg 转码：任意浏览器容器（webm/opus、mp4/aac…）→ 16k 单声道 16bit wav 字节。
     *
     * @throws IOException ffmpeg 缺失/失败/超时，消息可直接给用户看
     */
    public byte[] transcodeToWav16k(Path inputFile) throws IOException {
        Path outFile = Files.createTempFile("speech-", ".wav");
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", inputFile.toString(),
                    "-ac", "1", "-ar", "16000", "-acodec", "pcm_s16le", "-f", "wav", outFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg 转码超时");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg 转码失败：" + out.trim());
            }
            return Files.readAllBytes(outFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg 转码被中断");
        } finally {
            Files.deleteIfExists(outFile);
        }
    }

    /** ffmpeg 是否可用（部署自检用；缺失时接口返回可读提示而不是 500 堆栈）。 */
    public boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version").redirectErrorStream(true).start();
            p.getInputStream().readNBytes(64);
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- token

    /** 取有效 token（缓存未过期直接回，过期换新的）；失败返回 null。 */
    private String token() {
        String t = cachedToken;
        if (t != null && Instant.now().getEpochSecond() < tokenExpireEpochSec - 300) {
            return t;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().getEpochSecond() < tokenExpireEpochSec - 300) {
                return cachedToken;
            }
            try {
                JsonNode tok = createToken();
                if (tok == null) {
                    return null;
                }
                cachedToken = tok.path("Id").asText();
                // 新版 CreateToken(2019-02-28) 字段叫 ExpireTime，老版 SDK 文档里叫 TokenExpireTime，两个都认
                tokenExpireEpochSec = tok.has("ExpireTime")
                        ? tok.path("ExpireTime").asLong() : tok.path("TokenExpireTime").asLong();
                log.info("阿里云语音 token 已刷新，有效期至 {}", Instant.ofEpochSecond(tokenExpireEpochSec));
                return cachedToken;
            } catch (Exception e) {
                log.warn("获取阿里云语音 token 失败：{}", e.toString());
                return null;
            }
        }
    }

    /** 阿里云 RPC 签名（HMAC-SHA1）调 CreateToken。 */
    private JsonNode createToken() throws Exception {
        List<Map.Entry<String, String>> params = new ArrayList<>(List.of(
                Map.entry("Action", "CreateToken"),
                Map.entry("Version", "2019-02-28"),
                Map.entry("Format", "JSON"),
                Map.entry("RegionId", "cn-shanghai"),
                Map.entry("AccessKeyId", SecretCodec.resolve(accessKeyId)),
                Map.entry("SignatureMethod", "HMAC-SHA1"),
                Map.entry("SignatureVersion", "1.0"),
                Map.entry("SignatureNonce", UUID.randomUUID().toString()),
                Map.entry("Timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .withZone(ZoneOffset.UTC).format(Instant.now()))));
        params.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder canon = new StringBuilder();
        for (Map.Entry<String, String> e : params) {
            if (canon.length() > 0) {
                canon.append('&');
            }
            canon.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        String stringToSign = "GET&%2F&" + enc(canon.toString());
        String signature = hmacSha1Base64(stringToSign,
                SecretCodec.resolve(accessKeySecret) + "&");
        String url = tokenUrl + "?" + canon + "&Signature=" + enc(signature);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        // 响应结构有两个版本：老版 {"Data":{"Token":{...}}}，新版直接 {"Token":{"Id","ExpireTime"}}
        // （实测 nls-meta 2019-02-28 回的是新版，只按 Data 解析会把拿到的 token 判成失败）
        JsonNode token = root.path("Data").path("Token");
        if (token.isMissingNode() || token.size() == 0) {
            token = root.path("Token");
        }
        if (resp.statusCode() != 200 || token.isMissingNode() || token.size() == 0) {
            log.warn("CreateToken 失败 http={} body={}", resp.statusCode(),
                    resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
            return null;
        }
        return token;
    }

    private static String hmacSha1Base64(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return java.util.Base64.getEncoder().encodeToString(
                mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** 阿里云 RPC 的 percentEncode：URLEncoder 基础上再按 RFC3986 修正三个字符。 */
    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
