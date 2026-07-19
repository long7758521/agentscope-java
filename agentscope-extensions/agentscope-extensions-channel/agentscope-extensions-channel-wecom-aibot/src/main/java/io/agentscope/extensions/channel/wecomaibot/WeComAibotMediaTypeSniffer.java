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

/**
 * Best-effort MIME / extension detection from leading file bytes.
 *
 * <p>WeCom inbound media URLs and payloads often omit a reliable filename. Sniffing magic bytes
 * recovers a usable extension so vision models and document tools can route content correctly.
 */
public final class WeComAibotMediaTypeSniffer {

    private WeComAibotMediaTypeSniffer() {}

    /** Sniff result: leading-dot extension plus MIME type. */
    public record Sniffed(String extension, String contentType) {
        public static final Sniffed UNKNOWN = new Sniffed(".bin", "application/octet-stream");

        public boolean isKnown() {
            return !UNKNOWN.equals(this);
        }
    }

    public static Sniffed sniff(byte[] data) {
        if (data == null || data.length < 4) {
            return Sniffed.UNKNOWN;
        }
        if (match(data, 0x25, 0x50, 0x44, 0x46)) {
            return new Sniffed(".pdf", "application/pdf");
        }
        if (match(data, 0x89, 0x50, 0x4E, 0x47)) {
            return new Sniffed(".png", "image/png");
        }
        if (match(data, 0xFF, 0xD8, 0xFF)) {
            return new Sniffed(".jpg", "image/jpeg");
        }
        if (match(data, 0x47, 0x49, 0x46, 0x38)) {
            return new Sniffed(".gif", "image/gif");
        }
        if (match(data, 0x42, 0x4D)) {
            return new Sniffed(".bmp", "image/bmp");
        }
        if (data.length >= 12
                && match(data, 0x52, 0x49, 0x46, 0x46)
                && matchAt(data, 8, 0x57, 0x45, 0x42, 0x50)) {
            return new Sniffed(".webp", "image/webp");
        }
        if (match(data, 0x50, 0x4B, 0x03, 0x04)) {
            return new Sniffed(".zip", "application/zip");
        }
        return Sniffed.UNKNOWN;
    }

    /** True when {@code fileName} has no usable extension (blank, no dot, or {@code .bin}). */
    public static boolean needsExtensionFix(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return true;
        }
        String ext = fileName.substring(dot).toLowerCase();
        return ".bin".equals(ext);
    }

    public static String withSniffedExtension(String fileNameHint, Sniffed sniff) {
        String base =
                fileNameHint == null || fileNameHint.isBlank()
                        ? "file"
                        : stripExtension(fileNameHint);
        if (!sniff.isKnown()) {
            return needsExtensionFix(fileNameHint) ? base + sniff.extension() : fileNameHint;
        }
        return base + sniff.extension();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean match(byte[] data, int... expected) {
        return matchAt(data, 0, expected);
    }

    private static boolean matchAt(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
