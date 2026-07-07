/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.weixin;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信 iLink Bot 媒体文件 AES-128-ECB 加解密工具。
 *
 * <p>CDN 上的媒体文件使用 AES-128-ECB + PKCS5Padding 加密。
 * key 有三种格式：
 * <ul>
 *   <li>Hex 字符串（32 chars = 16 bytes），如 image_item.aeskey</li>
 *   <li>Base64 编码的原始 16 字节，如 media.aes_key (Format A)</li>
 *   <li>Base64 编码的 hex 字符串，如 media.aes_key (Format B)</li>
 * </ul>
 *
 * <p>Ported from mateclaw-server {@code vip.mate.channel.weixin.WeixinAesUtil}.
 */
public final class WeixinAesUtil {

    private WeixinAesUtil() {}

    /**
     * AES-128-ECB 解密（自动识别 key 格式）。
     *
     * @param data      加密数据
     * @param keyParam  AES key（hex / base64 / raw）
     * @return 解密后的数据
     */
    public static byte[] aesEcbDecrypt(byte[] data, String keyParam) throws Exception {
        byte[] key = parseAesKey(keyParam);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    /**
     * 自动识别并解析 AES key。
     *
     * <ol>
     *   <li>如果是 32/48/64 位纯 hex 字符串 → 直接 hex decode</li>
     *   <li>否则 Base64 decode，如果结果是 16 字节 → 直接用（Format A）</li>
     *   <li>如果 Base64 decode 结果是 32 字节纯 hex → 再 hex decode（Format B）</li>
     * </ol>
     */
    static byte[] parseAesKey(String keyParam) {
        String raw = keyParam.strip();

        // Format: raw hex string (e.g. image_item.aeskey — 32 hex chars = 16 bytes)
        if (isHex(raw) && (raw.length() == 32 || raw.length() == 48 || raw.length() == 64)) {
            return hexToBytes(raw);
        }

        // Format: base64-encoded
        byte[] decoded;
        try {
            String padded = raw;
            while (padded.length() % 4 != 0) {
                padded += "=";
            }
            decoded = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            decoded = raw.getBytes();
        }

        if (decoded.length == 16) {
            // Format A: base64(raw 16 bytes)
            return decoded;
        }

        if (decoded.length == 32 && isHex(new String(decoded))) {
            // Format B: base64(hex string)
            return hexToBytes(new String(decoded));
        }

        // Fallback: use as-is
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalArgumentException("Invalid AES key length: " + decoded.length);
        }
        return decoded;
    }

    /**
     * AES-128-ECB 加密（用于上传媒体文件到 CDN）。
     *
     * @param data       明文数据
     * @param keyBase64  AES key（Base64 编码的原始 16 字节）
     * @return 加密后的数据（含 PKCS5 填充）
     */
    public static byte[] aesEcbEncrypt(byte[] data, String keyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    /** 生成随机 AES-128 密钥（16 字节，Base64 编码）。 */
    public static String generateAesKeyBase64() {
        byte[] key = new byte[16];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte)
                            ((Character.digit(hex.charAt(i), 16) << 4)
                                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
