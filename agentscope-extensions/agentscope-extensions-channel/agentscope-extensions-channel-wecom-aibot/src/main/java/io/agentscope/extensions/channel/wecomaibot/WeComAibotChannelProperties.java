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
import java.util.Objects;

/**
 * Provider-specific configuration for a WeCom intelligent-robot (AI Bot) long-connection channel.
 *
 * <p>Both {@code botId} and {@code secret} may be absent so a host can create the channel first and
 * fill credentials after QR authorization. Supplying exactly one of them is rejected.
 */
public record WeComAibotChannelProperties(
        String botId,
        String secret,
        String wsUrl,
        String welcomeText,
        boolean mediaDownloadEnabled,
        String mediaDir,
        int maxReconnectAttempts,
        long heartbeatIntervalMs,
        int maxMissedPong,
        long replyAckTimeoutMs,
        long replyWorkerIdleMs,
        long keepaliveRefreshSec,
        long keepaliveMaxSec) {

    public static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    public WeComAibotChannelProperties {
        boolean hasBot = botId != null && !botId.isBlank();
        boolean hasSecret = secret != null && !secret.isBlank();
        if (hasBot ^ hasSecret) {
            throw new IllegalArgumentException(
                    "wecom-aibot.botId and wecom-aibot.secret must both be set or both be absent");
        }
        if (botId != null && botId.isBlank()) {
            botId = null;
        }
        if (secret != null && secret.isBlank()) {
            secret = null;
        }
        if (wsUrl == null || wsUrl.isBlank()) {
            wsUrl = DEFAULT_WS_URL;
        }
        if (welcomeText == null) {
            welcomeText = "";
        }
        if (mediaDir == null || mediaDir.isBlank()) {
            mediaDir = "data/wecom-aibot";
        }
        if (maxReconnectAttempts == 0) {
            maxReconnectAttempts = 8;
        }
        if (heartbeatIntervalMs <= 0) {
            heartbeatIntervalMs = 30_000L;
        }
        if (maxMissedPong <= 0) {
            maxMissedPong = 2;
        }
        if (replyAckTimeoutMs <= 0) {
            replyAckTimeoutMs = 5_000L;
        }
        if (replyWorkerIdleMs <= 0) {
            replyWorkerIdleMs = 60_000L;
        }
        if (keepaliveRefreshSec <= 0) {
            keepaliveRefreshSec = 20L;
        }
        if (keepaliveMaxSec <= 0) {
            keepaliveMaxSec = 180L;
        }
    }

    public boolean hasCredentials() {
        return botId != null && !botId.isBlank() && secret != null && !secret.isBlank();
    }

    public WeComAibotChannelProperties withCredentials(String newBotId, String newSecret) {
        return new WeComAibotChannelProperties(
                newBotId,
                newSecret,
                wsUrl,
                welcomeText,
                mediaDownloadEnabled,
                mediaDir,
                maxReconnectAttempts,
                heartbeatIntervalMs,
                maxMissedPong,
                replyAckTimeoutMs,
                replyWorkerIdleMs,
                keepaliveRefreshSec,
                keepaliveMaxSec);
    }

    public static WeComAibotChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultMediaDir = "data/wecom-aibot/" + channelId;
        return new WeComAibotChannelProperties(
                firstString(p, "botId", "bot_id"),
                asString(p, "secret"),
                asStringOr(p, "wsUrl", DEFAULT_WS_URL),
                asStringOr(p, "welcomeText", ""),
                asBool(p, "mediaDownloadEnabled", true),
                asStringOr(p, "mediaDir", defaultMediaDir),
                asInt(p, "maxReconnectAttempts", 8),
                asLong(p, "heartbeatIntervalMs", 30_000L),
                asInt(p, "maxMissedPong", 2),
                asLong(p, "replyAckTimeoutMs", 5_000L),
                asLong(p, "replyWorkerIdleMs", 60_000L),
                asLong(p, "keepaliveRefreshSec", 20L),
                asLong(p, "keepaliveMaxSec", 180L));
    }

    private static String firstString(Map<String, Object> p, String... keys) {
        for (String key : keys) {
            String v = asString(p, key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String asStringOr(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return v == null ? fallback : v.toString();
    }

    private static boolean asBool(Map<String, Object> p, String key, boolean fallback) {
        Object v = p.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static int asInt(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "wecom-aibot." + key + " must be an integer, got: " + v, e);
        }
    }

    private static long asLong(Map<String, Object> p, String key, long fallback) {
        Object v = p.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "wecom-aibot." + key + " must be a long, got: " + v, e);
        }
    }
}
