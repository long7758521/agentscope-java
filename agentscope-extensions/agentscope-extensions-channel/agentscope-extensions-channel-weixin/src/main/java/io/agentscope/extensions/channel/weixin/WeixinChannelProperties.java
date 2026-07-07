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
import java.util.Objects;

/**
 * 单个微信个人号（iLink Bot）通道实例的配置。
 *
 * @param botToken iLink Bot bearer token；构造时可为空，稍后通过
 *     {@code WeixinQrAuthController} 扫码登录注入
 * @param baseUrl iLink API 基础 URL；默认 {@code https://ilinkai.weixin.qq.com}
 * @param mediaDir 用于持久化下载的入站媒体的本地目录；
 *     默认 {@code data/weixin/{channelId}}
 * @param mediaDownloadEnabled 是否将入站媒体下载到磁盘；默认 true
 */
public record WeixinChannelProperties(
        String botToken, String baseUrl, String mediaDir, boolean mediaDownloadEnabled) {

    public static final String DEFAULT_BASE_URL = ILinkClient.DEFAULT_BASE_URL;

    public WeixinChannelProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }

    /** 当 mediaDir 未显式设置时，结合 channelId 解析媒体目录。 */
    public String resolveMediaDir(String channelId) {
        if (mediaDir != null && !mediaDir.isBlank()) {
            return mediaDir;
        }
        return "data/weixin/" + channelId;
    }

    /** 从任意 properties map 读取 {@link WeixinChannelProperties}。 */
    public static WeixinChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String botToken = asString(p, "botToken");
        String baseUrl = asString(p, "baseUrl");
        String mediaDir = asString(p, "mediaDir");
        boolean mediaDownloadEnabled = asBool(p, "mediaDownloadEnabled", true);
        return new WeixinChannelProperties(botToken, baseUrl, mediaDir, mediaDownloadEnabled);
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean asBool(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString();
        if (s.isBlank()) return def;
        return Boolean.parseBoolean(s);
    }
}
