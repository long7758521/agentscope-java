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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
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
 * <p>Media is downloaded (optional AES decrypt), persisted under {@code mediaDir}, and exposed as
 * {@link ImageBlock} / {@link DataBlock} with {@code file://} {@link URLSource}. Unsupported types
 * (including {@code video}) return empty so the caller can ignore without dispatching.
 */
public final class WeComAibotInboundMapper {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotInboundMapper.class);

    /**
     * Hint appended to WeChat public-account article links. Body text is behind a captcha-gated
     * page; without this, models invent content from the title alone.
     */
    static final String PUBLIC_ACCOUNT_ARTICLE_HINT =
            "\n\n（提示：该链接为公众号文章，正文需要用户在微信内打开后复制粘贴，" + "请优先请用户粘贴正文，不要凭标题猜测内容。）";

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

        List<ContentBlock> blocks = new ArrayList<>();
        if (!appendMsgType(body, msgType, blocks, null)) {
            log.debug("[wecom-aibot:{}] Ignoring unsupported message type: {}", channelId, msgType);
            return Optional.empty();
        }

        QuoteContext quote = extractQuoteContext(body);
        if (quote != null && !quote.isEmpty()) {
            applyQuote(blocks, quote);
        }

        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        boolean isGroup = "group".equalsIgnoreCase(chatType);
        Peer peer =
                isGroup ? new Peer(PeerKind.GROUP, chatId) : new Peer(PeerKind.DIRECT, senderId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(senderId).content(blocks).build();
        InboundMessage inbound =
                InboundMessage.builder(channelId, peer, List.of(msg)).senderId(senderId).build();
        String replyToken = isGroup ? chatId : senderId;
        return Optional.of(
                new MappedInbound(inbound, msgId, replyToken, chatType, chatId, frameReqId));
    }

    /**
     * @param defaultImageName when non-null, used as filename hint for image downloads (WeCom image
     *     URLs usually have no extension)
     * @return false if msgType is unsupported
     */
    private boolean appendMsgType(
            JsonNode body, String msgType, List<ContentBlock> blocks, String defaultImageName) {
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "text" -> {
                String c = text(body.path("text"), "content");
                if (c != null && !c.isBlank()) {
                    blocks.add(TextBlock.builder().text(c.trim()).build());
                }
                yield true;
            }
            case "voice" -> {
                String c = text(body.path("voice"), "content");
                blocks.add(
                        TextBlock.builder()
                                .text(c == null || c.isBlank() ? "[语音消息]" : c.trim())
                                .build());
                yield true;
            }
            case "image" -> {
                appendImage(
                        body.path("image"),
                        blocks,
                        defaultImageName != null ? defaultImageName : "image.jpg");
                yield true;
            }
            case "file" -> {
                appendFile(body.path("file"), blocks, "file.bin");
                yield true;
            }
            case "mixed" -> {
                JsonNode items = body.path("mixed").path("msg_item");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String t = textOr(item, "msgtype", "");
                        appendMsgType(item, t, blocks, "mixed_image.jpg");
                    }
                }
                yield true;
            }
            case "appmsg" -> {
                appendAppmsg(body.path("appmsg"), blocks);
                yield true;
            }
            default -> false;
        };
    }

    private void appendImage(JsonNode node, List<ContentBlock> blocks, String fileNameHint) {
        String url = text(node, "url");
        String aesKey = text(node, "aeskey");
        if (url == null || url.isBlank()) {
            blocks.add(TextBlock.builder().text("[图片]").build());
            return;
        }
        if (properties.mediaDownloadEnabled()) {
            SavedMedia saved = tryDownload("image", url, aesKey, fileNameHint);
            if (saved != null) {
                blocks.add(
                        ImageBlock.builder()
                                .source(
                                        new URLSource(
                                                saved.path().toUri().toString(), saved.mime()))
                                .build());
                return;
            }
            log.warn(
                    "[wecom-aibot:{}] image download/decrypt failed; skipping remote URL"
                            + " (encrypted COS URLs are unreadable by models)",
                    channelId);
        }
        // Do not attach remote ImageBlock: WeCom COS URLs are typically AES-encrypted and
        // short-lived; models cannot fetch them and text-only APIs reject image_url parts.
        blocks.add(TextBlock.builder().text("[图片: " + fileNameHint + "]").build());
    }

    private void appendFile(JsonNode node, List<ContentBlock> blocks, String defaultName) {
        String url = text(node, "url");
        String aesKey = text(node, "aeskey");
        String filename =
                firstNonBlank(
                        text(node, "filename"),
                        text(node, "file_name"),
                        text(node, "name"),
                        defaultName);
        if (url == null || url.isBlank()) {
            blocks.add(TextBlock.builder().text("[文件: " + filename + "]").build());
            return;
        }
        if (properties.mediaDownloadEnabled()) {
            SavedMedia saved = tryDownload("file", url, aesKey, filename);
            if (saved != null) {
                blocks.add(
                        DataBlock.builder()
                                .source(
                                        new URLSource(
                                                saved.path().toUri().toString(), saved.mime()))
                                .name(saved.fileName())
                                .build());
                return;
            }
            log.warn(
                    "[wecom-aibot:{}] file download/decrypt failed; skipping remote URL"
                            + " (encrypted COS URLs are unreadable by models)",
                    channelId);
        }
        // Do not attach remote DataBlock for the same reason as images.
        blocks.add(TextBlock.builder().text("[文件: " + filename + "]").build());
    }

    /**
     * Parses {@code msgtype=appmsg}: forwarded file / image, miniprogram card, or link / public
     * account article.
     */
    private void appendAppmsg(JsonNode appmsg, List<ContentBlock> blocks) {
        if (appmsg == null || appmsg.isMissingNode() || appmsg.isNull()) {
            blocks.add(TextBlock.builder().text("[appmsg]").build());
            return;
        }
        String title = firstNonBlank(text(appmsg, "title"), text(appmsg, "appname"));
        String desc = textOr(appmsg, "description", "").trim();
        String linkUrl = firstNonBlank(text(appmsg, "url"), text(appmsg, "pagepath"));
        JsonNode fileNode = appmsg.path("file");
        JsonNode imageNode = appmsg.path("image");
        JsonNode miniNode = appmsg.path("miniprogram");

        if (!fileNode.isMissingNode() && !fileNode.isNull() && fileNode.isObject()) {
            String fallbackName = title != null && !title.isBlank() ? title : "file.bin";
            appendFile(fileNode, blocks, fallbackName);
            return;
        }
        if (!imageNode.isMissingNode() && !imageNode.isNull() && imageNode.isObject()) {
            appendImage(imageNode, blocks, "appmsg_image.jpg");
            if (title != null && !title.isBlank()) {
                blocks.add(0, TextBlock.builder().text("[图片: " + title + "]").build());
            }
            return;
        }
        if (!miniNode.isMissingNode() && !miniNode.isNull() && miniNode.isObject()) {
            String miniTitle =
                    firstNonBlank(text(miniNode, "title"), title != null ? title : "未命名小程序");
            blocks.add(TextBlock.builder().text("[小程序: " + miniTitle + "]").build());
            return;
        }
        if (linkUrl != null && !linkUrl.isBlank()) {
            StringBuilder sb = new StringBuilder("[链接]");
            if (title != null && !title.isBlank()) {
                sb.append(' ').append(title);
            }
            if (!desc.isBlank()) {
                sb.append('\n').append(desc);
            }
            sb.append('\n').append(linkUrl);
            if (isPublicAccountArticle(textOr(appmsg, "type", ""), linkUrl)) {
                sb.append(PUBLIC_ACCOUNT_ARTICLE_HINT);
            }
            blocks.add(TextBlock.builder().text(sb.toString()).build());
            return;
        }
        if (title != null && !title.isBlank()) {
            blocks.add(TextBlock.builder().text("[appmsg: " + title + "]").build());
        } else {
            blocks.add(TextBlock.builder().text("[appmsg]").build());
        }
    }

    private QuoteContext extractQuoteContext(JsonNode body) {
        JsonNode quote = body.path("quote");
        if (quote.isMissingNode() || quote.isNull()) {
            return null;
        }
        String quoteType = textOr(quote, "msgtype", "");
        if (quoteType.isBlank()) {
            return null;
        }
        List<JsonNode> items = new ArrayList<>();
        if ("mixed".equalsIgnoreCase(quoteType)) {
            JsonNode arr = quote.path("mixed").path("msg_item");
            if (arr.isArray()) {
                arr.forEach(items::add);
            }
        } else {
            items.add(quote);
        }
        StringBuilder summary = new StringBuilder();
        List<ContentBlock> attached = new ArrayList<>();
        for (JsonNode item : items) {
            String itemType = textOr(item, "msgtype", "");
            switch (itemType.toLowerCase(Locale.ROOT)) {
                case "text" -> {
                    String content = textOr(item.path("text"), "content", "").trim();
                    if (!content.isBlank()) {
                        appendQuoteSummary(summary, content);
                    }
                }
                case "voice" -> {
                    String asr = textOr(item.path("voice"), "content", "").trim();
                    appendQuoteSummary(summary, asr.isBlank() ? "[语音消息]" : "[语音] " + asr);
                }
                case "image" -> {
                    appendQuoteSummary(summary, "[图片]");
                    List<ContentBlock> imgBlocks = new ArrayList<>();
                    appendImage(item.path("image"), imgBlocks, "quoted_image.jpg");
                    for (ContentBlock b : imgBlocks) {
                        if (b instanceof ImageBlock || b instanceof DataBlock) {
                            attached.add(b);
                        }
                    }
                }
                case "file" -> {
                    List<ContentBlock> fileBlocks = new ArrayList<>();
                    appendFile(item.path("file"), fileBlocks, "file.bin");
                    String label = "[文件]";
                    for (ContentBlock b : fileBlocks) {
                        if (b instanceof DataBlock db) {
                            attached.add(db);
                            if (db.getName() != null) {
                                label = "[文件: " + db.getName() + "]";
                            }
                        }
                    }
                    appendQuoteSummary(summary, label);
                }
                default -> {
                    if (!itemType.isBlank()) {
                        appendQuoteSummary(summary, "[" + itemType + "]");
                    }
                }
            }
        }
        if (summary.length() == 0 && attached.isEmpty()) {
            return null;
        }
        String prefix = "[引用消息: " + summary + "]\n";
        return new QuoteContext(prefix, attached);
    }

    private static void applyQuote(List<ContentBlock> blocks, QuoteContext quote) {
        String prefixed;
        // Merge quote prefix into an existing leading text block when present.
        if (!blocks.isEmpty() && blocks.get(0) instanceof TextBlock t) {
            String existing = t.getText() != null ? t.getText() : "";
            prefixed = quote.prefix() + existing;
            blocks.set(0, TextBlock.builder().text(prefixed).build());
        } else {
            blocks.add(0, TextBlock.builder().text(quote.prefix().trim()).build());
        }
        if (!quote.attached().isEmpty()) {
            blocks.addAll(1, quote.attached());
        }
    }

    private static void appendQuoteSummary(StringBuilder summary, String fragment) {
        if (summary.length() > 0) {
            summary.append(' ');
        }
        summary.append(fragment);
    }

    private SavedMedia tryDownload(String kind, String url, String aesKey, String filenameHint) {
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
            WeComAibotMediaTypeSniffer.Sniffed sniff = WeComAibotMediaTypeSniffer.sniff(bytes);
            String hint =
                    filenameHint != null && !filenameHint.isBlank()
                            ? filenameHint
                            : ("image".equals(kind) ? "image.jpg" : "file.bin");
            String fileName =
                    WeComAibotMediaTypeSniffer.needsExtensionFix(hint) || sniff.isKnown()
                            ? WeComAibotMediaTypeSniffer.withSniffedExtension(hint, sniff)
                            : hint;
            // Prefer sniffed MIME; fall back by kind.
            String mime =
                    sniff.isKnown()
                            ? sniff.contentType()
                            : ("image".equals(kind) ? "image/jpeg" : "application/octet-stream");
            Path dir = Path.of(properties.mediaDir());
            Files.createDirectories(dir);
            String safe = fileName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
            if (safe.isBlank()) {
                safe =
                        kind
                                + "_"
                                + System.currentTimeMillis()
                                + (sniff.isKnown() ? sniff.extension() : "");
            }
            Path out = dir.resolve(safe);
            // Avoid overwrite collisions.
            if (Files.exists(out)) {
                String base = safe;
                int dot = safe.lastIndexOf('.');
                String stem = dot > 0 ? safe.substring(0, dot) : safe;
                String ext = dot > 0 ? safe.substring(dot) : "";
                out = dir.resolve(stem + "_" + System.currentTimeMillis() + ext);
                if (base.isBlank()) {
                    out = dir.resolve(kind + "_" + System.currentTimeMillis() + ext);
                }
            }
            Files.write(out, bytes);
            return new SavedMedia(out, out.getFileName().toString(), mime);
        } catch (Exception e) {
            log.debug("[wecom-aibot:{}] media download failed: {}", channelId, e.getMessage());
            return null;
        }
    }

    private static boolean isPublicAccountArticle(String type, String url) {
        if (url != null) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("://mp.weixin.qq.com/") || lower.startsWith("mp.weixin.qq.com/")) {
                return true;
            }
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

    private record QuoteContext(String prefix, List<ContentBlock> attached) {
        boolean isEmpty() {
            return (prefix == null || prefix.isBlank()) && (attached == null || attached.isEmpty());
        }
    }

    private record SavedMedia(Path path, String fileName, String mime) {}

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
