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
 * 微信个人号（iLink Bot）通道的扫码登录端点。
 *
 * <p>与飞书/企微回调（通过 webhook POST 接收入站消息）不同，iLink Bot 通道
 * 入站使用长轮询，仅需要一次性扫码登录握手的 HTTP 端点：
 *
 * <ol>
 *   <li>{@code GET /api/channels/weixin/{channelId}/qrcode} —— 从 iLink 获取新的登录二维码
 *       （{@code /ilink/bot/get_bot_qrcode?bot_type=3}）。</li>
 *   <li>{@code GET /api/channels/weixin/{channelId}/qrcode/status?qrcode=...} —— 轮询扫码状态；
 *       状态为 {@code confirmed} 时替换通道的 bot_token 并重启轮询循环。</li>
 * </ol>
 *
 * <p>该控制器依赖宿主 Spring Boot 应用的组件扫描来识别 {@code @RestController}；
 * 扩展模块本身不提供 AutoConfiguration（与钉钉/飞书模式一致）。
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

    /** 从 iLink 获取新的登录二维码。响应结构由 iLink 返回
     *  （通常为 {@code qrcode}、{@code qrcode_img_content} base64 PNG、{@code url}）。 */
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

    /** 轮询扫码状态。状态为 {@code confirmed} 时更新通道的 bot_token + baseUrl
     *  并重启长轮询循环。 */
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
