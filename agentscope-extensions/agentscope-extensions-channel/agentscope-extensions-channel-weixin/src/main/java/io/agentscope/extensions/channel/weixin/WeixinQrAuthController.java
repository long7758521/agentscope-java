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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR scan login endpoints for the WeChat personal (iLink Bot) channel.
 *
 * <p>Unlike Feishu/WeCom callbacks (which receive inbound messages via webhook POSTs),
 * the iLink Bot channel uses long-polling for inbound and only needs HTTP endpoints for the
 * one-time QR-login handshake:
 *
 * <ol>
 *   <li>{@code GET /api/channels/weixin/{channelId}/qrcode} — fetch a fresh login QR code
 *       from iLink ({@code /ilink/bot/get_bot_qrcode?bot_type=3}).</li>
 *   <li>{@code GET /api/channels/weixin/{channelId}/qrcode/status?qrcode=...} — poll scan
 *       status; on {@code confirmed} the channel's bot_token is replaced and the poll loop
 *       is restarted.</li>
 * </ol>
 *
 * <p>The controller relies on the host Spring Boot app's component scan to pick up
 * {@code @RestController}; the extension module itself ships no AutoConfiguration
 * (mirrors the dingtalk/feishu pattern).
 */
@RestController
@RequestMapping("/api/channels/weixin")
public class WeixinQrAuthController {

    private static final Logger log = LoggerFactory.getLogger(WeixinQrAuthController.class);

    private final WeixinChannelRegistry registry;

    public WeixinQrAuthController() {
        this(WeixinChannelRegistry.instance());
    }

    WeixinQrAuthController(WeixinChannelRegistry registry) {
        this.registry = registry;
    }

    /** Fetches a new login QR code from iLink. The response shape is whatever iLink returns
     *  (typically {@code qrcode}, {@code qrcode_img_content} base64 PNG, {@code url}). */
    @GetMapping("/{channelId}/qrcode")
    public ResponseEntity<Map<String, Object>> qrcode(@PathVariable String channelId) {
        WeixinChannel channel = registry.get(channelId);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            Map<String, Object> resp = channel.client().getBotQrcode();
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[weixin:{}] getBotQrcode failed: {}", channelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    /** Polls scan status. On {@code confirmed} updates the channel's bot_token + baseUrl
     *  and restarts the long-poll loop. */
    @GetMapping("/{channelId}/qrcode/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String channelId, @RequestParam String qrcode) {
        WeixinChannel channel = registry.get(channelId);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            Map<String, Object> resp = channel.client().getQrcodeStatus(qrcode);
            Object statusObj = resp.get("status");
            String status = statusObj == null ? "" : statusObj.toString();
            if ("confirmed".equals(status)) {
                Object tokenObj = resp.get("bot_token");
                Object baseUrlObj = resp.get("baseurl");
                String token = tokenObj == null ? null : tokenObj.toString();
                String baseUrl = baseUrlObj == null ? null : baseUrlObj.toString();
                channel.onQrLoginSuccess(token, baseUrl);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[weixin:{}] getQrcodeStatus failed: {}", channelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
