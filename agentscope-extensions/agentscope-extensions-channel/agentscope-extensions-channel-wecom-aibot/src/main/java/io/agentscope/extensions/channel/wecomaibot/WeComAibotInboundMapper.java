/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.wecomaibot;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps WeCom AI Bot {@code aibot_msg_callback} body JSON into {@link InboundMessage}.
 *
 * <p>Unsupported types (including {@code video}) return empty so the caller can ignore without
 * dispatching.
 */
public final class WeComAibotInboundMapper {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotInboundMapper.class);

    static final String PUBLIC_ACCOUNT_ARTICLE_HINT = "\n\n（提示：这是公众号文章链接，请勿臆造正文内容；如需分析请用户粘贴正文。）";

    private final String channelId;
    private final WeComAibotChannelProperties properties;
    private final HttpClient httpClient;

    public WeComAibotInboundMapper(String channelId, WeComAibotChannelProperties properties) {
        this(
                channelId,
                properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    WeComAibotInboundMapper(
            String channelId, WeComAibotChannelProperties properties, HttpClient httpClient) {
        this.channelId = channelId;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public Optional<MappedInbound> map(String frameReqId, JsonNode body) {
        if (body == null || body.isNull()) {
            return Optional.empty();
        }
        String msgType = text(body, "msgtype");
        if (msgType == null || msgType.isBlank()) {
            return Optional.empty();
        }
        String chatId = textOr(body, "chatid", "");
        String chatType = textOr(body, "chattype", "single");
        String senderId = text(body.path("from"), "userid");
        if (senderId == null || senderId.isBlank()) {
            return Optional.empty();
        }
        String msgId = textOr(body, "msgid", "");
        if (msgId.isBlank()) {
            msgId =
                    senderId
                            + "_"
                            + textOr(body, "send_time", String.valueOf(System.currentTimeMillis()));
        }

        StringBuilder textBuf = new StringBuilder();
        List<String> mediaNotes = new ArrayList<>();
        boolean handled = appendContent(body, msgType, textBuf, mediaNotes);
        if (!handled) {
            log.debug("[wecom-aibot:{}] Ignoring unsupported message type: {}", channelId, msgType);
            return Optional.empty();
        }

        String quote = extractQuoteContext(body);
        if (quote != null && !quote.isBlank()) {
            textBuf.insert(0, "[引用消息: " + quote + "]\n");
        }

        String content = textBuf.toString().trim();
        if (content.isEmpty() && mediaNotes.isEmpty()) {
            return Optional.empty();
        }
        if (!mediaNotes.isEmpty()) {
            if (!content.isEmpty()) {
                content = content + "\n";
            }
            content = content + String.join("\n", mediaNotes);
        }

        boolean isGroup = "group".equalsIgnoreCase(chatType);
        Peer peer =
                isGroup ? new Peer(PeerKind.GROUP, chatId) : new Peer(PeerKind.DIRECT, senderId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(senderId).textContent(content).build();
        InboundMessage inbound =
                InboundMessage.builder(channelId, peer, List.of(msg)).senderId(senderId).build();
        String replyToken = isGroup ? chatId : senderId;
        return Optional.of(
                new MappedInbound(inbound, msgId, replyToken, chatType, chatId, frameReqId));
    }

    private boolean appendContent(
            JsonNode body, String msgType, StringBuilder textBuf, List<String> mediaNotes) {
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "text" -> {
                String c = text(body.path("text"), "content");
                if (c != null) {
                    textBuf.append(c.trim());
                }
                yield true;
            }
            case "voice" -> {
                String c = text(body.path("voice"), "content");
                textBuf.append(c == null || c.isBlank() ? "[语音消息]" : c.trim());
                yield true;
            }
            case "image" -> {
                mediaNotes.add(describeMedia("image", body.path("image")));
                yield true;
            }
            case "file" -> {
                mediaNotes.add(describeMedia("file", body.path("file")));
                yield true;
            }
            case "mixed" -> {
                JsonNode items = body.path("mixed").path("msg_item");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String t = textOr(item, "msgtype", "");
                        appendContent(item, t, textBuf, mediaNotes);
                        if (!textBuf.isEmpty() && textBuf.charAt(textBuf.length() - 1) != '\n') {
                            textBuf.append('\n');
                        }
                    }
                }
                yield true;
            }
            case "appmsg" -> {
                textBuf.append(parseAppmsg(body.path("appmsg")));
                yield true;
            }
            default -> false;
        };
    }

    private String describeMedia(String kind, JsonNode node) {
        String url = text(node, "url");
        String aesKey = text(node, "aeskey");
        String filename =
                firstNonBlank(text(node, "filename"), text(node, "file_name"), text(node, "name"));
        if (properties.mediaDownloadEnabled() && url != null && !url.isBlank()) {
            Path saved = tryDownload(kind, url, aesKey, filename);
            if (saved != null) {
                return "[" + kind + ": " + saved.toAbsolutePath() + "]";
            }
            if ("image".equals(kind)) {
                log.warn(
                        "[wecom-aibot:{}] image download/decrypt failed; URL-only fallback may be"
                                + " unreadable by the model",
                        channelId);
            }
        }
        String label = filename != null ? filename : (url != null ? url : kind);
        return "[" + kind + ": " + label + "]";
    }

    private Path tryDownload(String kind, String url, String aesKey, String filename) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();
            HttpResponse<byte[]> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            byte[] bytes = resp.body();
            if (aesKey != null && !aesKey.isBlank()) {
                bytes = WeComAibotMediaCrypto.decryptAes256Cbc(bytes, aesKey);
            }
            Path dir = Path.of(properties.mediaDir());
            Files.createDirectories(dir);
            String name =
                    filename != null && !filename.isBlank()
                            ? filename
                            : kind + "_" + System.currentTimeMillis();
            Path out = dir.resolve(name);
            Files.write(out, bytes);
            return out;
        } catch (Exception e) {
            log.debug("[wecom-aibot:{}] media download failed: {}", channelId, e.getMessage());
            return null;
        }
    }

    private String extractQuoteContext(JsonNode body) {
        JsonNode quote = body.path("quote");
        if (quote.isMissingNode() || quote.isNull()) {
            return null;
        }
        String qType = textOr(quote, "msgtype", "");
        StringBuilder sb = new StringBuilder();
        List<String> notes = new ArrayList<>();
        if (!appendContent(quote, qType, sb, notes)) {
            return "[" + qType + "]";
        }
        String t = sb.toString().trim();
        if (!notes.isEmpty()) {
            t = (t.isEmpty() ? "" : t + " ") + String.join(" ", notes);
        }
        return t.isEmpty() ? "[" + qType + "]" : t;
    }

    private String parseAppmsg(JsonNode appmsg) {
        String title = firstNonBlank(text(appmsg, "title"), text(appmsg, "appname"));
        String url = firstNonBlank(text(appmsg, "url"), text(appmsg, "pagepath"));
        String type = textOr(appmsg, "type", "");
        StringBuilder sb = new StringBuilder("[appmsg");
        if (title != null) {
            sb.append(": ").append(title);
        }
        sb.append(']');
        if (url != null) {
            sb.append(' ').append(url);
        }
        if (isPublicAccountArticle(type, url)) {
            sb.append(PUBLIC_ACCOUNT_ARTICLE_HINT);
        }
        return sb.toString();
    }

    private static boolean isPublicAccountArticle(String type, String url) {
        if (url != null && (url.contains("mp.weixin.qq.com") || url.contains("weixin.qq.com"))) {
            return true;
        }
        return "5".equals(type) || "news".equalsIgnoreCase(type);
    }

    public record MappedInbound(
            InboundMessage inbound,
            String msgId,
            String replyToken,
            String chatType,
            String chatId,
            String frameReqId) {}

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null ? fallback : v;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
