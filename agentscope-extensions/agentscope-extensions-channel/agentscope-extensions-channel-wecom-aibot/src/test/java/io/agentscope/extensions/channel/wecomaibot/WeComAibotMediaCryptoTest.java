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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WeComAibotMediaCryptoTest {

    @Test
    void roundTripDecrypt() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        String aesKey = Base64.getEncoder().encodeToString(key);
        byte[] plain = "hello-wecom-aibot".getBytes();

        // PKCS7 pad
        int pad = 16 - (plain.length % 16);
        byte[] padded = Arrays.copyOf(plain, plain.length + pad);
        Arrays.fill(padded, plain.length, padded.length, (byte) pad);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOf(key, 16)));
        byte[] encrypted = cipher.doFinal(padded);

        byte[] decrypted = WeComAibotMediaCrypto.decryptAes256Cbc(encrypted, aesKey);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    void blankKeyRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WeComAibotMediaCrypto.decryptAes256Cbc(new byte[] {1}, " "));
    }
}
