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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeComAibotStreamDedupTest {

    private WeComAibotReplyQueue queue;
    private List<Map<String, Object>> sent;
    private WeComAibotOutboundClient outbound;

    @BeforeEach
    void setUp() {
        queue = new WeComAibotReplyQueue("c1", 2000, 2000);
        queue.open();
        sent = new ArrayList<>();
        queue.setFrameSender(
                (inbound, frame) -> {
                    sent.add(frame);
                    String reqId = ((Map<?, ?>) frame.get("headers")).get("req_id").toString();
                    queue.completeAck(reqId, 0, "ok");
                });
        outbound =
                new WeComAibotOutboundClient(
                        "c1", queue, new WeComAibotGroupReplyReqIdCache(), new AtomicLong());
        outbound.setTransport(frame -> {});
    }

    @AfterEach
    void tearDown() {
        queue.closeGate();
        queue.drainAndFailAll("done");
        queue.shutdownExecutor();
    }

    @Test
    void skipsDuplicateNonFinishChunksButAlwaysSendsFinish() {
        outbound.replyStream("req1", "stream1", "思考中...", false);
        outbound.replyStream("req1", "stream1", "思考中...", false); // dedup
        outbound.replyStream("req1", "stream1", "最终回复", true);

        assertEquals(2, sent.size());
    }

    @Test
    void forceBypassesDedupForKeepaliveRefresh() {
        outbound.replyStream("req1", "stream1", "思考中...", false);
        outbound.replyStream("req1", "stream1", "思考中...", false); // dedup
        outbound.replyStream("req1", "stream1", "思考中...", false, true); // keepalive force

        assertEquals(2, sent.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> stream =
                (Map<String, Object>) ((Map<?, ?>) sent.get(1).get("body")).get("stream");
        assertEquals(false, stream.get("finish"));
        assertEquals("思考中...", stream.get("content"));
    }

    @Test
    void emptyReplyStillFinishesProcessingStream() {
        AtomicBoolean keepaliveStopped = new AtomicBoolean();
        outbound.setKeepaliveStopper(sid -> keepaliveStopped.set(true));
        outbound.putReplyContext("user1", "req1", "stream1");
        outbound.replyStream("req1", "stream1", "思考中...", false);
        sent.clear();

        Msg blank = Msg.builder().role(MsgRole.ASSISTANT).textContent("   ").build();
        outbound.send(new OutboundAddress("c1", null, "c1:DIRECT:user1", null), List.of(blank))
                .block();

        assertTrue(keepaliveStopped.get());
        assertEquals(1, sent.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> stream =
                (Map<String, Object>) ((Map<?, ?>) sent.get(0).get("body")).get("stream");
        assertEquals(true, stream.get("finish"));
        assertEquals(WeComAibotOutboundClient.DONE_TEXT, stream.get("content"));
    }

    @Test
    void textReplyStopsKeepaliveBeforeFinish() {
        List<String> order = new ArrayList<>();
        outbound.setKeepaliveStopper(sid -> order.add("stop:" + sid));
        outbound.putReplyContext("user1", "req1", "stream1");

        queue.setFrameSender(
                (inbound, frame) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stream =
                            (Map<String, Object>) ((Map<?, ?>) frame.get("body")).get("stream");
                    if (Boolean.TRUE.equals(stream.get("finish"))) {
                        order.add("finish:" + stream.get("content"));
                    }
                    sent.add(frame);
                    String reqId = ((Map<?, ?>) frame.get("headers")).get("req_id").toString();
                    queue.completeAck(reqId, 0, "ok");
                });

        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        outbound.send(new OutboundAddress("c1", null, "c1:DIRECT:user1", null), List.of(reply))
                .block();

        assertEquals(List.of("stop:stream1", "finish:你好"), order);
    }

    @Test
    void finishProcessingClearsStuckIndicatorOnError() {
        AtomicBoolean keepaliveStopped = new AtomicBoolean();
        outbound.setKeepaliveStopper(sid -> keepaliveStopped.set(true));
        outbound.putReplyContext("user1", "req1", "stream1");
        outbound.replyStream("req1", "stream1", "思考中...", false);
        sent.clear();

        outbound.finishProcessing("user1", WeComAibotOutboundClient.ERROR_TEXT);

        assertTrue(keepaliveStopped.get());
        assertEquals(1, sent.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> stream =
                (Map<String, Object>) ((Map<?, ?>) sent.get(0).get("body")).get("stream");
        assertEquals(true, stream.get("finish"));
        assertEquals(WeComAibotOutboundClient.ERROR_TEXT, stream.get("content"));
        // second call is no-op
        outbound.finishProcessing("user1", "x");
        assertEquals(1, sent.size());
    }

    @Test
    void stoppedKeepaliveTickDoesNotRefresh() {
        WeComAibotKeepalive keepalive = new WeComAibotKeepalive(60, 180);
        AtomicBoolean refreshed = new AtomicBoolean();
        WeComAibotKeepalive.StreamCallbacks cb =
                new WeComAibotKeepalive.StreamCallbacks() {
                    @Override
                    public void refresh(String frameReqId, String streamId, String content) {
                        refreshed.set(true);
                    }

                    @Override
                    public void finish(String frameReqId, String streamId, String content) {}

                    @Override
                    public void invalidateReplyContext(String replyToken, String streamId) {}
                };
        keepalive.start(cb, "req1", "stream1", "token", "思考中...");
        keepalive.stop("stream1");
        assertFalse(keepalive.isActive("stream1"));
        keepalive.tickNow(cb, "stream1");
        assertFalse(refreshed.get());
        keepalive.shutdown();
    }
}
