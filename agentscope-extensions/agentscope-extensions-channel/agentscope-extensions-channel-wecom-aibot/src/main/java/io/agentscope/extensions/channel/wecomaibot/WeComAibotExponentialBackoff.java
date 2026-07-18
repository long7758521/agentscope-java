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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exponential reconnect backoff. {@code maxAttempts == -1} means unlimited attempts.
 *
 * <p>Default WeCom profile: initial 2s, max 30s, factor 2.0.
 */
public final class WeComAibotExponentialBackoff {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double factor;
    private final int maxAttempts;
    private final AtomicInteger attempts = new AtomicInteger(0);

    public WeComAibotExponentialBackoff(
            long initialDelayMs, long maxDelayMs, double factor, int maxAttempts) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.factor = factor;
        this.maxAttempts = maxAttempts;
    }

    public boolean hasMoreAttempts() {
        if (maxAttempts < 0) {
            return true;
        }
        return attempts.get() < maxAttempts;
    }

    public long nextDelayMs() {
        int attempt = attempts.getAndIncrement();
        long base = (long) (initialDelayMs * Math.pow(factor, attempt));
        return Math.min(base, maxDelayMs);
    }

    public void reset() {
        attempts.set(0);
    }

    public int attemptCount() {
        return attempts.get();
    }
}
