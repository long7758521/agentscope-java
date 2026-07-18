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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket transport for WeCom AI Bot: connect, {@code aibot_subscribe}, heartbeat, reconnect,
 * frame send/receive, and ordered resource release.
 */
public final class WeComAibotWsClient {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotWsClient.class);

    public static final String CMD_SUBSCRIBE = "aibot_subscribe";
    public static final String CMD_HEARTBEAT = "ping";
    public static final String CMD_CALLBACK = "aibot_msg_callback";
    public static final String CMD_EVENT_CALLBACK = "aibot_event_callback";

    private final String channelId;
    private final ObjectMapper mapper;
    private final WeComAibotReplyQueue replyQueue;
    private final WeComAibotKeepalive keepalive;
    private final AtomicLong reqCounter;
    private final WeComAibotExponentialBackoff backoff;
    private final long heartbeatIntervalMs;
    private final int maxMissedPong;

    private volatile String botId;
    private volatile String secret;
    private volatile String wsUrl;
    private volatile FrameListener frameListener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disconnectInflight = new AtomicBoolean(false);
    private final AtomicInteger missedPong = new AtomicInteger(0);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> uploadAcks =
            new ConcurrentHashMap<>();
    private final StringBuilder textBuffer = new StringBuilder();

    private volatile HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile String lastError;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "wecom-aibot-ws");
                        t.setDaemon(true);
                        return t;
                    });

    public WeComAibotWsClient(
            String channelId,
            ObjectMapper mapper,
            WeComAibotChannelProperties props,
            WeComAibotReplyQueue replyQueue,
            WeComAibotKeepalive keepalive,
            AtomicLong reqCounter) {
        this.channelId = channelId;
        this.mapper = mapper;
        this.replyQueue = replyQueue;
        this.keepalive = keepalive;
        this.reqCounter = reqCounter;
        this.botId = props.botId();
        this.secret = props.secret();
        this.wsUrl = props.wsUrl();
        this.heartbeatIntervalMs = props.heartbeatIntervalMs();
        this.maxMissedPong = props.maxMissedPong();
        this.backoff =
                new WeComAibotExponentialBackoff(2000, 30_000, 2.0, props.maxReconnectAttempts());
        this.httpClient = newHttpClient();
    }

    public void setFrameListener(FrameListener frameListener) {
        this.frameListener = frameListener;
    }

    public void updateCredentials(String botId, String secret) {
        this.botId = botId;
        this.secret = secret;
    }

    public synchronized void start() {
        if (botId == null || botId.isBlank() || secret == null || secret.isBlank()) {
            log.warn(
                    "[wecom-aibot:{}] no credentials; WS connect deferred until credentials are"
                            + " set",
                    channelId);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        connectWebSocket();
    }

    public synchronized void stop() {
        running.set(false);
        cancelReconnect();
        releaseConnectionResources("stopped");
        log.info("[wecom-aibot:{}] WS client stopped", channelId);
    }

    public void sendFrame(Map<String, Object> frame) {
        WebSocket ws = webSocket;
        if (ws == null) {
            log.warn("[wecom-aibot:{}] sendFrame skipped: websocket null", channelId);
            return;
        }
        try {
            String json = mapper.writeValueAsString(frame);
            ws.sendText(json, true);
        } catch (Exception e) {
            log.warn("[wecom-aibot:{}] sendFrame failed: {}", channelId, e.getMessage());
        }
    }

    public CompletableFuture<JsonNode> sendFrameAwaitAck(Map<String, Object> frame) {
        String reqId = extractReqId(frame);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (reqId != null) {
            uploadAcks.put(reqId, future);
        } else {
            future.complete(mapper.nullNode());
            return future;
        }
        sendFrame(frame);
        return future;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isRunning() {
        return running.get();
    }

    WebSocket currentWebSocket() {
        return webSocket;
    }

    private void connectWebSocket() {
        Thread t =
                new Thread(
                        () -> {
                            try {
                                CompletableFuture<WebSocket> future =
                                        httpClient
                                                .newWebSocketBuilder()
                                                .connectTimeout(Duration.ofSeconds(20))
                                                .buildAsync(URI.create(wsUrl), new Listener());
                                WebSocket ws = future.get(20, TimeUnit.SECONDS);
                                this.webSocket = ws;
                                sendAuth();
                            } catch (Exception e) {
                                log.error(
                                        "[wecom-aibot:{}] WS connect failed: {}",
                                        channelId,
                                        e.getMessage());
                                handleFailure("connect failed: " + e.getMessage());
                            }
                        },
                        "wecom-aibot-connect-" + channelId);
        t.setDaemon(true);
        t.start();
    }

    private void sendAuth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bot_id", botId);
        body.put("secret", secret);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", CMD_SUBSCRIBE);
        frame.put("headers", Map.of("req_id", generateReqId(CMD_SUBSCRIBE)));
        frame.put("body", body);
        sendFrame(frame);
    }

    private void markReady() {
        backoff.reset();
        cancelReconnect();
        disconnectInflight.set(false);
        replyQueue.open();
        replyQueue.setFrameSender(
                (inboundReqId, frame) -> {
                    // For respond frames, headers.req_id is the inbound binding id.
                    sendFrame(frame);
                });
        startHeartbeat();
        log.info("[wecom-aibot:{}] authenticated and ready", channelId);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        missedPong.set(0);
        heartbeatFuture =
                scheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                sendHeartbeat();
                            } catch (Exception e) {
                                log.warn(
                                        "[wecom-aibot:{}] heartbeat send error: {}",
                                        channelId,
                                        e.getMessage());
                            }
                        },
                        heartbeatIntervalMs,
                        heartbeatIntervalMs,
                        TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        if (missedPong.get() >= maxMissedPong) {
            stopHeartbeat();
            handleFailure("Heartbeat timeout");
            return;
        }
        missedPong.incrementAndGet();
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", CMD_HEARTBEAT);
        frame.put("headers", Map.of("req_id", generateReqId(CMD_HEARTBEAT)));
        sendFrame(frame);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f != null) {
            f.cancel(false);
        }
        heartbeatFuture = null;
        missedPong.set(0);
    }

    private void handleFailure(String reason) {
        lastError = reason;
        if (!disconnectInflight.compareAndSet(false, true)) {
            log.debug("[wecom-aibot:{}] disconnect already inflight: {}", channelId, reason);
            return;
        }
        if (!running.get()) {
            return;
        }
        log.error("[wecom-aibot:{}] failure: {}", channelId, reason);
        releaseConnectionResources("reconnecting");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (!backoff.hasMoreAttempts()) {
            log.error(
                    "[wecom-aibot:{}] max reconnect attempts exhausted; lastError={}",
                    channelId,
                    lastError);
            return;
        }
        long delay = backoff.nextDelayMs();
        log.info("[wecom-aibot:{}] scheduling reconnect in {}ms", channelId, delay);
        reconnectFuture =
                scheduler.schedule(
                        () -> {
                            if (!running.get()) {
                                return;
                            }
                            doReconnect();
                        },
                        delay,
                        TimeUnit.MILLISECONDS);
    }

    private void doReconnect() {
        releaseConnectionResources("reconnecting");
        httpClient = newHttpClient();
        replyQueue.ensureReplyExecutor();
        disconnectInflight.set(false);
        connectWebSocket();
    }

    private void cancelReconnect() {
        ScheduledFuture<?> f = reconnectFuture;
        if (f != null) {
            f.cancel(false);
        }
        reconnectFuture = null;
    }

    /**
     * Release order (must not change casually):
     * 1) close reply gate → 2) stop heartbeat / close WS → 3) drain reply queues →
     * 4) shutdown reply executor → 5) drain upload acks → 6) keepalive shutdown → 7) httpClient=null
     */
    private void releaseConnectionResources(String reason) {
        replyQueue.closeGate();
        stopHeartbeat();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception ignored) {
                // best effort
            }
        }
        replyQueue.drainAndFailAll("Channel " + reason);
        replyQueue.shutdownExecutor();
        for (CompletableFuture<JsonNode> f : uploadAcks.values()) {
            f.completeExceptionally(new IllegalStateException("Channel " + reason));
        }
        uploadAcks.clear();
        keepalive.shutdownAll();
        if ("stopped".equals(reason) || "reconnecting".equals(reason)) {
            httpClient = null;
        }
    }

    private void onTextFrame(String text) {
        try {
            JsonNode root = mapper.readTree(text);
            String cmd = text(root, "cmd");
            String reqId = text(root.path("headers"), "req_id");

            if (cmd == null || cmd.isBlank()) {
                handleAck(root, reqId);
                return;
            }
            switch (cmd) {
                case CMD_CALLBACK, CMD_EVENT_CALLBACK -> {
                    FrameListener listener = frameListener;
                    if (listener != null) {
                        listener.onFrame(cmd, reqId, root.path("body"));
                    }
                }
                default ->
                        log.debug(
                                "[wecom-aibot:{}] ignoring cmd={} payload={}",
                                channelId,
                                cmd,
                                text.length() > 200 ? text.substring(0, 200) : text);
            }
        } catch (Exception e) {
            log.error("[wecom-aibot:{}] frame parse failed: {}", channelId, e.getMessage());
        }
    }

    private void handleAck(JsonNode root, String reqId) {
        if (reqId == null) {
            return;
        }
        int errcode = root.path("errcode").asInt(0);
        String errmsg = root.path("errmsg").asText("");

        if (reqId.startsWith(CMD_SUBSCRIBE)) {
            if (errcode == 0) {
                markReady();
            } else {
                log.error(
                        "[wecom-aibot:{}] subscribe failed errcode={} errmsg={}",
                        channelId,
                        errcode,
                        errmsg);
                handleFailure("subscribe failed: " + errcode + " " + errmsg);
            }
            return;
        }
        if (reqId.startsWith(CMD_HEARTBEAT)) {
            if (errcode == 0) {
                missedPong.set(0);
            } else {
                log.warn(
                        "[wecom-aibot:{}] ping ACK errcode={} errmsg={}",
                        channelId,
                        errcode,
                        errmsg);
            }
            return;
        }

        CompletableFuture<JsonNode> uploadFuture = uploadAcks.remove(reqId);
        if (uploadFuture != null) {
            if (errcode == 0) {
                uploadFuture.complete(root.path("body").isMissingNode() ? root : root.path("body"));
            } else {
                uploadFuture.completeExceptionally(
                        new RuntimeException("Upload ACK error " + errcode + " " + errmsg));
            }
            return;
        }

        replyQueue.completeAck(reqId, errcode, errmsg);
    }

    private String generateReqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + reqCounter.incrementAndGet();
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    private static String extractReqId(Map<String, Object> frame) {
        Object headers = frame.get("headers");
        if (headers instanceof Map<?, ?> map) {
            Object reqId = map.get("req_id");
            return reqId == null ? null : reqId.toString();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @FunctionalInterface
    public interface FrameListener {
        void onFrame(String cmd, String frameReqId, JsonNode body);
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (WeComAibotWsClient.this.webSocket != webSocket) {
                log.debug("[wecom-aibot:{}] ignore stale onText", channelId);
                return CompletableFuture.completedFuture(null);
            }
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                onTextFrame(payload);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (WeComAibotWsClient.this.webSocket != webSocket) {
                log.debug("[wecom-aibot:{}] ignore stale onClose", channelId);
                return CompletableFuture.completedFuture(null);
            }
            handleFailure("WS closed: " + statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (WeComAibotWsClient.this.webSocket != webSocket) {
                log.debug("[wecom-aibot:{}] ignore stale onError", channelId);
                return;
            }
            handleFailure("WS error: " + (error != null ? error.getMessage() : "unknown"));
        }
    }
}
