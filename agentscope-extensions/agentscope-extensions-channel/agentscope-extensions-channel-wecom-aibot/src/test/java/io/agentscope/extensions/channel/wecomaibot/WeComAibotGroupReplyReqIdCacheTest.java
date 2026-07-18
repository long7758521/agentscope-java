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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WeComAibotGroupReplyReqIdCacheTest {

    @Test
    void rememberAndPick() {
        WeComAibotGroupReplyReqIdCache cache = new WeComAibotGroupReplyReqIdCache(2);
        cache.remember("g1", "r1");
        assertEquals("r1", cache.pick("g1"));
        cache.remember("g2", "r2");
        cache.remember("g3", "r3");
        assertEquals(2, cache.size());
        assertNull(cache.pick(null));
    }
}
