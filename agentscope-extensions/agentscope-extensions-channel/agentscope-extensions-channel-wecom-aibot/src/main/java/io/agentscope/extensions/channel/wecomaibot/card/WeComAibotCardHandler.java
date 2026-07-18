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

/**
 * Host-provided handler for template card clicks. Implementations must finish quickly — WeCom
 * allows roughly a 5s window to call {@code aibot_respond_update_msg} using the <em>event</em>
 * frame {@code req_id}.
 */
@FunctionalInterface
public interface WeComAibotCardHandler {

    /**
     * @return optional update body for {@code aibot_respond_update_msg}, or {@code null} to skip
     *     update
     */
    Object handle(WeComAibotCardEvent event) throws Exception;
}
