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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses an iLink Bot inbound {@code msgs} element into an {@link InboundMessage}.
 *
 * <p>Only {@code message_type == 1} (user→bot) payloads are dispatched; other types
 * (bot→user echoes, system notices) are dropped.
 *
 * <p>{@code item_list[]} types mapped:
 * <ul>
 *   <li>1 → {@link TextBlock}</li>
 *   <li>2 → image: CDN download + AES decrypt → {@link ImageBlock} with {@code file://} URL</li>
 *   <li>3 → voice: prefer ASR text ({@code voice_item.text_item.text} / {@code voice_item.text} /
 *       {@code voice_item.content}); if absent, download → {@link AudioBlock}</li>
 *   <li>4 → file: download → {@link DataBlock} with file name</li>
 *   <li>5 → video: download → {@link VideoBlock}</li>
 *   <li>other → {@link TextBlock} placeholder</li>
 * </ul>
 *
 * <p>Peer mapping:
 * <ul>
 *   <li>DM (no {@code group_id}): {@link PeerKind#DIRECT}, peer id = {@code from_user_id},
 *       senderId = {@code from_user_id}</li>
 *   <li>Group ({@code group_id} present): {@link PeerKind#GROUP}, peer id = {@code group_id},
 *       senderId = {@code from_user_id}</li>
 * </ul>
 */
public final class WeixinInboundMapper {

    private static final Logger log = LoggerFactory.getLogger(WeixinInboundMapper.class);

    private final String channelId;
    private final ILinkClient client;
    private final WeixinContextTokenStore tokenStore;
    private final WeixinChannelProperties props;

    public WeixinInboundMapper(
            String channelId,
            ILinkClient client,
            WeixinContextTokenStore tokenStore,
            WeixinChannelProperties props) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.client = Objects.requireNonNull(client, "client");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.props = Objects.requireNonNull(props, "props");
    }

    /** Builds an {@link InboundMessage} from a single iLink {@code msgs} element. */
    @SuppressWarnings("unchecked")
    public Optional<InboundMessage> map(Map<String, Object> msg) {
        if (msg == null) {
            return Optional.empty();
        }
        String fromUserId = str(msg, "from_user_id");
        String contextToken = str(msg, "context_token");
        String groupId = str(msg, "group_id");
        int msgType = asInt(msg.get("message_type"), 0);
        if (msgType != 1) {
            return Optional.empty();
        }
        if (fromUserId == null || fromUserId.isBlank()) {
            return Optional.empty();
        }

        // Cache context_token for outbound replies / proactive pushes.
        tokenStore.put(groupId != null && !groupId.isBlank() ? groupId : fromUserId, contextToken);

        List<Map<String, Object>> itemList =
                (List<Map<String, Object>>) msg.getOrDefault("item_list", List.of());
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> item : itemList) {
            int itemType = asInt(item.get("type"), 0);
            switch (itemType) {
                case 1 -> mapText(item, blocks);
                case 2 -> mapImage(item, blocks);
                case 3 -> mapVoice(item, blocks);
                case 4 -> mapFile(item, blocks);
                case 5 -> mapVideo(item, blocks);
                default ->
                        blocks.add(
                                TextBlock.builder().text("[不支持的消息类型: " + itemType + "]").build());
            }
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        Peer peer;
        String senderId;
        if (groupId != null && !groupId.isBlank()) {
            peer = Peer.group(groupId);
            senderId = fromUserId;
        } else {
            peer = Peer.direct(fromUserId);
            senderId = fromUserId;
        }

        Msg msgObj = Msg.builder().role(MsgRole.USER).name(senderId).content(blocks).build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msgObj))
                        .accountId(channelId)
                        .senderId(senderId)
                        .build());
    }

    /**
     * Extracts a stable idempotency key from a payload. Prefers explicit ids
     * ({@code msg_id}/{@code msgid}/{@code msgId}/{@code message_id}); falls back to
     * {@code context_token} (iLink context tokens are unique per user interaction) or a
     * hash of {@code from_user_id + timestamp + item_list} when none are present.
     */
    public static String extractMsgId(Map<String, Object> msg) {
        if (msg == null) return null;
        for (String key : List.of("msg_id", "msgid", "msgId", "message_id")) {
            Object v = msg.get(key);
            if (v != null) {
                String s = v.toString();
                if (!s.isBlank()) return s;
            }
        }
        String contextToken = str(msg, "context_token");
        if (contextToken != null && !contextToken.isBlank()) {
            return contextToken;
        }
        // Fallback hash for payloads without any explicit id.
        String fromUserId = str(msg, "from_user_id");
        Object ts = msg.get("timestamp");
        Object itemList = msg.get("item_list");
        int hash = Objects.hash(fromUserId, ts, itemList);
        return "gen_" + Integer.toHexString(hash);
    }

    // ==================== item mappers ====================

    private void mapText(Map<String, Object> item, List<ContentBlock> out) {
        Map<String, Object> textItem = asMap(item.get("text_item"));
        String text = str(textItem, "text");
        if (text == null) return;
        text = text.strip();
        if (text.isEmpty()) return;
        out.add(TextBlock.builder().text(text).build());
    }

    private void mapImage(Map<String, Object> item, List<ContentBlock> out) {
        Map<String, Object> imageItem = asMap(item.get("image_item"));
        byte[] bytes = downloadItem(imageItem, "image");
        if (bytes == null) {
            out.add(TextBlock.builder().text("[图片: 下载失败]").build());
            return;
        }
        String mime = sniffImageMime(bytes);
        Path saved = persistMedia(bytes, mime, "img");
        out.add(ImageBlock.builder().source(new URLSource(saved.toUri().toString(), mime)).build());
    }

    private void mapVoice(Map<String, Object> item, List<ContentBlock> out) {
        Map<String, Object> voiceItem = asMap(item.get("voice_item"));
        String asrText = "";
        // Path 1: voice_item.text_item.text (nested)
        Map<String, Object> nestedText = asMap(voiceItem.get("text_item"));
        String t1 = str(nestedText, "text");
        if (t1 != null) asrText = t1.strip();
        // Path 2: voice_item.text (direct)
        if (asrText.isEmpty()) {
            String t2 = str(voiceItem, "text");
            if (t2 != null) asrText = t2.strip();
        }
        // Path 3: voice_item.content (WeCom-style field)
        if (asrText.isEmpty()) {
            String t3 = str(voiceItem, "content");
            if (t3 != null) asrText = t3.strip();
        }
        if (!asrText.isEmpty()) {
            out.add(TextBlock.builder().text(asrText).build());
            return;
        }
        // No ASR: try to download audio and pass to agent as audio block.
        byte[] bytes = downloadItem(voiceItem, "voice");
        if (bytes == null) {
            out.add(TextBlock.builder().text("[语音消息]").build());
            return;
        }
        Path saved = persistMedia(bytes, "audio/mpeg", "voice");
        out.add(
                AudioBlock.builder()
                        .source(new URLSource(saved.toUri().toString(), "audio/mpeg"))
                        .build());
    }

    private void mapFile(Map<String, Object> item, List<ContentBlock> out) {
        Map<String, Object> fileItem = asMap(item.get("file_item"));
        String fileName = str(fileItem, "file_name");
        if (fileName == null || fileName.isBlank()) fileName = "file.bin";
        byte[] bytes = downloadItem(fileItem, "file");
        if (bytes == null) {
            out.add(TextBlock.builder().text("[文件: " + fileName + " 下载失败]").build());
            return;
        }
        String mime = sniffMime(fileName);
        Path saved = persistMedia(bytes, mime, "file");
        out.add(
                DataBlock.builder()
                        .source(new URLSource(saved.toUri().toString(), mime))
                        .name(fileName)
                        .build());
    }

    private void mapVideo(Map<String, Object> item, List<ContentBlock> out) {
        Map<String, Object> videoItem = asMap(item.get("video_item"));
        byte[] bytes = downloadItem(videoItem, "video");
        if (bytes == null) {
            out.add(TextBlock.builder().text("[视频: 下载失败]").build());
            return;
        }
        Path saved = persistMedia(bytes, "video/mp4", "video");
        out.add(
                VideoBlock.builder()
                        .source(new URLSource(saved.toUri().toString(), "video/mp4"))
                        .build());
    }

    // ==================== media download / persistence ====================

    /** Downloads and AES-decrypts a media item. Returns null on failure. */
    private byte[] downloadItem(Map<String, Object> mediaItem, String label) {
        if (!props.mediaDownloadEnabled()) {
            log.debug("[weixin] media download disabled, skip {} item", label);
            return null;
        }
        Map<String, Object> media = asMap(mediaItem.get("media"));
        String encryptQueryParam = str(media, "encrypt_query_param");
        if (encryptQueryParam == null || encryptQueryParam.isBlank()) {
            log.warn("[weixin] No encrypt_query_param for {} download", label);
            return null;
        }
        // image_item carries a top-level hex "aeskey"; other items use media.aes_key (base64).
        String aesKeyHex = str(mediaItem, "aeskey");
        String aesKey =
                (aesKeyHex != null && !aesKeyHex.isBlank())
                        ? Base64.getEncoder().encodeToString(hexToBytes(aesKeyHex))
                        : str(media, "aes_key");
        try {
            return client.downloadMedia("", aesKey, encryptQueryParam);
        } catch (Exception e) {
            log.warn("[weixin] {} download failed: {}", label, e.getMessage());
            return null;
        }
    }

    private Path persistMedia(byte[] bytes, String mime, String prefix) {
        try {
            Path dir = Paths.get(props.resolveMediaDir(channelId));
            Files.createDirectories(dir);
            String ext = extensionFor(mime);
            String name = prefix + "_" + UUID.randomUUID() + "." + ext;
            Path file = dir.resolve(name);
            Files.write(file, bytes);
            return file;
        } catch (Exception e) {
            log.warn("[weixin] persist media failed: {}", e.getMessage());
            throw new RuntimeException("persist media failed", e);
        }
    }

    // ==================== helpers ====================

    private static String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                typed.put(String.valueOf(e.getKey()), e.getValue());
            }
            return typed;
        }
        return Map.of();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte)
                            ((Character.digit(hex.charAt(i), 16) << 4)
                                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String sniffImageMime(byte[] bytes) {
        if (bytes.length < 4) return "image/jpeg";
        // Magic byte sniffing for common image formats.
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        // WeChat commonly uses webp/heic; default to jpeg when inconclusive.
        return "image/jpeg";
    }

    private static String sniffMime(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String extensionFor(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "audio/mpeg" -> "mp3";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "application/zip" -> "zip";
            default -> "bin";
        };
    }
}
