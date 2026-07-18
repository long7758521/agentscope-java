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

class WeComAibotUploadLimitsTest {

    @Test
    void rejectsOver20Mb() {
        var d =
                WeComAibotUploadLimits.apply(
                        WeComAibotUploadLimits.FILE_MAX_BYTES + 1, "file", null);
        assertTrue(d.rejected());
    }

    @Test
    void downgradesLargeImageToFile() {
        var d =
                WeComAibotUploadLimits.apply(
                        WeComAibotUploadLimits.IMAGE_MAX_BYTES + 1, "image", "image/jpeg");
        assertTrue(d.downgraded());
        assertEquals("file", d.mediaType());
    }

    @Test
    void downgradesNonAmrVoice() {
        var d = WeComAibotUploadLimits.apply(1024, "voice", "audio/mpeg");
        assertTrue(d.downgraded());
        assertEquals("file", d.mediaType());
    }

    @Test
    void passesNormalImage() {
        var d = WeComAibotUploadLimits.apply(1024, "image", "image/png");
        assertEquals(WeComAibotUploadLimits.Decision.Kind.PASS, d.kind());
        assertEquals("image", d.mediaType());
    }
}
