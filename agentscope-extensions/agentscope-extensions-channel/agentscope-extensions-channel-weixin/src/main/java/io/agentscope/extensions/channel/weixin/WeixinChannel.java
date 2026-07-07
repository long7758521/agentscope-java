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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * WeChat personal account (iLink Bot) channel adapter.
 *
 * <p>Inbound: {@link WeixinInboundPoller} long-polls {@code /ilink/bot/getupdates} on a daemon
 * thread and hands each {@code msgs} element here, where it is deduplicated by
 * {@link IdempotencyStore}, mapped via {@link WeixinInboundMapper} (full media support),
 * throttled by {@link BotLoopGuard}, then routed via {@link ChannelRouter} and executed
 * through the {@link Gateway}. A {@link WeixinTypingIndicator} drives the "正在输入" cue
 * while the agent is reasoning.
 *
 * <p>Outbound: {@link WeixinOutboundClient} resolves each peer's cached {@code context_token}
 * and routes text/image/voice/video/file blocks to the corresponding {@link ILinkClient}
 * send methods.
 *
 * <p>QR login: when no {@code botToken} is supplied via properties, the user scans a QR code
 * through {@link WeixinQrAuthController}; on confirmation the controller invokes
 * {@link #onQrLoginSuccess(String, String)} to update the client credentials and restart
 * the poll loop.
 */
public final class WeixinChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeixinChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "weixin";

    private final String channelId;
    private final ChannelConfig config;
    private final WeixinChannelProperties properties;
    private final ILinkClient client;
    private final WeixinContextTokenStore tokenStore;
    private final WeixinInboundMapper mapper;
    private final WeixinOutboundClient outbound;
    private final WeixinTypingIndicator typing;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final WeixinInboundPoller poller;

    private volatile Gateway gateway;

    private WeixinChannel(
            String channelId,
            ChannelConfig config,
            WeixinChannelProperties properties,
            ILinkClient client,
            WeixinContextTokenStore tokenStore,
            WeixinInboundMapper mapper,
            WeixinOutboundClient outbound,
            WeixinTypingIndicator typing,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = Objects.requireNonNull(client, "client");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.typing = Objects.requireNonNull(typing, "typing");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        // Poller is constructed last so its callback can reference this::onInboundPayload safely.
        this.poller = new WeixinInboundPoller(channelId, client, this::onInboundPayload);
    }

    /** Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static WeixinChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WeixinChannelProperties props = WeixinChannelProperties.from(channelId, rawProperties);
        ObjectMapper objectMapper = new ObjectMapper();
        ILinkClient client = new ILinkClient(props.botToken(), props.baseUrl(), objectMapper);
        WeixinContextTokenStore tokens = new WeixinContextTokenStore();
        WeixinInboundMapper inboundMapper =
                new WeixinInboundMapper(channelId, client, tokens, props);
        WeixinOutboundClient outbound = new WeixinOutboundClient(client, tokens);
        WeixinTypingIndicator typing = new WeixinTypingIndicator(client, channelId);
        return new WeixinChannel(
                channelId,
                routing,
                props,
                client,
                tokens,
                inboundMapper,
                outbound,
                typing,
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()));
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

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
        WeixinChannelRegistry.instance().register(this);
        if (client.getBotToken() != null && !client.getBotToken().isBlank()) {
            poller.start();
        } else {
            log.warn(
                    "[weixin:{}] no botToken configured; poll loop deferred until QR login"
                            + " completes",
                    channelId);
        }
        log.info(
                "[weixin:{}] channel started: baseUrl={}, mediaDownloadEnabled={}",
                channelId,
                client.getBaseUrl(),
                properties.mediaDownloadEnabled());
    }

    @Override
    public void stop() {
        poller.stop();
        typing.shutdown();
        WeixinChannelRegistry.instance().unregister(channelId);
        log.info("[weixin:{}] channel stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WeixinChannel '" + channelId + "' has no gateway"));
        }
        String peerId = message.peer().id();
        String contextToken = tokenStore.get(peerId);
        RouteResult route = router.resolveRoute(config, message);

        // typing.onStart is blocking (HTTP getConfig) — run on boundedElastic before invoking
        // gateway;
        // typing.onStop is also blocking — run async in doFinally so it never blocks the reactive
        // stream.
        return Mono.<Void>fromRunnable(() -> typing.onStart(peerId, contextToken))
                .subscribeOn(Schedulers.boundedElastic())
                .then(
                        g.run(route.context(), message.messages(), route.outboundAddress())
                                .flatMap(
                                        reply ->
                                                sendReply(route.outboundAddress(), reply)
                                                        .thenReturn(reply)))
                .doFinally(
                        sig ->
                                Mono.<Void>fromRunnable(() -> typing.onStop(peerId))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe());
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
                                        "[weixin:{}] deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Poller callback
    // -----------------------------------------------------------------

    private void onInboundPayload(Map<String, Object> payload) {
        String msgId = WeixinInboundMapper.extractMsgId(payload);
        if (msgId != null && !idempotency.firstSeen(channelId + "|" + msgId)) {
            log.debug("[weixin:{}] duplicate msgId={}", channelId, msgId);
            return;
        }
        Optional<InboundMessage> inbound = mapper.map(payload);
        if (inbound.isEmpty()) {
            return;
        }
        InboundMessage in = inbound.get();
        if (!botLoopGuard.allow(in.peer().key())) {
            log.warn(
                    "[weixin:{}] bot-loop guard tripped for peer='{}'", channelId, in.peer().key());
            return;
        }
        dispatch(in)
                .doOnError(
                        err ->
                                log.warn(
                                        "[weixin:{}] dispatch failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  QR login callback (invoked by WeixinQrAuthController)
    // -----------------------------------------------------------------

    /** Updates credentials and restarts the poll loop after a successful QR scan. */
    public void onQrLoginSuccess(String newBotToken, String newBaseUrl) {
        if (newBotToken != null && !newBotToken.isBlank()) {
            client.setBotToken(newBotToken);
        }
        if (newBaseUrl != null && !newBaseUrl.isBlank()) {
            client.setBaseUrl(newBaseUrl);
        }
        log.info("[weixin:{}] QR login confirmed, restarting poll loop", channelId);
        poller.restart();
    }

    /** Package-private accessor for the QR controller. */
    ILinkClient client() {
        return client;
    }

    private Mono<Void> sendReply(OutboundAddress address, Msg reply) {
        if (reply == null) {
            return Mono.empty();
        }
        return outbound.send(address, List.of(reply))
                .doOnError(
                        err ->
                                log.warn(
                                        "[weixin:{}] reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
