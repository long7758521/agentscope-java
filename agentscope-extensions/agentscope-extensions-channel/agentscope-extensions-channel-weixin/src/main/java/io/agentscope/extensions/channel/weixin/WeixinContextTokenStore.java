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
 * iLink Bot sendmessage API 的按对端 context_token 缓存。
 *
 * <p>iLink 要求每条出站消息携带 {@code context_token}；该 token 由每条入站消息提供，
 * 仅在该会话上下文中回复有效。本缓存按对端 id（私聊为 {@code from_user_id}，
 * 群聊为 {@code group_id}）保存最近观测到的 token，以支持通过
 * {@code WeixinChannel.dispatch} 同步回复以及通过 {@code WeixinChannel.deliver} 主动推送。
 */
public final class WeixinContextTokenStore {

    private final Map<String, String> peerToToken = new ConcurrentHashMap<>();

    /** 缓存（或替换）给定对端 id 对应的 context_token。 */
    public void put(String peerId, String contextToken) {
        if (peerId == null || contextToken == null || contextToken.isBlank()) {
            return;
        }
        peerToToken.put(peerId, contextToken);
    }

    /** 返回对端对应的缓存 context_token；若尚未观测到则返回 {@code null}。 */
    public String get(String peerId) {
        if (peerId == null) return null;
        return peerToToken.get(peerId);
    }

    public void clear() {
        peerToToken.clear();
    }
}
