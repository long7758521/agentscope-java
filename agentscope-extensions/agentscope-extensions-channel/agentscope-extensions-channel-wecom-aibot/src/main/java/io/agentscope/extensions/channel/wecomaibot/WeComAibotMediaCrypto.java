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

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC decrypt for WeCom AI Bot inbound media ({@code aeskey}), aligned with
 * {@code wecom-aibot-python-sdk crypto_utils.py}.
 */
public final class WeComAibotMediaCrypto {

    private WeComAibotMediaCrypto() {}

    public static byte[] decryptAes256Cbc(byte[] encryptedData, String aesKeyBase64)
            throws Exception {
        if (encryptedData == null || encryptedData.length == 0) {
            throw new IllegalArgumentException("encryptedData is empty");
        }
        if (aesKeyBase64 == null || aesKeyBase64.isBlank()) {
            throw new IllegalArgumentException("aesKeyBase64 is blank");
        }
        int padCount = (4 - aesKeyBase64.length() % 4) % 4;
        byte[] keyBytes = Base64.getDecoder().decode(aesKeyBase64 + "=".repeat(padCount));
        byte[] iv = Arrays.copyOf(keyBytes, 16);

        int blockSize = 16;
        int remainder = encryptedData.length % blockSize;
        byte[] cipherBytes = encryptedData;
        if (remainder != 0) {
            cipherBytes =
                    Arrays.copyOf(encryptedData, encryptedData.length + (blockSize - remainder));
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(cipherBytes);

        int padLen = decrypted[decrypted.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 32 || padLen > decrypted.length) {
            throw new IllegalArgumentException("Invalid PKCS#7 padding length: " + padLen);
        }
        for (int i = 0; i < padLen; i++) {
            if ((decrypted[decrypted.length - 1 - i] & 0xFF) != padLen) {
                throw new IllegalArgumentException("Invalid PKCS#7 padding bytes");
            }
        }
        return Arrays.copyOf(decrypted, decrypted.length - padLen);
    }
}
