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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "正在输入" 提示管理器。
 *
 * <p>对应 iLink Bot 协议：
 * <ol>
 *   <li>{@link ILinkClient#getConfig(String, String)} 获取 {@code typing_ticket}</li>
 *   <li>{@link ILinkClient#sendTyping(String, String, int)} 周期性发送
 *       {@code status=1}（输入中），回复前发送 {@code status=2}（停止输入）</li>
 * </ol>
 *
 * <p>调度模型：每 peer 一个 5s 周期任务，由单线程调度器驱动。
 * {@code onStart(peerId, contextToken)} 在 dispatch 入口调用；
 * {@code onStop(peerId)} 在 {@code doFinally} 中调用，取消周期任务并发送 status=2。
 *
 * <p>所有 HTTP 调用都是阻塞的，必须由调用方在 {@code boundedElastic} 调度器上调度。
 */
public final class WeixinTypingIndicator {

    private static final Logger log = LoggerFactory.getLogger(WeixinTypingIndicator.class);

    /** 输入中刷新周期（秒）。 */
    private static final long REFRESH_INTERVAL_SEC = 5L;

    private final ILinkClient client;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> active = new ConcurrentHashMap<>();
    private final Map<String, String> typingTickets = new ConcurrentHashMap<>();

    public WeixinTypingIndicator(ILinkClient client, String channelId) {
        this.client = client;
        this.scheduler =
                Executors.newScheduledThreadPool(
                        1, new NamedThreadFactory("weixin-typing-" + channelId));
    }

    /**
     * 在 dispatch 入口调用：拉取 typing_ticket 并启动 5s 周期 status=1 刷新。
     *
     * <p>必须由调用方在 {@code boundedElastic} 上执行（含阻塞 HTTP 调用）。
     */
    public void onStart(String peerId, String contextToken) {
        if (peerId == null || peerId.isBlank() || contextToken == null || contextToken.isBlank()) {
            return;
        }
        // 已经在跑就保持原状
        if (active.containsKey(peerId)) {
            return;
        }
        try {
            Map<String, Object> cfg = client.getConfig(peerId, contextToken);
            Object ticketObj = cfg.get("typing_ticket");
            String ticket = ticketObj == null ? null : ticketObj.toString();
            if (ticket == null || ticket.isBlank()) {
                log.debug("[weixin] no typing_ticket for peer {}", peerId);
                return;
            }
            typingTickets.put(peerId, ticket);
            ScheduledFuture<?> f =
                    scheduler.scheduleAtFixedRate(
                            () -> {
                                try {
                                    client.sendTyping(peerId, ticket, 1);
                                } catch (Exception e) {
                                    log.debug(
                                            "[weixin] sendtyping(1) failed for {}: {}",
                                            peerId,
                                            e.getMessage());
                                }
                            },
                            0L,
                            REFRESH_INTERVAL_SEC,
                            TimeUnit.SECONDS);
            active.put(peerId, f);
        } catch (Exception e) {
            log.debug("[weixin] getConfig(typing) failed for {}: {}", peerId, e.getMessage());
        }
    }

    /**
     * 在 dispatch 的 {@code doFinally} 中调用：停止周期刷新并发送 status=2。
     *
     * <p>调用方应在 {@code boundedElastic} 上调度，避免阻塞 reactive 流。
     */
    public void onStop(String peerId) {
        if (peerId == null) return;
        ScheduledFuture<?> f = active.remove(peerId);
        if (f != null) {
            f.cancel(false);
        }
        String ticket = typingTickets.remove(peerId);
        if (ticket != null) {
            try {
                client.sendTyping(peerId, ticket, 2);
            } catch (Exception e) {
                log.debug("[weixin] sendtyping(2) failed for {}: {}", peerId, e.getMessage());
            }
        }
    }

    /** 关闭调度器并清理所有状态。在 {@code Channel.stop()} 中调用。 */
    public void shutdown() {
        for (ScheduledFuture<?> f : active.values()) {
            f.cancel(false);
        }
        active.clear();
        typingTickets.clear();
        scheduler.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
