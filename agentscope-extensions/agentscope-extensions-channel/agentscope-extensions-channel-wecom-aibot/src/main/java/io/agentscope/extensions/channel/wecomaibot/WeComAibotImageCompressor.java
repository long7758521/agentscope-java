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

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Compresses outbound images larger than ~1.9MB toward WeCom's practical upload limits. */
public final class WeComAibotImageCompressor {

    private static final Logger log = LoggerFactory.getLogger(WeComAibotImageCompressor.class);
    private static final long THRESHOLD_BYTES = (long) (1.9 * 1024 * 1024);

    private WeComAibotImageCompressor() {}

    public static byte[] compressIfNeeded(byte[] original) {
        if (original == null || original.length <= THRESHOLD_BYTES) {
            return original;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                log.warn("WeCom AI Bot image compress: cannot decode image, keeping original");
                return original;
            }
            byte[] best = original;
            float quality = 0.85f;
            for (int i = 0; i < 6; i++) {
                byte[] candidate = encodeJpeg(image, quality);
                if (candidate != null && candidate.length < best.length) {
                    best = candidate;
                }
                if (best.length <= THRESHOLD_BYTES) {
                    return best;
                }
                quality -= 0.12f;
                if (quality < 0.3f) {
                    break;
                }
            }
            // Scale down once if still large.
            int w = Math.max(1, image.getWidth() / 2);
            int h = Math.max(1, image.getHeight() / 2);
            Image scaled = image.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            BufferedImage scaledBuf = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaledBuf.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            byte[] scaledBytes = encodeJpeg(scaledBuf, 0.7f);
            if (scaledBytes != null && scaledBytes.length < best.length) {
                best = scaledBytes;
            }
            if (best.length > THRESHOLD_BYTES) {
                log.warn("WeCom AI Bot image still large after compress: {} bytes", best.length);
            }
            return best;
        } catch (Exception e) {
            log.error("WeCom AI Bot image compress failed: {}", e.getMessage());
            return original;
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));
            }
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb =
                        new BufferedImage(
                                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
