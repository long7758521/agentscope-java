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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunked media upload via {@code aibot_upload_media_*} commands. Uses {@code base64_data} (not
 * {@code data}) for chunk payloads.
 */
public final class WeComAibotMediaUploader {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotMediaUploader.class);

    public static final int UPLOAD_CHUNK_SIZE = 512 * 1024;
    public static final long UPLOAD_ACK_TIMEOUT_MS = 30_000L;

    private final String channelId;
    private final ObjectMapper mapper;
    private final AtomicLong reqCounter;
    private final Semaphore uploadLock = new Semaphore(1);
    private final Function<Map<String, Object>, CompletableFuture<JsonNode>> sendAndAwaitAck;

    public WeComAibotMediaUploader(
            String channelId,
            ObjectMapper mapper,
            AtomicLong reqCounter,
            Function<Map<String, Object>, CompletableFuture<JsonNode>> sendAndAwaitAck) {
        this.channelId = channelId;
        this.mapper = mapper;
        this.reqCounter = reqCounter;
        this.sendAndAwaitAck = sendAndAwaitAck;
    }

    /**
     * @return media_id or null on failure
     */
    public String upload(byte[] fileBytes, String mediaType, String fileName, String contentType) {
        if (fileBytes == null || fileBytes.length == 0) {
            return null;
        }
        WeComAibotUploadLimits.Decision decision =
                WeComAibotUploadLimits.apply(fileBytes.length, mediaType, contentType);
        if (decision.rejected()) {
            log.warn("[wecom-aibot:{}] upload rejected: {}", channelId, decision.message());
            return null;
        }
        String type = decision.downgraded() ? decision.mediaType() : mediaType;
        if (decision.downgraded()) {
            log.info("[wecom-aibot:{}] {}", channelId, decision.message());
        }
        if ("image".equalsIgnoreCase(type)) {
            fileBytes = WeComAibotImageCompressor.compressIfNeeded(fileBytes);
        }

        int totalChunks = (int) Math.ceil(fileBytes.length / (double) UPLOAD_CHUNK_SIZE);
        if (totalChunks > 100) {
            log.warn(
                    "[wecom-aibot:{}] upload rejected: too many chunks ({})",
                    channelId,
                    totalChunks);
            return null;
        }

        boolean locked = false;
        try {
            locked = uploadLock.tryAcquire(60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("[wecom-aibot:{}] upload lock timeout", channelId);
                return null;
            }

            String md5 = md5Hex(fileBytes);
            Map<String, Object> initBody = new LinkedHashMap<>();
            initBody.put("type", type);
            initBody.put("filename", fileName != null ? fileName : "file");
            initBody.put("total_size", fileBytes.length);
            initBody.put("total_chunks", totalChunks);
            initBody.put("md5", md5);
            JsonNode initAck =
                    sendAndAwaitAck
                            .apply(frame("aibot_upload_media_init", initBody))
                            .orTimeout(UPLOAD_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .join();
            String uploadId = text(initAck, "upload_id");
            if (uploadId == null || uploadId.isBlank()) {
                // some ACKs nest under body
                uploadId = text(initAck.path("body"), "upload_id");
            }
            if (uploadId == null || uploadId.isBlank()) {
                log.error("[wecom-aibot:{}] upload init missing upload_id", channelId);
                return null;
            }

            for (int i = 0; i < totalChunks; i++) {
                int from = i * UPLOAD_CHUNK_SIZE;
                int to = Math.min(fileBytes.length, from + UPLOAD_CHUNK_SIZE);
                byte[] chunk = new byte[to - from];
                System.arraycopy(fileBytes, from, chunk, 0, chunk.length);
                Map<String, Object> chunkBody = new LinkedHashMap<>();
                chunkBody.put("upload_id", uploadId);
                chunkBody.put("chunk_index", i);
                chunkBody.put("base64_data", Base64.getEncoder().encodeToString(chunk));
                sendAndAwaitAck
                        .apply(frame("aibot_upload_media_chunk", chunkBody))
                        .orTimeout(UPLOAD_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .join();
            }

            Map<String, Object> finishBody = Map.of("upload_id", uploadId);
            JsonNode finishAck =
                    sendAndAwaitAck
                            .apply(frame("aibot_upload_media_finish", finishBody))
                            .orTimeout(UPLOAD_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .join();
            String mediaId = text(finishAck, "media_id");
            if (mediaId == null || mediaId.isBlank()) {
                mediaId = text(finishAck.path("body"), "media_id");
            }
            if (mediaId == null || mediaId.isBlank()) {
                log.error("[wecom-aibot:{}] upload finish missing media_id", channelId);
                return null;
            }
            return mediaId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[wecom-aibot:{}] upload interrupted", channelId);
            return null;
        } catch (Exception e) {
            log.error("[wecom-aibot:{}] upload failed: {}", channelId, e.getMessage());
            return null;
        } finally {
            if (locked) {
                uploadLock.release();
            }
        }
    }

    private Map<String, Object> frame(String cmd, Map<String, Object> body) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", cmd);
        frame.put("headers", Map.of("req_id", generateReqId(cmd)));
        frame.put("body", body);
        return frame;
    }

    private String generateReqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + reqCounter.incrementAndGet();
    }

    private static String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
