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

import io.agentscope.extensions.channel.weixin.error.TokenExpiredException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-polling daemon thread for iLink Bot inbound messages.
 *
 * <p>Pattern is analogous to {@code DingTalkStreamClient} (a per-channel daemon thread driven
 * by {@code start()}/{@code stop()}), but uses iLink's HTTP long-poll endpoint
 * {@code POST /ilink/bot/getupdates} (server holds the connection up to 35s) instead of a
 * WebSocket. Each returned {@code msgs} element is handed to {@code messageConsumer} for
 * idempotency + mapping + dispatch.
 *
 * <p>On {@link TokenExpiredException} the loop terminates and the channel must wait for the
 * user to re-scan a QR code via {@code WeixinQrAuthController}; the controller will replace
 * the bot token on {@link ILinkClient} and call {@link #restart()} to resume polling.
 */
public final class WeixinInboundPoller {

    private static final Logger log = LoggerFactory.getLogger(WeixinInboundPoller.class);

    /** Backoff between failed poll attempts (non-token errors). */
    private static final long ERROR_BACKOFF_MS = 3000L;

    private final String channelId;
    private final ILinkClient client;
    private final Consumer<Map<String, Object>> messageConsumer;

    private volatile boolean running;
    private String cursor = "";
    private Thread pollThread;

    public WeixinInboundPoller(
            String channelId, ILinkClient client, Consumer<Map<String, Object>> messageConsumer) {
        this.channelId = channelId;
        this.client = client;
        this.messageConsumer = messageConsumer;
    }

    /** Starts the daemon poll thread; no-op if already running. */
    public synchronized void start() {
        if (running) return;
        running = true;
        cursor = "";
        pollThread = new Thread(this::pollLoop, "weixin-poll-" + channelId);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /** Stops the poll thread and interrupts any in-flight long-poll. */
    public synchronized void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** Restarts the poller after a fresh bot_token has been loaded. */
    public synchronized void restart() {
        stop();
        // Brief pause to let the prior socket release.
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        start();
    }

    public boolean isRunning() {
        return running;
    }

    @SuppressWarnings("unchecked")
    private void pollLoop() {
        log.info("[weixin:{}] poll loop started", channelId);
        while (running) {
            try {
                Map<String, Object> resp = client.getUpdates(cursor);
                Object nextCursor = resp.get("get_updates_buf");
                if (nextCursor != null) {
                    cursor = nextCursor.toString();
                }
                Object msgs = resp.get("msgs");
                if (msgs instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            try {
                                messageConsumer.accept((Map<String, Object>) m);
                            } catch (RuntimeException re) {
                                log.warn(
                                        "[weixin:{}] inbound consumer error: {}",
                                        channelId,
                                        re.getMessage());
                            }
                        }
                    }
                }
            } catch (TokenExpiredException te) {
                log.error(
                        "[weixin:{}] bot_token expired (HTTP {}); stopping poll loop — channel"
                                + " needs re-scan",
                        channelId,
                        te.getHttpStatus());
                running = false;
                break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) break;
                log.warn(
                        "[weixin:{}] poll error, retry in {}ms: {}",
                        channelId,
                        ERROR_BACKOFF_MS,
                        e.getMessage());
                try {
                    Thread.sleep(ERROR_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("[weixin:{}] poll loop stopped", channelId);
    }
}
