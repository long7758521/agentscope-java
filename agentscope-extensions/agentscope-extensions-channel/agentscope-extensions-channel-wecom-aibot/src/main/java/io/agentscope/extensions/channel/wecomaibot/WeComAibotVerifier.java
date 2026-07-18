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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One-shot WebSocket probe that validates {@code bot_id}/{@code secret} via {@code
 * aibot_subscribe}. Intended for host preflight; does not keep a long-lived connection.
 */
public final class WeComAibotVerifier {

    public record Result(boolean success, String message, Integer errcode) {
        public static Result ok() {
            return new Result(true, "ok", 0);
        }

        public static Result failed(String message, Integer errcode) {
            return new Result(false, message, errcode);
        }
    }

    private WeComAibotVerifier() {}

    public static Result verify(String botId, String secret) {
        return verify(botId, secret, WeComAibotChannelProperties.DEFAULT_WS_URL, 5_000L);
    }

    public static Result verify(String botId, String secret, String wsUrl, long ackTimeoutMs) {
        if (botId == null || botId.isBlank() || secret == null || secret.isBlank()) {
            return Result.failed("botId and secret are required", null);
        }
        ObjectMapper mapper = new ObjectMapper();
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        AckWaiter waiter = new AckWaiter();
        WebSocket ws = null;
        try {
            ws =
                    httpClient
                            .newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .buildAsync(URI.create(wsUrl), waiter)
                            .get(7, TimeUnit.SECONDS);

            String reqId = "aibot_subscribe-" + UUID.randomUUID();
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("cmd", "aibot_subscribe");
            frame.put("headers", Map.of("req_id", reqId));
            frame.put("body", Map.of("bot_id", botId, "secret", secret));
            ws.sendText(mapper.writeValueAsString(frame), true)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            JsonNode ack = waiter.awaitAck(ackTimeoutMs);
            int errcode = ack.path("errcode").asInt(-1);
            String errmsg = ack.path("errmsg").asText("");
            if (errcode == 0) {
                return Result.ok();
            }
            return Result.failed(hintFor(errcode, errmsg), errcode);
        } catch (TimeoutException e) {
            return Result.failed("Timed out waiting for subscribe ACK (network/firewall?)", null);
        } catch (Exception e) {
            return Result.failed("Connect/verify failed: " + e.getMessage(), null);
        } finally {
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "verify done");
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private static String hintFor(int errcode, String errmsg) {
        return switch (errcode) {
            case 40001, 40014 -> "Invalid secret (errcode=" + errcode + ")";
            case 40013 -> "Invalid bot/corp id (errcode=" + errcode + ")";
            case 41001 -> "Missing access credential (errcode=" + errcode + ")";
            default -> "Subscribe failed errcode=" + errcode + " errmsg=" + errmsg;
        };
    }

    private static final class AckWaiter implements WebSocket.Listener {
        private final CompletableFuture<JsonNode> ackFuture = new CompletableFuture<>();
        private final StringBuilder buf = new StringBuilder();
        private final ObjectMapper mapper = new ObjectMapper();

        JsonNode awaitAck(long timeoutMs) throws Exception {
            return ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                try {
                    JsonNode root = mapper.readTree(buf.toString());
                    buf.setLength(0);
                    String reqId = root.path("headers").path("req_id").asText("");
                    if (reqId.startsWith("aibot_subscribe")) {
                        ackFuture.complete(root);
                    }
                } catch (Exception e) {
                    // ignore non-JSON; wait for timeout
                    buf.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ackFuture.completeExceptionally(
                    new IllegalStateException(
                            "WS closed before ACK: " + statusCode + " " + reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ackFuture.completeExceptionally(
                    error != null ? error : new IllegalStateException("WS error"));
        }
    }
}
