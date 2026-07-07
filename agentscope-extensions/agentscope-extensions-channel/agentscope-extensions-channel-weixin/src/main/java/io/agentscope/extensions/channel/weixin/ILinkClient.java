/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.weixin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.weixin.error.WeixinClientError;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信 iLink Bot HTTP 客户端。
 *
 * <ul>
 *   <li>iLink API 基础地址：{@value #DEFAULT_BASE_URL}</li>
 *   <li>HTTP/JSON 协议，无需第三方 SDK</li>
 *   <li>Bearer Token 认证（通过 QR 码登录获取）</li>
 *   <li>长轮询 getupdates（服务端最长持有 35 秒）</li>
 * </ul>
 *
 * <p>认证流程：
 * <ol>
 *   <li>GET /ilink/bot/get_bot_qrcode?bot_type=3 → 获取二维码</li>
 *   <li>轮询 GET /ilink/bot/get_qrcode_status?qrcode=xxx → 等待扫码确认</li>
 *   <li>确认后获得 bot_token + baseurl</li>
 *   <li>后续请求均带 Bearer token</li>
 * </ol>
 *
 * <p>Ported from mateclaw-server {@code vip.mate.channel.weixin.ILinkClient},
 * with lombok {@code @Slf4j}/{@code @Setter} replaced by slf4j-api and explicit setters
 * to keep this module lombok-free.
 */
public class ILinkClient {

    private static final Logger log = LoggerFactory.getLogger(ILinkClient.class);

    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final String CHANNEL_VERSION = "2.0.1";

    /** 长轮询超时（服务端最长 35s，客户端设 45s）。 */
    private static final Duration GETUPDATES_TIMEOUT = Duration.ofSeconds(45);

    /** 普通请求超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** 媒体下载超时。 */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    /** CDN 上传超时。 */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(120);

    /** 微信 CDN 基础地址。 */
    private static final String CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    private String botToken;
    private String baseUrl;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ILinkClient(String botToken, String baseUrl, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.baseUrl =
                (baseUrl != null && !baseUrl.isBlank())
                        ? baseUrl.replaceAll("/+$", "")
                        : DEFAULT_BASE_URL;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl =
                (baseUrl != null && !baseUrl.isBlank())
                        ? baseUrl.replaceAll("/+$", "")
                        : DEFAULT_BASE_URL;
    }

    public String getBotToken() {
        return botToken;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ==================== 请求头构建 ====================

    /**
     * 构建 iLink API 请求头。
     *
     * <ul>
     *   <li>X-WECHAT-UIN: base64(str(random_uint32)) — 每请求一个随机值，防重放</li>
     *   <li>Authorization: Bearer {token}</li>
     *   <li>AuthorizationType: ilink_bot_token</li>
     * </ul>
     */
    private Map<String, String> makeHeaders() {
        long uinVal = new Random().nextLong(0, 0xFFFFFFFFL + 1);
        String uinB64 =
                Base64.getEncoder()
                        .encodeToString(String.valueOf(uinVal).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("AuthorizationType", "ilink_bot_token");
        headers.put("X-WECHAT-UIN", uinB64);
        if (botToken != null && !botToken.isBlank()) {
            headers.put("Authorization", "Bearer " + botToken);
        }
        return headers;
    }

    /** 统一 HTTP 状态校验：401/403 → TokenExpiredException；其它非 200 → RuntimeException。 */
    private static void ensureOk(HttpResponse<?> response, String operation) {
        int status = response.statusCode();
        if (status == 200) return;
        String body = stringifyBody(response.body());
        throw WeixinClientError.fromStatus(status, operation, body).toException();
    }

    private static String stringifyBody(Object body) {
        if (body == null) return "";
        if (body instanceof String s) return s;
        if (body instanceof byte[] bytes) {
            int len = Math.min(bytes.length, 200);
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
        return String.valueOf(body);
    }

    private HttpRequest.Builder applyHeaders(HttpRequest.Builder builder) {
        makeHeaders().forEach(builder::header);
        return builder;
    }

    // ==================== 认证 API ====================

    /** 获取登录二维码（包含 qrcode, qrcode_img_content, url 等字段）。 */
    public Map<String, Object> getBotQrcode() throws Exception {
        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        baseUrl
                                                                + "/ilink/bot/get_bot_qrcode?bot_type=3"))
                                        .GET())
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "getBotQrcode");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 轮询二维码扫码状态（waiting/scanned/confirmed/expired，confirmed 时含 bot_token/baseurl）。 */
    public Map<String, Object> getQrcodeStatus(String qrcode) throws Exception {
        String encoded = URLEncoder.encode(qrcode, StandardCharsets.UTF_8);
        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        baseUrl
                                                                + "/ilink/bot/get_qrcode_status?qrcode="
                                                                + encoded))
                                        .GET())
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "getQrcodeStatus");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 阻塞等待 QR 码扫码确认，最长 maxWaitSeconds 秒。 */
    public QrLoginResult waitForLogin(String qrcode, long pollIntervalMs, int maxWaitSeconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> data = getQrcodeStatus(qrcode);
            String status = (String) data.getOrDefault("status", "");
            if ("confirmed".equals(status)) {
                String token = (String) data.getOrDefault("bot_token", "");
                String newBaseUrl = (String) data.getOrDefault("baseurl", baseUrl);
                return new QrLoginResult(token, newBaseUrl);
            }
            if ("expired".equals(status)) {
                throw new RuntimeException("WeChat QR code expired, please retry login");
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("WeChat QR code not scanned within " + maxWaitSeconds + "s");
    }

    // ==================== 消息 API ====================

    /** 长轮询获取新消息（服务端最长持有 35 秒）。cursor 首次传空字符串。 */
    public Map<String, Object> getUpdates(String cursor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("get_updates_buf", cursor != null ? cursor : "");
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/ilink/bot/getupdates"))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        objectMapper.writeValueAsString(body))))
                        .timeout(GETUPDATES_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "getUpdates");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 发送消息（msg 遵循 iLink sendmessage 协议）。 */
    public Map<String, Object> sendMessage(Map<String, Object> msg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg", msg);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/ilink/bot/sendmessage"))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        objectMapper.writeValueAsString(body))))
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "sendMessage");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 发送纯文本消息。contextToken 来自入站消息，必需。 */
    public void sendText(String toUserId, String text, String contextToken) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", UUID.randomUUID().toString());
        msg.put("message_type", 2); // 机器人
        msg.put("message_state", 2); // 完成
        msg.put("context_token", contextToken);
        msg.put("item_list", List.of(Map.of("type", 1, "text_item", Map.of("text", text))));
        sendMessage(msg);
    }

    // ==================== 媒体下载 ====================

    /**
     * 下载 CDN 媒体文件并可选解密。
     *
     * @param url               直接 HTTP URL（如果有）
     * @param aesKeyParam       AES key（hex / base64，为空则不解密）
     * @param encryptQueryParam CDN 查询参数
     */
    public byte[] downloadMedia(String url, String aesKeyParam, String encryptQueryParam)
            throws Exception {
        String downloadUrl;
        if (encryptQueryParam != null && !encryptQueryParam.isBlank()) {
            String enc = URLEncoder.encode(encryptQueryParam, StandardCharsets.UTF_8);
            downloadUrl = CDN_BASE_URL + "/download?encrypted_query_param=" + enc;
        } else if (url != null && url.startsWith("http")) {
            downloadUrl = url;
        } else {
            throw new IllegalArgumentException("Cannot download media: no valid URL. url=" + url);
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .GET()
                        .timeout(DOWNLOAD_TIMEOUT)
                        .build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        ensureOk(response, "downloadMedia");

        byte[] data = response.body();
        if (aesKeyParam != null && !aesKeyParam.isBlank()) {
            data = WeixinAesUtil.aesEcbDecrypt(data, aesKeyParam);
        }
        return data;
    }

    // ==================== 输入中提示 API ====================

    /** 获取用户配置（含 typing_ticket）。 */
    public Map<String, Object> getConfig(String ilinkUserId, String contextToken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", ilinkUserId);
        body.put("context_token", contextToken);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/ilink/bot/getconfig"))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        objectMapper.writeValueAsString(body))))
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "getConfig");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 发送输入中状态（status: 1=开始输入, 2=停止输入）。 */
    public Map<String, Object> sendTyping(String toUserId, String typingTicket, int status)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", toUserId);
        body.put("typing_ticket", typingTicket);
        body.put("status", status);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/ilink/bot/sendtyping"))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        objectMapper.writeValueAsString(body))))
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "sendTyping");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    // ==================== 媒体上传 API ====================

    /**
     * 获取 CDN 上传 URL。
     *
     * @param filekey    16 字节随机 hex 字符串（唯一文件 ID）
     * @param mediaType  1=image, 2=video, 3=file, 4=voice
     * @param toUserId   收件人 ID
     * @param rawSize    原始文件大小
     * @param rawFileMd5 原始文件 MD5（32 字符 hex）
     * @param fileSize   加密后文件大小
     * @param aesKeyHex  AES key 的 hex 编码（32 字符）
     */
    public Map<String, Object> getUploadUrl(
            String filekey,
            int mediaType,
            String toUserId,
            long rawSize,
            String rawFileMd5,
            long fileSize,
            String aesKeyHex)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filekey", filekey);
        body.put("media_type", mediaType);
        body.put("to_user_id", toUserId);
        body.put("rawsize", rawSize);
        body.put("rawfilemd5", rawFileMd5);
        body.put("filesize", fileSize);
        body.put("aeskey", aesKeyHex);
        body.put("no_need_thumb", true);
        body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

        HttpRequest request =
                applyHeaders(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/ilink/bot/getuploadurl"))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        objectMapper.writeValueAsString(body))))
                        .timeout(DEFAULT_TIMEOUT)
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response, "getUploadUrl");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * 上传媒体文件到微信 CDN。
     *
     * <p>流程：读字节 → 计算 MD5 → 生成 AES-128 key 加密 → getUploadUrl 取上传地址 →
     * POST 加密数据到 CDN → 从响应头提取 encrypt_query_param。
     *
     * @param fileBytes  文件字节
     * @param fileName   文件名
     * @param mediaType  1=image, 2=video, 3=file, 4=voice
     * @param toUserId   收件人 ID
     */
    public UploadResult uploadMedia(
            byte[] fileBytes, String fileName, int mediaType, String toUserId) throws Exception {
        long rawSize = fileBytes.length;
        String rawFileMd5 = md5Hex(fileBytes);

        SecureRandom random = new SecureRandom();
        byte[] aesKeyRawBytes = new byte[16];
        random.nextBytes(aesKeyRawBytes);
        String aesKeyHex = bytesToHex(aesKeyRawBytes);
        String aesKeyB64ForEncrypt = Base64.getEncoder().encodeToString(aesKeyRawBytes);
        // 消息中的 aes_key: base64(hex_string) — iLink 要求的 base64-of-hex 编码
        String aesKeyB64ForMsg =
                Base64.getEncoder().encodeToString(aesKeyHex.getBytes(StandardCharsets.UTF_8));

        byte[] encryptedData = WeixinAesUtil.aesEcbEncrypt(fileBytes, aesKeyB64ForEncrypt);
        long encryptedSize = encryptedData.length;

        byte[] filekeyBytes = new byte[16];
        random.nextBytes(filekeyBytes);
        String filekey = bytesToHex(filekeyBytes);

        Map<String, Object> uploadUrlResp =
                getUploadUrl(
                        filekey,
                        mediaType,
                        toUserId,
                        rawSize,
                        rawFileMd5,
                        encryptedSize,
                        aesKeyHex);

        String uploadUrl;
        String fullUrl = (String) uploadUrlResp.get("upload_full_url");
        if (fullUrl != null && !fullUrl.isBlank()) {
            uploadUrl = fullUrl;
        } else {
            String uploadParam = (String) uploadUrlResp.get("upload_param");
            if (uploadParam == null || uploadParam.isBlank()) {
                throw new RuntimeException(
                        "uploadMedia: no upload_full_url or upload_param in response");
            }
            String encParam = URLEncoder.encode(uploadParam, StandardCharsets.UTF_8);
            uploadUrl =
                    CDN_BASE_URL
                            + "/upload?encrypted_query_param="
                            + encParam
                            + "&filekey="
                            + filekey;
        }

        HttpRequest.Builder cdnBuilder =
                HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedData))
                        .header("Content-Type", "application/octet-stream")
                        .timeout(UPLOAD_TIMEOUT);
        // 仅在 upload_full_url 模式下附加 auth 头
        if (fullUrl != null && !fullUrl.isBlank()) {
            applyHeaders(cdnBuilder);
        }

        HttpResponse<byte[]> cdnResponse =
                httpClient.send(cdnBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        ensureOk(cdnResponse, "CDN upload");

        String encryptQueryParam =
                cdnResponse
                        .headers()
                        .firstValue("x-encrypted-param")
                        .or(() -> cdnResponse.headers().firstValue("X-Encrypted-Param"))
                        .orElse("");

        if (encryptQueryParam.isBlank()) {
            log.error(
                    "[weixin] CDN upload: missing encrypt_query_param in response headers. Headers:"
                            + " {}",
                    cdnResponse.headers().map());
            throw new RuntimeException(
                    "uploadMedia: empty encrypt_query_param from CDN (file would appear blank)");
        }

        log.info(
                "[weixin] Media uploaded: type={}, size={}KB, encryptedSize={}KB",
                mediaType,
                rawSize / 1024,
                encryptedSize / 1024);

        return new UploadResult(encryptQueryParam, aesKeyB64ForMsg, encryptedSize);
    }

    /** 发送图片消息。 */
    public void sendImage(String toUserId, byte[] imageBytes, String contextToken)
            throws Exception {
        UploadResult result = uploadMedia(imageBytes, "image.jpg", 1, toUserId);

        Map<String, Object> imageItem = new LinkedHashMap<>();
        imageItem.put("type", 2);
        imageItem.put(
                "image_item",
                Map.of(
                        "media",
                        Map.of(
                                "encrypt_query_param", result.encryptQueryParam(),
                                "aes_key", result.aesKeyB64(),
                                "mid_size", result.fileSize())));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", UUID.randomUUID().toString());
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("context_token", contextToken);
        msg.put("item_list", List.of(imageItem));

        sendMessage(msg);
    }

    /** 发送文件消息。 */
    public void sendFile(String toUserId, byte[] fileBytes, String fileName, String contextToken)
            throws Exception {
        UploadResult result = uploadMedia(fileBytes, fileName, 3, toUserId);

        Map<String, Object> fileItem = new LinkedHashMap<>();
        fileItem.put("type", 4);
        fileItem.put(
                "file_item",
                Map.of(
                        "file_name",
                        fileName,
                        "len",
                        (long) fileBytes.length,
                        "media",
                        Map.of(
                                "encrypt_query_param", result.encryptQueryParam(),
                                "aes_key", result.aesKeyB64())));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", UUID.randomUUID().toString());
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("context_token", contextToken);
        msg.put("item_list", List.of(fileItem));

        sendMessage(msg);
    }

    /** 发送视频消息。 */
    public void sendVideo(String toUserId, byte[] videoBytes, String contextToken)
            throws Exception {
        UploadResult result = uploadMedia(videoBytes, "video.mp4", 2, toUserId);

        Map<String, Object> videoItem = new LinkedHashMap<>();
        videoItem.put("type", 5);
        videoItem.put(
                "video_item",
                Map.of(
                        "media",
                        Map.of(
                                "encrypt_query_param", result.encryptQueryParam(),
                                "aes_key", result.aesKeyB64())));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", UUID.randomUUID().toString());
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("context_token", contextToken);
        msg.put("item_list", List.of(videoItem));

        sendMessage(msg);
    }

    /**
     * 发送语音消息（以文件形式发送 MP3，用户可点击播放）。
     *
     * <p>iLink Bot API 的原生语音接口（mediaType=4, item type=3）尚未完全验证，
     * 故采用降级策略以文件形式发送，可靠性最高。
     */
    public void sendVoice(String toUserId, byte[] voiceBytes, String fileName, String contextToken)
            throws Exception {
        sendFile(toUserId, voiceBytes, fileName, contextToken);
    }

    // ==================== 工具方法 ====================

    private static String md5Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input);
            return bytesToHex(hash);
        } catch (Exception e) {
            return "0";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== 内部模型 ====================

    /** 媒体上传结果：encryptQueryParam 用于 sendmessage 的 media；aesKeyB64 为 base64(hex) 编码。 */
    public record UploadResult(String encryptQueryParam, String aesKeyB64, long fileSize) {}

    /** QR 码登录结果。 */
    public record QrLoginResult(String token, String baseUrl) {}
}
