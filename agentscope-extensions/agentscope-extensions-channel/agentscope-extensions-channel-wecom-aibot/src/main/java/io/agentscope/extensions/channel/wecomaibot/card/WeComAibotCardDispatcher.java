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
package io.agentscope.extensions.channel.wecomaibot.card;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes {@code template_card_event} to registered handlers by {@code task_id} prefix / kind.
 *
 * <p>Does not bind MateClaw ApprovalService — hosts register their own {@link WeComAibotCardHandler}.
 */
public final class WeComAibotCardDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotCardDispatcher.class);

    private final ConcurrentHashMap<String, WeComAibotCardHandler> handlersByPrefix =
            new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Map<String, Object>> updateSender;

    public void setUpdateSender(BiConsumer<String, Map<String, Object>> updateSender) {
        this.updateSender = updateSender;
    }

    /** Registers a handler for task ids that start with {@code taskIdPrefix}. */
    public void register(String taskIdPrefix, WeComAibotCardHandler handler) {
        handlersByPrefix.put(taskIdPrefix, handler);
    }

    public void unregister(String taskIdPrefix) {
        handlersByPrefix.remove(taskIdPrefix);
    }

    public void dispatch(WeComAibotCardEvent event) {
        if (event == null) {
            return;
        }
        String taskId = event.taskId();
        if (taskId == null || taskId.isBlank()) {
            log.debug("template_card_event ignored: missing task_id");
            return;
        }
        WeComAibotCardHandler handler = findHandler(taskId);
        if (handler == null) {
            log.warn("template_card_event ignored: no handler for task_id={}", taskId);
            return;
        }
        try {
            Object updateBody = handler.handle(event);
            if (updateBody != null && updateSender != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body =
                        updateBody instanceof Map
                                ? (Map<String, Object>) updateBody
                                : Map.of(
                                        "msgtype",
                                        "update_template_card",
                                        "update_template_card",
                                        updateBody);
                updateSender.accept(event.frameReqId(), body);
            }
        } catch (Exception e) {
            log.error(
                    "template_card_event handler failed for task_id={}: {}",
                    taskId,
                    e.getMessage());
        }
    }

    private WeComAibotCardHandler findHandler(String taskId) {
        WeComAibotCardHandler exact = handlersByPrefix.get(taskId);
        if (exact != null) {
            return exact;
        }
        WeComAibotCardHandler best = null;
        int bestLen = -1;
        for (Map.Entry<String, WeComAibotCardHandler> e : handlersByPrefix.entrySet()) {
            String prefix = e.getKey();
            if (taskId.startsWith(prefix) && prefix.length() > bestLen) {
                best = e.getValue();
                bestLen = prefix.length();
            }
        }
        return best;
    }
}
