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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WeComAibotChannelPropertiesTest {

    @Test
    void bothAbsentAllowed() {
        WeComAibotChannelProperties p = WeComAibotChannelProperties.from("c1", Map.of());
        assertFalse(p.hasCredentials());
    }

    @Test
    void botIdAliasAccepted() {
        WeComAibotChannelProperties p =
                WeComAibotChannelProperties.from("c1", Map.of("bot_id", "b1", "secret", "s1"));
        assertTrue(p.hasCredentials());
        assertEquals("b1", p.botId());
    }

    @Test
    void halfConfigRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WeComAibotChannelProperties.from("c1", Map.of("botId", "only")));
        assertThrows(
                IllegalArgumentException.class,
                () -> WeComAibotChannelProperties.from("c1", Map.of("secret", "only")));
    }
}
