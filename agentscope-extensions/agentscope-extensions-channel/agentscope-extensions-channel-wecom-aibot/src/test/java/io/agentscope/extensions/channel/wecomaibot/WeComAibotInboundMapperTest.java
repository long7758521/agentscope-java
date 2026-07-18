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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WeComAibotInboundMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WeComAibotInboundMapper inbound =
            new WeComAibotInboundMapper(
                    "c1",
                    WeComAibotChannelProperties.from("c1", Map.of("mediaDownloadEnabled", false)));

    @Test
    void mapsTextDirect() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "text");
        body.put("chattype", "single");
        body.put("msgid", "m1");
        body.putObject("from").put("userid", "u1");
        body.putObject("text").put("content", "hello");

        Optional<WeComAibotInboundMapper.MappedInbound> mapped = inbound.map("req1", body);
        assertTrue(mapped.isPresent());
        assertEquals("u1", mapped.get().replyToken());
        assertEquals("hello", mapped.get().inbound().messages().get(0).getTextContent());
    }

    @Test
    void ignoresVideo() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "video");
        body.putObject("from").put("userid", "u1");
        assertTrue(inbound.map("req1", body).isEmpty());
    }

    @Test
    void emptyTextIgnored() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "text");
        body.putObject("from").put("userid", "u1");
        body.putObject("text").put("content", "   ");
        assertTrue(inbound.map("req1", body).isEmpty());
    }

    @Test
    void mapsGroupPeer() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "text");
        body.put("chattype", "group");
        body.put("chatid", "g1");
        body.put("msgid", "m2");
        body.putObject("from").put("userid", "u1");
        body.putObject("text").put("content", "hi");
        var mapped = inbound.map("req2", body).orElseThrow();
        assertEquals("g1", mapped.replyToken());
        assertEquals("g1", mapped.inbound().peer().id());
        assertEquals("u1", mapped.inbound().senderId());
    }
}
