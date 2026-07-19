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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;
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

    @Test
    void mapsImageAsPlaceholderWhenDownloadDisabled() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "image");
        body.put("chattype", "single");
        body.put("msgid", "img1");
        body.putObject("from").put("userid", "u1");
        // WeCom image URLs typically have no filename / extension.
        body.putObject("image").put("url", "https://cdn.example.com/media/abc123");

        var mapped = inbound.map("req", body).orElseThrow();
        List<ContentBlock> blocks = mapped.inbound().messages().get(0).getContent();
        // Remote COS URLs must not become ImageBlock (unreadable / breaks text-only models).
        assertTrue(blocks.stream().noneMatch(b -> b instanceof ImageBlock));
        assertTrue(
                blocks.stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .anyMatch(t -> t != null && t.contains("image.jpg")));
    }

    @Test
    void mapsAppmsgMiniprogram() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "appmsg");
        body.putObject("from").put("userid", "u1");
        body.putObject("appmsg").putObject("miniprogram").put("title", "打卡小程序");

        var mapped = inbound.map("req", body).orElseThrow();
        assertEquals("[小程序: 打卡小程序]", mapped.inbound().messages().get(0).getTextContent());
    }

    @Test
    void mapsAppmsgPublicAccountLink() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "appmsg");
        body.putObject("from").put("userid", "u1");
        ObjectNode appmsg = body.putObject("appmsg");
        appmsg.put("title", "某文章");
        appmsg.put("url", "https://mp.weixin.qq.com/s/xyz");

        String text =
                inbound.map("req", body).orElseThrow().inbound().messages().get(0).getTextContent();
        assertTrue(text.contains("[链接]"));
        assertTrue(text.contains("mp.weixin.qq.com"));
        assertTrue(text.contains("请优先请用户粘贴正文"));
    }

    @Test
    void mapsAppmsgFileAsPlaceholderWhenDownloadDisabled() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "appmsg");
        body.putObject("from").put("userid", "u1");
        ObjectNode appmsg = body.putObject("appmsg");
        appmsg.put("title", "report.pdf");
        ObjectNode file = appmsg.putObject("file");
        file.put("url", "https://cdn.example.com/f");
        file.put("filename", "report.pdf");

        var mapped = inbound.map("req", body).orElseThrow();
        List<ContentBlock> blocks = mapped.inbound().messages().get(0).getContent();
        assertTrue(blocks.stream().noneMatch(b -> b instanceof DataBlock));
        assertTrue(
                blocks.stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .anyMatch(t -> t != null && t.contains("report.pdf")));
    }

    @Test
    void mapsQuotePrefix() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "text");
        body.putObject("from").put("userid", "u1");
        body.putObject("text").put("content", "什么意思");
        ObjectNode quote = body.putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "昨日纪要");

        String text =
                inbound.map("req", body).orElseThrow().inbound().messages().get(0).getTextContent();
        assertTrue(text.startsWith("[引用消息: 昨日纪要]"));
        assertTrue(text.contains("什么意思"));
    }

    @Test
    void mapsVoiceAsr() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "voice");
        body.putObject("from").put("userid", "u1");
        body.putObject("voice").put("content", "你好");
        assertEquals(
                "你好",
                inbound.map("req", body)
                        .orElseThrow()
                        .inbound()
                        .messages()
                        .get(0)
                        .getTextContent());
    }
}
