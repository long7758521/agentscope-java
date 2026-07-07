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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide lookup table from {@code channelId} to {@link WeixinChannel}. Used by
 * {@link WeixinQrAuthController} to dispatch QR-login status updates to the correct channel
 * so it can refresh its bot_token and restart the long-poll loop.
 *
 * <p>Mirrors {@code FeishuChannelRegistry} / {@code WeComChannelRegistry}.
 */
public final class WeixinChannelRegistry {

    private static final WeixinChannelRegistry INSTANCE = new WeixinChannelRegistry();

    private final ConcurrentHashMap<String, WeixinChannel> channels = new ConcurrentHashMap<>();

    private WeixinChannelRegistry() {}

    /** Returns the process-wide singleton instance. */
    public static WeixinChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(WeixinChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public WeixinChannel get(String channelId) {
        return channels.get(channelId);
    }
}
