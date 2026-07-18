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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes WeCom processing stream placeholders every {@code refreshSec} and force-finishes after
 * {@code maxSec}.
 *
 * <p>Call {@link #stop(String)} <b>before</b> sending the final {@code finish=true} stream chunk so
 * a late refresh cannot overwrite the real reply with「思考中...」.
 */
public final class WeComAibotKeepalive {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotKeepalive.class);

    public interface StreamCallbacks {
        void refresh(String frameReqId, String streamId, String content);

        void finish(String frameReqId, String streamId, String content);

        void invalidateReplyContext(String replyToken, String streamId);
    }

    private final long refreshSec;
    private final long maxSec;
    private final ConcurrentHashMap<String, StreamState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "wecom-aibot-keepalive");
                        t.setDaemon(true);
                        return t;
                    });

    public WeComAibotKeepalive(long refreshSec, long maxSec) {
        this.refreshSec = refreshSec;
        this.maxSec = maxSec;
    }

    public void start(
            StreamCallbacks callbacks,
            String frameReqId,
            String streamId,
            String replyToken,
            String content) {
        Objects.requireNonNull(callbacks, "callbacks");
        if (streamId == null || streamId.isBlank()) {
            log.debug("keepalive start ignored: blank streamId");
            return;
        }
        if (states.containsKey(streamId)) {
            log.debug("keepalive start ignored: duplicate streamId={}", streamId);
            return;
        }
        StreamState st =
                new StreamState(
                        frameReqId, streamId, replyToken, content, System.currentTimeMillis());
        ScheduledFuture<?> future =
                scheduler.scheduleAtFixedRate(
                        () -> tick(callbacks, st), refreshSec, refreshSec, TimeUnit.SECONDS);
        st.future = future;
        states.put(streamId, st);
    }

    /**
     * Stop keepalive for a stream — call immediately before sending the real {@code finish=true}
     * reply so the next refresh tick cannot race that chunk.
     */
    public void stop(String streamId) {
        if (streamId == null) {
            return;
        }
        StreamState st = states.remove(streamId);
        if (st != null && st.future != null) {
            // false: do not interrupt an in-flight ACK wait; tick() checks states membership
            st.future.cancel(false);
        }
    }

    public boolean isActive(String streamId) {
        return streamId != null && states.containsKey(streamId);
    }

    public void shutdownAll() {
        for (String id : states.keySet()) {
            stop(id);
        }
        states.clear();
    }

    public void shutdown() {
        shutdownAll();
        scheduler.shutdownNow();
    }

    private void tick(StreamCallbacks callbacks, StreamState st) {
        try {
            // stop() removes the entry first — abort if we were cancelled mid-flight
            if (states.get(st.streamId) != st) {
                return;
            }
            long elapsedSec = (System.currentTimeMillis() - st.startedAt) / 1000L;
            if (elapsedSec >= maxSec) {
                if (states.get(st.streamId) != st) {
                    return;
                }
                callbacks.finish(st.frameReqId, st.streamId, st.content);
                callbacks.invalidateReplyContext(st.replyToken, st.streamId);
                stop(st.streamId);
                return;
            }
            if (states.get(st.streamId) != st) {
                return;
            }
            callbacks.refresh(st.frameReqId, st.streamId, st.content);
        } catch (Exception e) {
            log.debug("keepalive tick failed: {}", e.getMessage());
        }
    }

    /** Test helper: force a tick immediately. */
    void tickNow(StreamCallbacks callbacks, String streamId) {
        StreamState st = states.get(streamId);
        if (st != null) {
            tick(callbacks, st);
        }
    }

    /** Test helper: backdate startedAt. */
    void backdateStartedAt(String streamId, long startedAtMs) {
        StreamState st = states.get(streamId);
        if (st != null) {
            st.startedAt = startedAtMs;
        }
    }

    private static final class StreamState {
        final String frameReqId;
        final String streamId;
        final String replyToken;
        final String content;
        volatile long startedAt;
        volatile ScheduledFuture<?> future;

        StreamState(
                String frameReqId,
                String streamId,
                String replyToken,
                String content,
                long startedAt) {
            this.frameReqId = frameReqId;
            this.streamId = streamId;
            this.replyToken = replyToken;
            this.content = content;
            this.startedAt = startedAt;
        }
    }
}
