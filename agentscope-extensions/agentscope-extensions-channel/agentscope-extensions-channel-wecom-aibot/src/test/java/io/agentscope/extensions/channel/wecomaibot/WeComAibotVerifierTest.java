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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeComAibotVerifierTest {

    @Test
    void missingCredentialsFail() {
        WeComAibotVerifier.Result r = WeComAibotVerifier.verify(null, "s");
        assertFalse(r.success());
        r = WeComAibotVerifier.verify("b", " ");
        assertFalse(r.success());
    }

    @Test
    void backoffHasMoreAttempts() {
        WeComAibotExponentialBackoff b = new WeComAibotExponentialBackoff(10, 100, 2.0, 2);
        assertTrue(b.hasMoreAttempts());
        b.nextDelayMs();
        assertTrue(b.hasMoreAttempts());
        b.nextDelayMs();
        assertFalse(b.hasMoreAttempts());
        b.reset();
        assertTrue(b.hasMoreAttempts());
    }

    @Test
    void unlimitedAttempts() {
        WeComAibotExponentialBackoff b = new WeComAibotExponentialBackoff(10, 100, 2.0, -1);
        for (int i = 0; i < 20; i++) {
            assertTrue(b.hasMoreAttempts());
            b.nextDelayMs();
        }
    }
}
