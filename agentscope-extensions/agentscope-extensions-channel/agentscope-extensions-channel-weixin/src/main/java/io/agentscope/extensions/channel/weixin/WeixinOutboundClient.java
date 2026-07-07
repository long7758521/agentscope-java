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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 出站客户端，封装 {@link ILinkClient}，通过 iLink Bot sendmessage API 将 {@link Msg}
 * 负载发送到微信个人号。
 *
 * <p>{@link OutboundAddress#to()} 期望的地址格式为 {@code "channelId:kind:peerId"}
 * （与 {@code DingTalkOutboundClient.parseAddress} 一致）。当 kind 段缺失时，
 * 对端被视为 {@link PeerKind#DIRECT}。
 *
 * <p>每个 {@link Msg} 的 {@code content} 块逐个分发到 iLink——文本块转为 text item；
 * image/video/audio/file 块解析为字节（来自 {@link URLSource} 走 HTTP 或 {@code file://}，
 * 或来自 {@link Base64Source} 走解码）后路由到对应的 {@link ILinkClient} 发送方法，
 * 该方法内部执行 AES 加密 + CDN 上传。
 *
 * <p>由于 {@link ILinkClient} 是阻塞的，发送操作调度在
 * {@link Schedulers#boundedElastic()} 上执行。
 */
public final class WeixinOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinOutboundClient.class);

    private static final Duration URL_FETCH_TIMEOUT = Duration.ofSeconds(60);

    private final ILinkClient client;
    private final WeixinContextTokenStore tokenStore;
    private final HttpClient httpDownloader;

    public WeixinOutboundClient(ILinkClient client, WeixinContextTokenStore tokenStore) {
        this.client = client;
        this.tokenStore = tokenStore;
        this.httpDownloader =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 将每条 {@code msg} 发送到解析出的对端。 */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        PeerTarget target = parseAddress(address);
        return Flux.fromIterable(messages)
                .concatMap(msg -> sendOne(target, msg))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> sendOne(PeerTarget target, Msg msg) {
        String peerId = target.id();
        String contextToken = tokenStore.get(peerId);
        if (contextToken == null || contextToken.isBlank()) {
            log.warn("[weixin] No context_token cached for peer {}, skip send", peerId);
            return Mono.empty();
        }
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(
                        () -> {
                            for (ContentBlock block : blocks) {
                                try {
                                    dispatchBlock(peerId, contextToken, block);
                                } catch (RuntimeException re) {
                                    throw re;
                                } catch (Exception e) {
                                    throw new RuntimeException(
                                            "weixin send failed for block "
                                                    + block.getClass().getSimpleName()
                                                    + ": "
                                                    + e.getMessage(),
                                            e);
                                }
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void dispatchBlock(String peerId, String contextToken, ContentBlock block)
            throws Exception {
        if (block instanceof TextBlock t) {
            String text = t.getText();
            if (text != null && !text.isBlank()) {
                client.sendText(peerId, text, contextToken);
            }
        } else if (block instanceof ImageBlock ib) {
            byte[] bytes = resolveBytes(ib.getSource());
            if (bytes != null && bytes.length > 0) {
                client.sendImage(peerId, bytes, contextToken);
            }
        } else if (block instanceof VideoBlock vb) {
            byte[] bytes = resolveBytes(vb.getSource());
            if (bytes != null && bytes.length > 0) {
                client.sendVideo(peerId, bytes, contextToken);
            }
        } else if (block instanceof AudioBlock ab) {
            byte[] bytes = resolveBytes(ab.getSource());
            if (bytes != null && bytes.length > 0) {
                client.sendVoice(peerId, bytes, "voice.mp3", contextToken);
            }
        } else if (block instanceof DataBlock db) {
            byte[] bytes = resolveBytes(db.getSource());
            if (bytes == null || bytes.length == 0) return;
            String name = db.getName() != null ? db.getName() : "file.bin";
            client.sendFile(peerId, bytes, name, contextToken);
        } else {
            log.debug(
                    "[weixin] Unsupported outbound block type {}, skip",
                    block.getClass().getSimpleName());
        }
    }

    /** 将 {@link Source} 解析为原始字节（URL → 拉取，file:// → 读取，Base64 → 解码）。 */
    private byte[] resolveBytes(Source src) throws Exception {
        if (src == null) return null;
        if (src instanceof URLSource u) {
            String url = u.getUrl();
            if (url == null || url.isBlank()) return null;
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
                "[weixin] Unknown source type {}, cannot resolve bytes",
                src.getClass().getSimpleName());
        return null;
    }

    /** 将 {@code "channelId:kind:peerId"}（或 {@code "peerId"}）解析为 {@link PeerTarget}。 */
    static PeerTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("OutboundAddress.to is blank");
        }
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

    record PeerTarget(PeerKind kind, String id) {}
}
