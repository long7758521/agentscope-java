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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes outbound frames that share the same inbound {@code req_id}.
 *
 * <p>{@link #replyQueueAccepting} must be {@code true} only after transport auth succeeds
 * ({@code markReady}) and is cleared as the first step of resource release so teardown cannot
 * accept new work while the socket is going away.
 */
public final class WeComAibotReplyQueue {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotReplyQueue.class);

    private final String channelId;
    private final long ackTimeoutMs;
    private final long workerIdleMs;
    private final AtomicBoolean replyQueueAccepting = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, LinkedBlockingQueue<ReplyTask>> queues =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingAcks =
            new ConcurrentHashMap<>();

    private volatile ExecutorService replyExecutor;
    private volatile BiConsumer<String, Map<String, Object>> frameSender;

    public WeComAibotReplyQueue(String channelId, long ackTimeoutMs, long workerIdleMs) {
        this.channelId = channelId;
        this.ackTimeoutMs = ackTimeoutMs;
        this.workerIdleMs = workerIdleMs;
        this.replyExecutor = newReplyExecutor();
    }

    public void setFrameSender(BiConsumer<String, Map<String, Object>> frameSender) {
        this.frameSender = frameSender;
    }

    public void open() {
        ensureReplyExecutor();
        replyQueueAccepting.set(true);
    }

    /**
     * Step 0 of resource release: refuse new enqueue before closing the socket.
     */
    public void closeGate() {
        replyQueueAccepting.set(false);
    }

    public boolean isAccepting() {
        return replyQueueAccepting.get();
    }

    public void registerPendingAck(String reqId, CompletableFuture<Void> future) {
        pendingAcks.put(reqId, future);
    }

    public void completeAck(String reqId, int errcode, String errmsg) {
        CompletableFuture<Void> future = pendingAcks.remove(reqId);
        if (future == null) {
            return;
        }
        if (errcode == 0) {
            future.complete(null);
        } else {
            future.completeExceptionally(
                    new RuntimeException(
                            "Reply ACK error errcode=" + errcode + " errmsg=" + errmsg));
        }
    }

    public boolean completeAckIfPresent(String reqId, CompletableFuture<Void> expected) {
        return pendingAcks.remove(reqId, expected);
    }

    public CompletableFuture<Void> sendFrameWithAck(
            String inboundReqId, Map<String, Object> frame) {
        if (!replyQueueAccepting.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Channel reply queue not accepting"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        ReplyTask task = new ReplyTask(frame, result);
        AtomicBoolean offered = new AtomicBoolean(false);
        queues.compute(
                inboundReqId,
                (key, existing) -> {
                    if (!replyQueueAccepting.get()) {
                        return existing;
                    }
                    LinkedBlockingQueue<ReplyTask> q =
                            existing != null ? existing : new LinkedBlockingQueue<>();
                    boolean wasEmpty = existing == null;
                    if (!q.offer(task)) {
                        return existing;
                    }
                    offered.set(true);
                    if (wasEmpty) {
                        try {
                            replyExecutor.execute(() -> runWorker(key));
                        } catch (RejectedExecutionException e) {
                            q.remove(task);
                            offered.set(false);
                            return null;
                        }
                    }
                    return q;
                });
        if (!offered.get()) {
            result.completeExceptionally(
                    new IllegalStateException("Channel transitioning; reply not accepted"));
        }
        return result;
    }

    public void drainAndFailAll(String reason) {
        for (Map.Entry<String, LinkedBlockingQueue<ReplyTask>> e : queues.entrySet()) {
            LinkedBlockingQueue<ReplyTask> q = e.getValue();
            ReplyTask t;
            while ((t = q.poll()) != null) {
                t.future().completeExceptionally(new IllegalStateException(reason));
            }
        }
        queues.clear();
        for (Map.Entry<String, CompletableFuture<Void>> e : pendingAcks.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException(reason));
        }
        pendingAcks.clear();
    }

    public void shutdownExecutor() {
        ExecutorService exec = replyExecutor;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    public void ensureReplyExecutor() {
        ExecutorService exec = replyExecutor;
        if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            replyExecutor = newReplyExecutor();
        }
    }

    private void runWorker(String inboundReqId) {
        LinkedBlockingQueue<ReplyTask> q = queues.get(inboundReqId);
        if (q == null) {
            return;
        }
        try {
            while (true) {
                ReplyTask task = q.poll(workerIdleMs, TimeUnit.MILLISECONDS);
                if (task == null) {
                    queues.compute(
                            inboundReqId,
                            (key, existing) -> {
                                if (existing == null || existing.isEmpty()) {
                                    return null;
                                }
                                return existing;
                            });
                    if (!queues.containsKey(inboundReqId)) {
                        return;
                    }
                    continue;
                }
                try {
                    BiConsumer<String, Map<String, Object>> sender = frameSender;
                    if (sender == null) {
                        task.future()
                                .completeExceptionally(
                                        new IllegalStateException("No frame sender configured"));
                        continue;
                    }
                    String outReqId = extractReqId(task.frame());
                    CompletableFuture<Void> ackFuture = new CompletableFuture<>();
                    if (outReqId != null) {
                        pendingAcks.put(outReqId, ackFuture);
                    } else {
                        ackFuture.complete(null);
                    }
                    sender.accept(inboundReqId, task.frame());
                    try {
                        ackFuture.orTimeout(ackTimeoutMs, TimeUnit.MILLISECONDS).join();
                        task.future().complete(null);
                    } catch (Exception e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof TimeoutException) {
                            log.debug(
                                    "[wecom-aibot:{}] reply ACK timeout for inboundReqId={}",
                                    channelId,
                                    inboundReqId);
                        }
                        task.future().completeExceptionally(cause);
                    } finally {
                        if (outReqId != null) {
                            pendingAcks.remove(outReqId, ackFuture);
                        }
                    }
                } catch (Exception e) {
                    task.future().completeExceptionally(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractReqId(Map<String, Object> frame) {
        Object headers = frame.get("headers");
        if (headers instanceof Map<?, ?> map) {
            Object reqId = map.get("req_id");
            return reqId == null ? null : reqId.toString();
        }
        return null;
    }

    private static ExecutorService newReplyExecutor() {
        return Executors.newCachedThreadPool(
                r -> {
                    Thread t = new Thread(r, "wecom-aibot-reply");
                    t.setDaemon(true);
                    return t;
                });
    }

    record ReplyTask(Map<String, Object> frame, CompletableFuture<Void> future) {}
}
