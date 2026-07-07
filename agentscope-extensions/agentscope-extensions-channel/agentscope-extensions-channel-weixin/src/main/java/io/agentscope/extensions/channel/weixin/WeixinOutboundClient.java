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
 * Outbound client wrapping {@link ILinkClient} for sending {@link Msg} payloads to WeChat
 * personal accounts via the iLink Bot sendmessage API.
 *
 * <p>Address format expected on {@link OutboundAddress#to()}: {@code "channelId:kind:peerId"}
 * (matches {@code DingTalkOutboundClient.parseAddress}). When the kind segment is absent the
 * peer is treated as {@link PeerKind#DIRECT}.
 *
 * <p>Each {@link Msg}'s {@code content} blocks are dispatched one-by-one to iLink — text
 * blocks become text items; image/video/audio/file blocks are resolved to bytes (from
 * {@link URLSource} via HTTP or {@code file://}, or from {@link Base64Source} via decoding)
 * and routed to the corresponding {@link ILinkClient} send method, which performs AES
 * encryption + CDN upload internally.
 *
 * <p>Because {@link ILinkClient} is blocking, sends are scheduled on
 * {@link Schedulers#boundedElastic()}.
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

    /** Sends each {@code msg} to the resolved peer. */
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

    /** Resolves a {@link Source} to its raw bytes (URL → fetch, file:// → read, Base64 → decode). */
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

    /** Parses {@code "channelId:kind:peerId"} (or {@code "peerId"}) into a {@link PeerTarget}. */
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
