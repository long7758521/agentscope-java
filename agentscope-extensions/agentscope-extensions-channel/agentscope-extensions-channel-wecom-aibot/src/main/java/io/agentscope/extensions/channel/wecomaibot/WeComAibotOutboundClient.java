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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Outbound client for WeCom AI Bot: stream / markdown / media / welcome / template card.
 *
 * <p>Each {@link Msg} is dispatched by {@link ContentBlock}: text becomes stream-finish or
 * markdown; image / audio / file / video blocks are uploaded via {@link WeComAibotMediaUploader}
 * then sent as {@code msgtype=image|file|voice|video} with {@code media_id}.
 */
public final class WeComAibotOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotOutboundClient.class);

    private static final Duration URL_FETCH_TIMEOUT = Duration.ofSeconds(60);

    public static final String PROCESSING_TEXT = "思考中...";

    /** Clears the processing stream when the agent returns no sendable content. */
    public static final String DONE_TEXT = "✅ Done";

    /** Clears the processing stream when dispatch fails. */
    public static final String ERROR_TEXT = "回复失败，请稍后重试。";

    private final String channelId;
    private final WeComAibotReplyQueue replyQueue;
    private final WeComAibotGroupReplyReqIdCache groupReqIds;
    private final AtomicLong reqCounter;
    private final HttpClient httpDownloader;
    private final ConcurrentHashMap<String, String> streamLastContent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReplyContext> replyContexts = new ConcurrentHashMap<>();

    private volatile FrameTransport transport;
    private volatile WeComAibotMediaUploader mediaUploader;

    /** Stops keepalive for a streamId before finish=true (must be set by the channel). */
    private volatile Consumer<String> keepaliveStopper;

    public WeComAibotOutboundClient(
            String channelId,
            WeComAibotReplyQueue replyQueue,
            WeComAibotGroupReplyReqIdCache groupReqIds,
            AtomicLong reqCounter) {
        this.channelId = channelId;
        this.replyQueue = replyQueue;
        this.groupReqIds = groupReqIds;
        this.reqCounter = reqCounter;
        this.httpDownloader =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void setTransport(FrameTransport transport) {
        this.transport = transport;
    }

    public void setMediaUploader(WeComAibotMediaUploader mediaUploader) {
        this.mediaUploader = mediaUploader;
    }

    public void setKeepaliveStopper(Consumer<String> keepaliveStopper) {
        this.keepaliveStopper = keepaliveStopper;
    }

    public void putReplyContext(String replyToken, String frameReqId, String streamId) {
        replyContexts.put(replyToken, new ReplyContext(frameReqId, streamId));
    }

    public ReplyContext removeReplyContext(String replyToken) {
        return replyContexts.remove(replyToken);
    }

    public void invalidateReplyContext(String replyToken, String streamId) {
        replyContexts.computeIfPresent(
                replyToken,
                (k, ctx) -> streamId != null && streamId.equals(ctx.streamId()) ? null : ctx);
    }

    public void clearContexts() {
        replyContexts.clear();
        streamLastContent.clear();
    }

    /**
     * Force-finish the processing stream for {@code replyToken} (e.g. dispatch failure). No-op if
     * no context remains.
     */
    public void finishProcessing(String replyToken, String content) {
        if (replyToken == null) {
            return;
        }
        ReplyContext ctx = replyContexts.remove(replyToken);
        if (ctx == null) {
            return;
        }
        stopKeepalive(ctx.streamId());
        String text = content == null || content.isBlank() ? ERROR_TEXT : content;
        replyStream(ctx.frameReqId(), ctx.streamId(), text, true);
    }

    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        List<Msg> safe = messages == null ? List.of() : messages;
        return Mono.fromRunnable(() -> sendBlocking(address, safe))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void sendBlocking(OutboundAddress address, List<Msg> messages) {
        PeerTarget target = parseAddress(address);
        String replyToken = target.id();
        ReplyContext ctx = replyContexts.remove(replyToken);
        // Stop keepalive before finish=true so a late refresh cannot overwrite the real reply.
        if (ctx != null) {
            stopKeepalive(ctx.streamId());
        }
        boolean usedStream = false;
        boolean sentAnything = false;
        String replyFrameReqId = ctx != null ? ctx.frameReqId() : null;

        for (Msg msg : messages) {
            List<ContentBlock> blocks = msg.getContent();
            if (blocks == null || blocks.isEmpty()) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    blocks = List.of(TextBlock.builder().text(text).build());
                } else {
                    continue;
                }
            }
            for (ContentBlock block : blocks) {
                try {
                    if (block instanceof TextBlock t) {
                        String text = t.getText();
                        if (text == null || text.isBlank()) {
                            continue;
                        }
                        String formatted = WeComAibotMarkdownTables.format(text);
                        if (ctx != null && !usedStream) {
                            replyStream(ctx.frameReqId(), ctx.streamId(), formatted, true);
                            usedStream = true;
                            // First text consumed the inbound reply slot; later media uses
                            // proactive / cached group req_id.
                            replyFrameReqId = null;
                        } else {
                            sendMarkdown(target, formatted);
                        }
                        sentAnything = true;
                    } else if (block instanceof ImageBlock ib) {
                        sentAnything |=
                                sendMediaBlock(
                                        target,
                                        resolveBytes(ib.getSource()),
                                        "image",
                                        fileNameFromSource(ib.getSource(), "image.jpg"),
                                        mimeFromSource(ib.getSource(), "image/jpeg"),
                                        replyFrameReqId);
                        replyFrameReqId = null;
                    } else if (block instanceof AudioBlock ab) {
                        String name = fileNameFromSource(ab.getSource(), "voice.amr");
                        boolean amr = name.toLowerCase(Locale.ROOT).endsWith(".amr");
                        sentAnything |=
                                sendMediaBlock(
                                        target,
                                        resolveBytes(ab.getSource()),
                                        amr ? "voice" : "file",
                                        name,
                                        mimeFromSource(
                                                ab.getSource(), amr ? "audio/amr" : "audio/mpeg"),
                                        replyFrameReqId);
                        replyFrameReqId = null;
                    } else if (block instanceof VideoBlock vb) {
                        sentAnything |=
                                sendMediaBlock(
                                        target,
                                        resolveBytes(vb.getSource()),
                                        "video",
                                        fileNameFromSource(vb.getSource(), "video.mp4"),
                                        mimeFromSource(vb.getSource(), "video/mp4"),
                                        replyFrameReqId);
                        replyFrameReqId = null;
                    } else if (block instanceof DataBlock db) {
                        String name = db.getName() != null ? db.getName() : "file.bin";
                        sentAnything |=
                                sendMediaBlock(
                                        target,
                                        resolveBytes(db.getSource()),
                                        "file",
                                        name,
                                        mimeFromSource(db.getSource(), null),
                                        replyFrameReqId);
                        replyFrameReqId = null;
                    } else {
                        log.debug(
                                "[wecom-aibot:{}] unsupported outbound block {}, skip",
                                channelId,
                                block.getClass().getSimpleName());
                    }
                } catch (Exception e) {
                    log.warn(
                            "[wecom-aibot:{}] send block {} failed: {}",
                            channelId,
                            block.getClass().getSimpleName(),
                            e.getMessage());
                    sendFallback(target, block);
                    sentAnything = true;
                }
            }
        }
        // No content but we had a processing indicator — clear「思考中...」so the slot closes.
        if (ctx != null && !usedStream && !sentAnything) {
            replyStream(ctx.frameReqId(), ctx.streamId(), DONE_TEXT, true);
        } else if (ctx != null && !usedStream && sentAnything) {
            // Media-only reply: still close the processing stream slot.
            replyStream(ctx.frameReqId(), ctx.streamId(), DONE_TEXT, true);
        }
    }

    private boolean sendMediaBlock(
            PeerTarget target,
            byte[] bytes,
            String mediaType,
            String fileName,
            String contentType,
            String frameReqId) {
        if (bytes == null || bytes.length == 0) {
            sendMarkdown(target, fallbackLabel(mediaType, fileName));
            return true;
        }
        WeComAibotMediaUploader uploader = mediaUploader;
        if (uploader == null) {
            log.warn(
                    "[wecom-aibot:{}] media uploader not set; cannot send {}",
                    channelId,
                    mediaType);
            sendMarkdown(target, fallbackLabel(mediaType, fileName));
            return true;
        }
        WeComAibotUploadLimits.Decision decision =
                WeComAibotUploadLimits.apply(bytes.length, mediaType, contentType);
        if (decision.rejected()) {
            sendMarkdown(target, "⚠️ " + decision.message());
            return true;
        }
        String effectiveType = decision.downgraded() ? decision.mediaType() : mediaType;
        String mediaId = uploader.upload(bytes, effectiveType, fileName, contentType);
        if (mediaId == null) {
            sendMarkdown(target, fallbackLabel(mediaType, fileName));
            return true;
        }
        sendMediaMessage(target, mediaId, effectiveType, frameReqId);
        if (decision.downgraded() && decision.message() != null) {
            sendMarkdown(target, "ℹ️ " + decision.message());
        }
        return true;
    }

    /**
     * Sends a native media message. When {@code frameReqId} is present, rides {@code
     * aibot_respond_msg}; otherwise uses proactive send (group prefers cached inbound req_id).
     */
    public void sendMediaMessage(
            PeerTarget target, String mediaId, String mediaType, String frameReqId) {
        Map<String, Object> mediaBody = new LinkedHashMap<>();
        mediaBody.put("msgtype", mediaType);
        mediaBody.put(mediaType, Map.of("media_id", mediaId));
        if (frameReqId != null && !frameReqId.isBlank()) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("cmd", "aibot_respond_msg");
            frame.put("headers", Map.of("req_id", frameReqId));
            frame.put("body", mediaBody);
            enqueueRespond(frameReqId, frame);
            return;
        }
        sendOutboundBody(target, mediaBody);
    }

    private void sendFallback(PeerTarget target, ContentBlock block) {
        if (block instanceof ImageBlock ib && ib.getSource() instanceof URLSource u) {
            sendMarkdown(target, "![image](" + u.getUrl() + ")");
        } else if (block instanceof DataBlock db) {
            sendMarkdown(target, "[文件: " + (db.getName() != null ? db.getName() : "file") + "]");
        } else if (block instanceof AudioBlock) {
            sendMarkdown(target, "[语音回复]");
        } else if (block instanceof VideoBlock) {
            sendMarkdown(target, "[视频]");
        }
    }

    private static String fallbackLabel(String mediaType, String fileName) {
        return switch (mediaType == null ? "" : mediaType) {
            case "image" -> "[图片]";
            case "voice" -> "[语音回复]";
            case "video" -> "[视频]";
            default -> "[文件: " + (fileName != null ? fileName : "file") + "]";
        };
    }

    private byte[] resolveBytes(Source src) throws Exception {
        if (src == null) {
            return null;
        }
        if (src instanceof URLSource u) {
            String url = u.getUrl();
            if (url == null || url.isBlank()) {
                return null;
            }
            URI uri = URI.create(url);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path p = Paths.get(uri);
                return Files.readAllBytes(p);
            }
            HttpRequest req =
                    HttpRequest.newBuilder().uri(uri).timeout(URL_FETCH_TIMEOUT).GET().build();
            HttpResponse<byte[]> resp =
                    httpDownloader.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException(
                        "URL fetch failed: HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        }
        if (src instanceof Base64Source b) {
            return Base64.getDecoder().decode(b.getData());
        }
        log.warn(
                "[wecom-aibot:{}] unknown source type {}",
                channelId,
                src.getClass().getSimpleName());
        return null;
    }

    private static String mimeFromSource(Source src, String fallback) {
        if (src instanceof URLSource u && u.getMimeType() != null && !u.getMimeType().isBlank()) {
            return u.getMimeType();
        }
        if (src instanceof Base64Source b
                && b.getMediaType() != null
                && !b.getMediaType().isBlank()) {
            return b.getMediaType();
        }
        return fallback;
    }

    private static String fileNameFromSource(Source src, String fallback) {
        if (src instanceof URLSource u) {
            String url = u.getUrl();
            if (url != null) {
                try {
                    String path = URI.create(url).getPath();
                    if (path != null) {
                        int slash = path.lastIndexOf('/');
                        String name = slash >= 0 ? path.substring(slash + 1) : path;
                        if (!name.isBlank() && name.contains(".")) {
                            return name;
                        }
                    }
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return fallback;
    }

    public void sendWelcome(String frameReqId, String welcomeText) {
        if (welcomeText == null || welcomeText.isBlank() || frameReqId == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", Map.of("content", welcomeText));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", "aibot_respond_welcome_msg");
        frame.put("headers", Map.of("req_id", frameReqId));
        frame.put("body", body);
        sendRaw(frame);
    }

    public void replyStream(String frameReqId, String streamId, String content, boolean finish) {
        replyStream(frameReqId, streamId, content, finish, false, null);
    }

    /**
     * @param force when {@code true}, skip non-finish content dedup (needed for keepalive TTL
     *     refresh with identical「思考中...」text)
     */
    public void replyStream(
            String frameReqId, String streamId, String content, boolean finish, boolean force) {
        replyStream(frameReqId, streamId, content, finish, force, null);
    }

    /**
     * Streaming reply. {@code feedbackId} is only attached when {@code finish=true} (WeCom AI Bot
     * protocol: feedback is meaningful on the finishing chunk).
     */
    public void replyStream(
            String frameReqId,
            String streamId,
            String content,
            boolean finish,
            boolean force,
            String feedbackId) {
        if (frameReqId == null || streamId == null) {
            return;
        }
        if (!finish && !force) {
            String prev = streamLastContent.get(streamId);
            if (content != null && content.equals(prev)) {
                return;
            }
        }
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("id", streamId);
        stream.put("finish", finish);
        stream.put("content", content != null ? content : "");
        if (finish && feedbackId != null && !feedbackId.isBlank()) {
            stream.put("feedback", Map.of("id", feedbackId));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "stream");
        body.put("stream", stream);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", "aibot_respond_msg");
        frame.put("headers", Map.of("req_id", frameReqId));
        frame.put("body", body);
        if (finish) {
            streamLastContent.remove(streamId);
        } else {
            streamLastContent.put(streamId, content != null ? content : "");
        }
        enqueueRespond(frameReqId, frame);
    }

    public void sendTemplateCard(String frameReqId, Map<String, Object> templateCardBody) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "template_card");
        body.put("template_card", templateCardBody);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", "aibot_respond_msg");
        frame.put("headers", Map.of("req_id", frameReqId));
        frame.put("body", body);
        enqueueRespond(frameReqId, frame);
    }

    public void updateTemplateCard(String eventFrameReqId, Map<String, Object> updateBody) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", "aibot_respond_update_msg");
        frame.put("headers", Map.of("req_id", eventFrameReqId));
        frame.put("body", updateBody);
        sendRaw(frame);
    }

    public String newStreamId() {
        return "stream_" + System.currentTimeMillis() + "_" + reqCounter.incrementAndGet();
    }

    private void stopKeepalive(String streamId) {
        Consumer<String> stopper = keepaliveStopper;
        if (stopper == null || streamId == null) {
            return;
        }
        try {
            stopper.accept(streamId);
        } catch (Exception e) {
            log.debug(
                    "[wecom-aibot:{}] keepalive stop failed for {}: {}",
                    channelId,
                    streamId,
                    e.getMessage());
        }
    }

    private void sendMarkdown(PeerTarget target, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("content", text));
        sendOutboundBody(target, body);
    }

    /**
     * Proactive / follow-up outbound body. Group chats prefer a cached inbound {@code req_id} via
     * {@code aibot_respond_msg}; otherwise {@code aibot_send_msg} (may be rejected in groups).
     */
    private void sendOutboundBody(PeerTarget target, Map<String, Object> bodyWithMsgtype) {
        if (target.kind() == PeerKind.GROUP) {
            String cached = groupReqIds.pick(target.id());
            if (cached != null) {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("cmd", "aibot_respond_msg");
                frame.put("headers", Map.of("req_id", cached));
                frame.put("body", bodyWithMsgtype);
                enqueueRespond(cached, frame);
                return;
            }
            log.warn(
                    "[wecom-aibot:{}] group proactive send without cached req_id; falling back to"
                            + " aibot_send_msg (may be rejected)",
                    channelId);
        }
        Map<String, Object> body = new LinkedHashMap<>(bodyWithMsgtype);
        body.put("chatid", target.id());
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", "aibot_send_msg");
        frame.put("headers", Map.of("req_id", generateReqId("aibot_send_msg")));
        frame.put("body", body);
        sendRaw(frame);
    }

    private void enqueueRespond(String inboundReqId, Map<String, Object> frame) {
        try {
            replyQueue.sendFrameWithAck(inboundReqId, frame).join();
        } catch (Exception e) {
            log.warn("[wecom-aibot:{}] respond send failed: {}", channelId, e.getMessage());
        }
    }

    private void sendRaw(Map<String, Object> frame) {
        FrameTransport t = transport;
        if (t == null) {
            log.warn("[wecom-aibot:{}] no transport; drop frame", channelId);
            return;
        }
        t.sendFrame(frame);
    }

    private String generateReqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + reqCounter.incrementAndGet();
    }

    static PeerTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int sep = to.indexOf(':');
        if (sep < 0) {
            return new PeerTarget(PeerKind.DIRECT, to);
        }
        String rest = to.substring(sep + 1);
        int sep2 = rest.indexOf(':');
        String kindRaw;
        String id;
        if (sep2 < 0) {
            kindRaw = "DIRECT";
            id = rest;
        } else {
            kindRaw = rest.substring(0, sep2);
            id = rest.substring(sep2 + 1);
        }
        PeerKind kind;
        try {
            kind = PeerKind.valueOf(kindRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            kind = PeerKind.DIRECT;
        }
        return new PeerTarget(kind, id);
    }

    public record ReplyContext(String frameReqId, String streamId) {}

    public record PeerTarget(PeerKind kind, String id) {}

    @FunctionalInterface
    public interface FrameTransport {
        void sendFrame(Map<String, Object> frame);
    }
}
