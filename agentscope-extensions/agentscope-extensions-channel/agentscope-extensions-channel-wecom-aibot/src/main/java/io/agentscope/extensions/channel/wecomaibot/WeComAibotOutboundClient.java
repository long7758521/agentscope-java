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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Outbound markdown / stream / welcome / proactive send for WeCom AI Bot. */
public final class WeComAibotOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotOutboundClient.class);

    public static final String PROCESSING_TEXT = "思考中...";

    /** Clears the processing stream when the agent returns no text (aligned with MateClaw). */
    public static final String DONE_TEXT = "✅ Done";

    /** Clears the processing stream when dispatch fails. */
    public static final String ERROR_TEXT = "回复失败，请稍后重试。";

    private final String channelId;
    private final WeComAibotReplyQueue replyQueue;
    private final WeComAibotGroupReplyReqIdCache groupReqIds;
    private final AtomicLong reqCounter;
    private final ConcurrentHashMap<String, String> streamLastContent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReplyContext> replyContexts = new ConcurrentHashMap<>();

    private volatile FrameTransport transport;

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
    }

    public void setTransport(FrameTransport transport) {
        this.transport = transport;
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
        for (Msg msg : messages) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (ctx != null && !usedStream) {
                replyStream(ctx.frameReqId(), ctx.streamId(), text, true);
                usedStream = true;
            } else {
                sendMarkdown(target, text);
            }
        }
        // No text but we had a processing indicator — clear「思考中...」like MateClaw.
        if (ctx != null && !usedStream) {
            replyStream(ctx.frameReqId(), ctx.streamId(), DONE_TEXT, true);
        }
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
        replyStream(frameReqId, streamId, content, finish, false);
    }

    /**
     * @param force when {@code true}, skip non-finish content dedup (needed for keepalive TTL
     *     refresh with identical「思考中...」text)
     */
    public void replyStream(
            String frameReqId, String streamId, String content, boolean finish, boolean force) {
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
        if (target.kind() == PeerKind.GROUP) {
            String cached = groupReqIds.pick(target.id());
            if (cached != null) {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("cmd", "aibot_respond_msg");
                frame.put("headers", Map.of("req_id", cached));
                frame.put("body", body);
                enqueueRespond(cached, frame);
                return;
            }
            log.warn(
                    "[wecom-aibot:{}] group proactive send without cached req_id; falling back to"
                            + " aibot_send_msg (may be rejected)",
                    channelId);
        }
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

    private static PeerTarget parseAddress(OutboundAddress address) {
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
            kind = PeerKind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            kind = PeerKind.DIRECT;
        }
        return new PeerTarget(kind, id);
    }

    public record ReplyContext(String frameReqId, String streamId) {}

    private record PeerTarget(PeerKind kind, String id) {}

    @FunctionalInterface
    public interface FrameTransport {
        void sendFrame(Map<String, Object> frame);
    }
}
