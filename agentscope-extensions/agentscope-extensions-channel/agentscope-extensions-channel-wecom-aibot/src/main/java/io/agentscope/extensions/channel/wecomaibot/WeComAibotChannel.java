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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.wecomaibot.card.WeComAibotCardDispatcher;
import io.agentscope.extensions.channel.wecomaibot.card.WeComAibotCardEvent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * WeCom intelligent-robot (AI Bot) long-connection channel.
 *
 * <p>Transport: {@code wss://openws.work.weixin.qq.com} with {@code aibot_subscribe}. Credentials
 * may be deferred (QR auth by the host) via {@link #onCredentialsUpdated(String, String)}.
 *
 * <p><b>Single-leader:</b> running multiple processes with the same bot credentials will duplicate
 * inbound delivery.
 */
public final class WeComAibotChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotChannel.class);

    /** {@code type} value used in {@code agentscope.json} / {@code ChannelFactory}. */
    public static final String TYPE = "wecom-aibot";

    private static final int PROCESSED_IDS_MAX = 2000;

    private final String channelId;
    private final ChannelConfig config;
    private volatile WeComAibotChannelProperties properties;
    private final WeComAibotInboundMapper mapper;
    private final WeComAibotOutboundClient outbound;
    private final WeComAibotWsClient wsClient;
    private final WeComAibotReplyQueue replyQueue;
    private final WeComAibotKeepalive keepalive;
    private final WeComAibotGroupReplyReqIdCache groupReqIds;
    private final WeComAibotCardDispatcher cardDispatcher;
    private final WeComAibotMediaUploader mediaUploader;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final ConcurrentHashMap<String, Boolean> processedMessageIds =
            new ConcurrentHashMap<>();

    private volatile Gateway gateway;

    private WeComAibotChannel(
            String channelId,
            ChannelConfig config,
            WeComAibotChannelProperties properties,
            WeComAibotInboundMapper mapper,
            WeComAibotOutboundClient outbound,
            WeComAibotWsClient wsClient,
            WeComAibotReplyQueue replyQueue,
            WeComAibotKeepalive keepalive,
            WeComAibotGroupReplyReqIdCache groupReqIds,
            WeComAibotCardDispatcher cardDispatcher,
            WeComAibotMediaUploader mediaUploader,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = mapper;
        this.outbound = outbound;
        this.wsClient = wsClient;
        this.replyQueue = replyQueue;
        this.keepalive = keepalive;
        this.groupReqIds = groupReqIds;
        this.cardDispatcher = cardDispatcher;
        this.mediaUploader = mediaUploader;
        this.idempotency = idempotency;
        this.botLoopGuard = botLoopGuard;
        this.router = router;
    }

    public static WeComAibotChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WeComAibotChannelProperties props =
                WeComAibotChannelProperties.from(channelId, rawProperties);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicLong reqCounter = new AtomicLong();
        WeComAibotReplyQueue replyQueue =
                new WeComAibotReplyQueue(
                        channelId, props.replyAckTimeoutMs(), props.replyWorkerIdleMs());
        WeComAibotGroupReplyReqIdCache groupReqIds = new WeComAibotGroupReplyReqIdCache();
        WeComAibotKeepalive keepalive =
                new WeComAibotKeepalive(props.keepaliveRefreshSec(), props.keepaliveMaxSec());
        WeComAibotOutboundClient outbound =
                new WeComAibotOutboundClient(channelId, replyQueue, groupReqIds, reqCounter);
        WeComAibotWsClient wsClient =
                new WeComAibotWsClient(
                        channelId, objectMapper, props, replyQueue, keepalive, reqCounter);
        WeComAibotMediaUploader uploader =
                new WeComAibotMediaUploader(
                        channelId, objectMapper, reqCounter, wsClient::sendFrameAwaitAck);
        WeComAibotCardDispatcher cards = new WeComAibotCardDispatcher();
        cards.setUpdateSender(outbound::updateTemplateCard);
        outbound.setTransport(wsClient::sendFrame);
        outbound.setKeepaliveStopper(keepalive::stop);

        WeComAibotChannel channel =
                new WeComAibotChannel(
                        channelId,
                        routing,
                        props,
                        new WeComAibotInboundMapper(channelId, props),
                        outbound,
                        wsClient,
                        replyQueue,
                        keepalive,
                        groupReqIds,
                        cards,
                        uploader,
                        new IdempotencyStore(),
                        new BotLoopGuard(),
                        new ChannelRouter(routing.defaultAgentId()));
        wsClient.setFrameListener(channel::onWsFrame);
        return channel;
    }

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {
        WeComAibotChannelRegistry.instance().register(this);
        if (properties.hasCredentials()) {
            wsClient.start();
        } else {
            log.warn(
                    "[wecom-aibot:{}] no botId/secret; WS deferred until onCredentialsUpdated()",
                    channelId);
        }
        log.info(
                "[wecom-aibot:{}] channel started: wsUrl={}, mediaDownloadEnabled={}",
                channelId,
                properties.wsUrl(),
                properties.mediaDownloadEnabled());
    }

    @Override
    public void stop() {
        wsClient.stop();
        keepalive.shutdown();
        processedMessageIds.clear();
        outbound.clearContexts();
        groupReqIds.clear();
        WeComAibotChannelRegistry.instance().unregister(channelId);
        log.info("[wecom-aibot:{}] channel stopped", channelId);
    }

    /**
     * Hot-update credentials after host QR authorization and (re)start the WebSocket client.
     */
    public synchronized void onCredentialsUpdated(String botId, String secret) {
        this.properties = properties.withCredentials(botId, secret);
        wsClient.updateCredentials(botId, secret);
        if (wsClient.isRunning()) {
            wsClient.stop();
        }
        wsClient.start();
        log.info("[wecom-aibot:{}] credentials updated; WS (re)started", channelId);
    }

    public WeComAibotChannelProperties properties() {
        return properties;
    }

    public WeComAibotCardDispatcher cardDispatcher() {
        return cardDispatcher;
    }

    public WeComAibotMediaUploader mediaUploader() {
        return mediaUploader;
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException(
                            "WeComAibotChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(route.context(), message.messages(), route.outboundAddress())
                .flatMap(reply -> sendReply(route.outboundAddress(), reply).thenReturn(reply));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        outbound.send(address, messages)
                .doOnError(
                        err ->
                                log.warn(
                                        "[wecom-aibot:{}] deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    private Mono<Void> sendReply(OutboundAddress address, Msg reply) {
        // null / empty still goes through send so any processing stream is finished (✅ Done)
        List<Msg> messages = reply == null ? List.of() : List.of(reply);
        return outbound.send(address, messages)
                .doOnError(
                        err ->
                                log.warn(
                                        "[wecom-aibot:{}] reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }

    private void onWsFrame(String cmd, String frameReqId, JsonNode body) {
        try {
            if (WeComAibotWsClient.CMD_EVENT_CALLBACK.equals(cmd)) {
                handleEvent(frameReqId, body);
                return;
            }
            if (!WeComAibotWsClient.CMD_CALLBACK.equals(cmd)) {
                return;
            }
            var mapped = mapper.map(frameReqId, body);
            if (mapped.isEmpty()) {
                return;
            }
            WeComAibotInboundMapper.MappedInbound m = mapped.get();
            String idemKey = channelId + "|" + m.msgId();
            if (!idempotency.firstSeen(idemKey)) {
                log.debug("[wecom-aibot:{}] duplicate msgId={}", channelId, m.msgId());
                return;
            }
            if (processedMessageIds.putIfAbsent(m.msgId(), Boolean.TRUE) != null) {
                log.debug("[wecom-aibot:{}] duplicate local msgId={}", channelId, m.msgId());
                return;
            }
            trimProcessedIds();

            InboundMessage inbound = m.inbound();
            if (!botLoopGuard.allow(inbound.peer().key())) {
                log.warn(
                        "[wecom-aibot:{}] bot-loop guard triggered for peer={}",
                        channelId,
                        inbound.peer().key());
                return;
            }

            if ("group".equalsIgnoreCase(m.chatType())) {
                groupReqIds.remember(m.chatId(), frameReqId);
            }

            String streamId = outbound.newStreamId();
            outbound.putReplyContext(m.replyToken(), frameReqId, streamId);
            try {
                outbound.replyStream(
                        frameReqId, streamId, WeComAibotOutboundClient.PROCESSING_TEXT, false);
            } catch (Exception e) {
                log.debug(
                        "[wecom-aibot:{}] processing indicator failed: {}",
                        channelId,
                        e.getMessage());
            }
            try {
                keepalive.start(
                        new WeComAibotKeepalive.StreamCallbacks() {
                            @Override
                            public void refresh(String reqId, String sid, String content) {
                                // force=true so identical「思考中...」still hits the wire (TTL refresh)
                                outbound.replyStream(reqId, sid, content, false, true);
                            }

                            @Override
                            public void finish(String reqId, String sid, String content) {
                                outbound.replyStream(reqId, sid, content, true);
                            }

                            @Override
                            public void invalidateReplyContext(String replyToken, String sid) {
                                outbound.invalidateReplyContext(replyToken, sid);
                            }
                        },
                        frameReqId,
                        streamId,
                        m.replyToken(),
                        WeComAibotOutboundClient.PROCESSING_TEXT);
            } catch (Exception e) {
                log.debug("[wecom-aibot:{}] keepalive start failed: {}", channelId, e.getMessage());
            }

            String replyToken = m.replyToken();
            dispatch(inbound)
                    .doOnError(
                            err -> {
                                log.warn(
                                        "[wecom-aibot:{}] dispatch failed: {}",
                                        channelId,
                                        err.getMessage());
                                outbound.finishProcessing(
                                        replyToken, WeComAibotOutboundClient.ERROR_TEXT);
                            })
                    .doFinally(sig -> keepalive.stop(streamId))
                    .subscribe();
        } catch (Exception e) {
            log.error("[wecom-aibot:{}] onWsFrame error: {}", channelId, e.getMessage());
        }
    }

    private void handleEvent(String frameReqId, JsonNode body) {
        String eventType = text(body.path("event"), "eventtype");
        if (eventType == null) {
            eventType = text(body, "eventtype");
        }
        if ("enter_chat".equals(eventType)) {
            String welcome = properties.welcomeText();
            if (welcome != null && !welcome.isBlank()) {
                outbound.sendWelcome(frameReqId, welcome);
            }
            return;
        }
        if ("template_card_event".equals(eventType)) {
            JsonNode event = body.path("event");
            String taskId = firstNonBlank(text(event, "task_id"), text(body, "task_id"));
            String eventKey = firstNonBlank(text(event, "event_key"), text(body, "event_key"));
            String clicker =
                    firstNonBlank(
                            text(event.path("from"), "userid"), text(body.path("from"), "userid"));
            String chatId = textOr(body, "chatid", "");
            String chatType = textOr(body, "chattype", "single");
            cardDispatcher.dispatch(
                    new WeComAibotCardEvent(
                            channelId,
                            frameReqId,
                            taskId,
                            eventKey,
                            clicker,
                            chatId,
                            chatType,
                            Map.of()));
            return;
        }
        log.debug("[wecom-aibot:{}] Ignoring event type: {}", channelId, eventType);
    }

    private void trimProcessedIds() {
        if (processedMessageIds.size() <= PROCESSED_IDS_MAX) {
            return;
        }
        int remove = processedMessageIds.size() / 2;
        var it = processedMessageIds.keySet().iterator();
        while (remove-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

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
