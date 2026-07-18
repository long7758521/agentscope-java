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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Approximate LRU cache of the last inbound frame {@code req_id} per group {@code chatid}. Used so
 * group proactive sends can ride {@code aibot_respond_msg} instead of {@code aibot_send_msg}
 * (which the platform rejects for groups).
 */
public final class WeComAibotGroupReplyReqIdCache {

    public static final int DEFAULT_MAX_SIZE = 1000;

    private final int maxSize;
    private final ConcurrentHashMap<String, String> lastChatReqIds = new ConcurrentHashMap<>();

    public WeComAibotGroupReplyReqIdCache() {
        this(DEFAULT_MAX_SIZE);
    }

    public WeComAibotGroupReplyReqIdCache(int maxSize) {
        this.maxSize = maxSize;
    }

    public void remember(String chatId, String frameReqId) {
        if (chatId == null || chatId.isBlank() || frameReqId == null || frameReqId.isBlank()) {
            return;
        }
        lastChatReqIds.put(chatId, frameReqId);
        while (lastChatReqIds.size() > maxSize) {
            var it = lastChatReqIds.keySet().iterator();
            if (it.hasNext()) {
                lastChatReqIds.remove(it.next());
            } else {
                break;
            }
        }
    }

    public String pick(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        return lastChatReqIds.get(chatId);
    }

    public int size() {
        return lastChatReqIds.size();
    }

    public void clear() {
        lastChatReqIds.clear();
    }
}
