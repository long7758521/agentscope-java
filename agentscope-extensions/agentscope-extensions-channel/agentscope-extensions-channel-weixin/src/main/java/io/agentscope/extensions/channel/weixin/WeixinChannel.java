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
 * 微信个人号（iLink Bot）通道适配器。
 *
 * <p>入站：{@link WeixinInboundPoller} 在守护线程上长轮询 {@code /ilink/bot/getupdates}，
 * 并将每个 {@code msgs} 元素交到这里——经 {@link IdempotencyStore} 去重、
 * {@link WeixinInboundMapper} 映射（完整支持媒体）、{@link BotLoopGuard} 限流，
 * 然后由 {@link ChannelRouter} 路由并通过 {@link Gateway} 执行。
 * {@link WeixinTypingIndicator} 在 agent 推理期间驱动"正在输入"提示。
 *
 * <p>出站：{@link WeixinOutboundClient} 解析每个对端缓存的 {@code context_token}，
 * 将 text/image/voice/video/file 块路由到对应的 {@link ILinkClient} 发送方法。
 *
 * <p>扫码登录：当未通过 properties 提供 {@code botToken} 时，用户通过
 * {@link WeixinQrAuthController} 扫码登录；确认后控制器调用
 * {@link #onQrLoginSuccess(String, String)} 更新客户端凭证并重启轮询循环。
 */
public final class WeixinChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeixinChannel.class);

    /** 在 {@code agentscope.json} 和 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 中使用的 {@code type} 值。 */
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
        // Poller 最后构造，以便其回调可以安全引用 this::onInboundPayload。
        this.poller = new WeixinInboundPoller(channelId, client, this::onInboundPayload);
    }

    /** 由 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 使用的工厂方法。 */
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
    //  通道生命周期
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

        // typing.onStart 是阻塞操作（HTTP getConfig）——在调用 gateway 前于 boundedElastic 上执行；
        // typing.onStop 也是阻塞——在 doFinally 中异步执行，确保永不阻塞响应式流。
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
    //  Poller 回调
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
    //  扫码登录回调（由 WeixinQrAuthController 调用）
    // -----------------------------------------------------------------

    /** 扫码成功后更新凭证并重启轮询循环。 */
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

    /** 包级可见的访问器，供 QR 控制器使用。 */
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
