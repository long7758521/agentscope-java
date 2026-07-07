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
 * iLink Bot 入站消息的长轮询守护线程。
 *
 * <p>模式类似于 {@code DingTalkStreamClient}（按通道启守护线程，由 {@code start()}/{@code stop()}
 * 控制），但使用 iLink 的 HTTP 长轮询端点 {@code POST /ilink/bot/getupdates}
 * （服务端会保持连接最多 35 秒）替代 WebSocket。每返回一个 {@code msgs} 元素就交给
 * {@code messageConsumer} 进行幂等校验 + 映射 + 分发。
 *
 * <p>遇到 {@link TokenExpiredException} 时轮询循环终止，通道需等待用户通过
 * {@code WeixinQrAuthController} 重新扫码；控制器会替换 {@link ILinkClient} 上的 bot token
 * 并调用 {@link #restart()} 恢复轮询。
 */
public final class WeixinInboundPoller {

    private static final Logger log = LoggerFactory.getLogger(WeixinInboundPoller.class);

    /** 轮询失败（非 token 错误）时的退避间隔。 */
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

    /** 启动守护轮询线程；若已在运行则直接返回。 */
    public synchronized void start() {
        if (running) return;
        running = true;
        cursor = "";
        pollThread = new Thread(this::pollLoop, "weixin-poll-" + channelId);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /** 停止轮询线程并中断任何进行中的长轮询。 */
    public synchronized void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** 在加载新的 bot_token 后重启轮询器。 */
    public synchronized void restart() {
        stop();
        // 短暂等待上一个 socket 释放。
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
