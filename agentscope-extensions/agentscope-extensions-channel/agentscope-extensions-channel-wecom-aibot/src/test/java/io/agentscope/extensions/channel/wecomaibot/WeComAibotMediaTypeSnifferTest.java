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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeComAibotMediaTypeSnifferTest {

    @Test
    void sniffsJpeg() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        var sniffed = WeComAibotMediaTypeSniffer.sniff(jpeg);
        assertTrue(sniffed.isKnown());
        assertEquals(".jpg", sniffed.extension());
        assertEquals("image/jpeg", sniffed.contentType());
    }

    @Test
    void sniffsPng() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals(".png", WeComAibotMediaTypeSniffer.sniff(png).extension());
    }

    @Test
    void sniffsPdf() {
        byte[] pdf = "%PDF-1.4".getBytes();
        assertEquals(".pdf", WeComAibotMediaTypeSniffer.sniff(pdf).extension());
    }

    @Test
    void needsExtensionFixForBlankAndBin() {
        assertTrue(WeComAibotMediaTypeSniffer.needsExtensionFix(null));
        assertTrue(WeComAibotMediaTypeSniffer.needsExtensionFix(""));
        assertTrue(WeComAibotMediaTypeSniffer.needsExtensionFix("file"));
        assertTrue(WeComAibotMediaTypeSniffer.needsExtensionFix("file.bin"));
        assertFalse(WeComAibotMediaTypeSniffer.needsExtensionFix("image.jpg"));
    }

    @Test
    void withSniffedExtensionReplacesGenericHint() {
        var png = new WeComAibotMediaTypeSniffer.Sniffed(".png", "image/png");
        assertEquals(
                "image.png", WeComAibotMediaTypeSniffer.withSniffedExtension("image.jpg", png));
        assertEquals(
                "image_123.png", WeComAibotMediaTypeSniffer.withSniffedExtension("image_123", png));
    }
}
