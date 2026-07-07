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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-peer context_token cache for the iLink Bot sendmessage API.
 *
 * <p>iLink requires a {@code context_token} on every outbound message; the token is supplied
 * by each inbound message and is only valid for replying within that conversation context.
 * This store caches the most recently observed token per peer id (a {@code from_user_id} for
 * DMs, a {@code group_id} for group chats), enabling both synchronous replies via
 * {@code WeixinChannel.dispatch} and proactive pushes via {@code WeixinChannel.deliver}.
 */
public final class WeixinContextTokenStore {

    private final Map<String, String> peerToToken = new ConcurrentHashMap<>();

    /** Caches (or replaces) the context_token observed for the given peer id. */
    public void put(String peerId, String contextToken) {
        if (peerId == null || contextToken == null || contextToken.isBlank()) {
            return;
        }
        peerToToken.put(peerId, contextToken);
    }

    /** Returns the cached context_token for the peer, or {@code null} if none observed yet. */
    public String get(String peerId) {
        if (peerId == null) return null;
        return peerToToken.get(peerId);
    }

    public void clear() {
        peerToToken.clear();
    }
}
