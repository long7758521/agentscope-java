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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WeComAibotKeepaliveTest {

    @Test
    void forceFinishAfterMaxDuration() throws Exception {
        WeComAibotKeepalive keepalive = new WeComAibotKeepalive(1, 2);
        AtomicInteger finishCount = new AtomicInteger();
        AtomicReference<String> invalidated = new AtomicReference<>();
        WeComAibotKeepalive.StreamCallbacks cb =
                new WeComAibotKeepalive.StreamCallbacks() {
                    @Override
                    public void refresh(String frameReqId, String streamId, String content) {}

                    @Override
                    public void finish(String frameReqId, String streamId, String content) {
                        finishCount.incrementAndGet();
                    }

                    @Override
                    public void invalidateReplyContext(String replyToken, String streamId) {
                        invalidated.set(replyToken + "|" + streamId);
                    }
                };
        keepalive.start(cb, "req1", "stream1", "token1", "思考中...");
        keepalive.backdateStartedAt("stream1", System.currentTimeMillis() - 3000);
        keepalive.tickNow(cb, "stream1");
        assertEquals(1, finishCount.get());
        assertEquals("token1|stream1", invalidated.get());
        keepalive.shutdown();
    }

    @Test
    void duplicateStartIgnored() {
        WeComAibotKeepalive keepalive = new WeComAibotKeepalive(60, 180);
        AtomicInteger refresh = new AtomicInteger();
        WeComAibotKeepalive.StreamCallbacks cb =
                new WeComAibotKeepalive.StreamCallbacks() {
                    @Override
                    public void refresh(String frameReqId, String streamId, String content) {
                        refresh.incrementAndGet();
                    }

                    @Override
                    public void finish(String frameReqId, String streamId, String content) {}

                    @Override
                    public void invalidateReplyContext(String replyToken, String streamId) {}
                };
        keepalive.start(cb, "r", "s", "t", "x");
        keepalive.start(cb, "r", "s", "t", "x");
        assertTrue(keepalive.isActive("s"));
        keepalive.stop("s");
        assertFalse(keepalive.isActive("s"));
        keepalive.shutdown();
    }
}
