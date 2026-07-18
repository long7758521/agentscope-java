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

import java.util.Locale;
import java.util.Set;

/** Pre-upload size / MIME checks aligned with MateClaw {@code applyWeComUploadLimits}. */
public final class WeComAibotUploadLimits {

    public static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 10L * 1024 * 1024;
    public static final long VOICE_MAX_BYTES = 2L * 1024 * 1024;
    public static final long FILE_MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> VOICE_SUPPORTED_MIMES = Set.of("audio/amr");

    private WeComAibotUploadLimits() {}

    public static Decision apply(long fileSize, String mediaType, String contentType) {
        String type = mediaType == null ? "file" : mediaType.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();

        if (fileSize > FILE_MAX_BYTES) {
            return Decision.rejected(
                    String.format(
                            Locale.ROOT,
                            "文件大小 %.2fMB 超过企业微信 20MB 上限",
                            fileSize / (1024.0 * 1024.0)));
        }
        if ("image".equals(type) && fileSize > IMAGE_MAX_BYTES) {
            return Decision.downgraded(
                    "file",
                    String.format(
                            Locale.ROOT,
                            "图片 %.2fMB 超过 10MB 限制，已转为文件形式发送",
                            fileSize / (1024.0 * 1024.0)));
        }
        if ("video".equals(type) && fileSize > VIDEO_MAX_BYTES) {
            return Decision.downgraded(
                    "file",
                    String.format(
                            Locale.ROOT,
                            "视频 %.2fMB 超过 10MB 限制，已转为文件形式发送",
                            fileSize / (1024.0 * 1024.0)));
        }
        if ("voice".equals(type) && !mime.isEmpty() && !VOICE_SUPPORTED_MIMES.contains(mime)) {
            return Decision.downgraded("file", "语音格式 " + mime + " 不支持（企微仅支持 AMR），已转为文件形式发送");
        }
        if ("voice".equals(type) && fileSize > VOICE_MAX_BYTES) {
            return Decision.downgraded(
                    "file",
                    String.format(
                            Locale.ROOT,
                            "语音 %.2fMB 超过 2MB 限制，已转为文件形式发送",
                            fileSize / (1024.0 * 1024.0)));
        }
        return Decision.pass(type);
    }

    public record Decision(Kind kind, String mediaType, String message) {
        public enum Kind {
            PASS,
            DOWNGRADED,
            REJECTED
        }

        public static Decision pass(String mediaType) {
            return new Decision(Kind.PASS, mediaType, null);
        }

        public static Decision downgraded(String mediaType, String message) {
            return new Decision(Kind.DOWNGRADED, mediaType, message);
        }

        public static Decision rejected(String message) {
            return new Decision(Kind.REJECTED, null, message);
        }

        public boolean rejected() {
            return kind == Kind.REJECTED;
        }

        public boolean downgraded() {
            return kind == Kind.DOWNGRADED;
        }
    }
}
