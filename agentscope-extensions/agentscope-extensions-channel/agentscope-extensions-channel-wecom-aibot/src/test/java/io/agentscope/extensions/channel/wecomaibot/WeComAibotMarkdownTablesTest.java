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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeComAibotMarkdownTablesTest {

    @Test
    void padsSimpleTable() {
        String input = "| A | B |\n| - | - |\n| 1 | 22 |";
        String out = WeComAibotMarkdownTables.format(input);
        assertTrue(out.contains("| A "));
        assertTrue(out.contains("| 1 "));
        assertTrue(out.contains("---"));
    }

    @Test
    void leavesCodeFenceUntouched() {
        String input = "```\n| a | b |\n```";
        assertEquals(input, WeComAibotMarkdownTables.format(input));
    }
}
