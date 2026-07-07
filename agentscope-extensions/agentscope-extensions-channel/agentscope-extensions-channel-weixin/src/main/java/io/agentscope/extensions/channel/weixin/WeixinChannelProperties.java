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
 * Configuration for a single WeChat personal account (iLink Bot) channel instance.
 *
 * @param botToken iLink Bot bearer token; may be blank at construction time and
 *     supplied later via QR scan login through {@code WeixinQrAuthController}
 * @param baseUrl iLink API base URL; default {@code https://ilinkai.weixin.qq.com}
 * @param mediaDir local directory for persisting downloaded inbound media;
 *     default {@code data/weixin/{channelId}}
 * @param mediaDownloadEnabled whether to download inbound media to disk; default true
 */
public record WeixinChannelProperties(
        String botToken, String baseUrl, String mediaDir, boolean mediaDownloadEnabled) {

    public static final String DEFAULT_BASE_URL = ILinkClient.DEFAULT_BASE_URL;

    public WeixinChannelProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }

    /** Resolves mediaDir against channelId when not explicitly set. */
    public String resolveMediaDir(String channelId) {
        if (mediaDir != null && !mediaDir.isBlank()) {
            return mediaDir;
        }
        return "data/weixin/" + channelId;
    }

    /** Reads a {@link WeixinChannelProperties} out of an arbitrary properties map. */
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
