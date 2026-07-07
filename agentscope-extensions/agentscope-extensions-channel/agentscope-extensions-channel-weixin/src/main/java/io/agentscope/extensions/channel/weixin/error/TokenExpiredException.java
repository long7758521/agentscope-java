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
package io.agentscope.extensions.channel.weixin.error;

/**
 * iLink Bot token 已失效时抛出的专用异常。
 *
 * <p>{@code WeixinInboundPoller.pollLoop} 显式 catch 此异常并停止轮询，
 * 等待用户重新扫码登录后由 {@code WeixinQrAuthController} 触发 poller 重启。
 *
 * <p>Ported from mateclaw-server {@code vip.mate.channel.weixin.error.TokenExpiredException}.
 */
public final class TokenExpiredException extends RuntimeException {

    private final String operation;
    private final int httpStatus;
    private final String responseBody;

    public TokenExpiredException(String operation, int httpStatus, String responseBody) {
        super("WeChat bot_token expired (HTTP " + httpStatus + ") during " + operation);
        this.operation = operation;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public String getOperation() {
        return operation;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
