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
 * iLink Bot HTTP 响应的错误分类。
 *
 * <p>sealed 锁定可选分支，便于 JDK 21 pattern switch 穷尽处理；所有子类都是不可变 record。
 * 配合 {@link TokenExpiredException} 让调用方（{@code WeixinInboundPoller.pollLoop}）
 * 能区分"token 已失效（必须重扫码）"与"偶发抖动（可以直接重试）"。
 *
 * <p>Ported from mateclaw-server {@code vip.mate.channel.weixin.error.WeixinClientError}.
 */
public sealed interface WeixinClientError
        permits WeixinClientError.TokenExpired,
                WeixinClientError.BadRequest,
                WeixinClientError.ServerError,
                WeixinClientError.NetworkError,
                WeixinClientError.Unknown {

    int httpStatus();

    String operation();

    /** 当前错误类型是否代表"token 已失效，必须重扫码"。 */
    default boolean isTokenExpired() {
        return this instanceof TokenExpired;
    }

    /** 把本错误升格为运行时异常抛出。 */
    RuntimeException toException();

    /** 401/403 — bot_token 过期或被吊销。 */
    record TokenExpired(int httpStatus, String operation, String responseBody)
            implements WeixinClientError {
        @Override
        public TokenExpiredException toException() {
            return new TokenExpiredException(operation, httpStatus, responseBody);
        }
    }

    /** 400 / 404 / 422 等客户端错误 — 通常无法重试。 */
    record BadRequest(int httpStatus, String operation, String responseBody)
            implements WeixinClientError {
        @Override
        public RuntimeException toException() {
            return new RuntimeException(
                    operation + " failed: HTTP " + httpStatus + " " + truncate(responseBody));
        }
    }

    /** 5xx 服务端错误 — 短暂，可退避后重试。 */
    record ServerError(int httpStatus, String operation, String responseBody)
            implements WeixinClientError {
        @Override
        public RuntimeException toException() {
            return new RuntimeException(
                    operation + " failed: HTTP " + httpStatus + " " + truncate(responseBody));
        }
    }

    /** IO / 连接问题（由调用包装；此处 httpStatus=-1）。 */
    record NetworkError(String operation, String cause) implements WeixinClientError {
        @Override
        public int httpStatus() {
            return -1;
        }

        @Override
        public RuntimeException toException() {
            return new RuntimeException(operation + " network error: " + cause);
        }
    }

    /** 其它意外状态码。 */
    record Unknown(int httpStatus, String operation, String responseBody)
            implements WeixinClientError {
        @Override
        public RuntimeException toException() {
            return new RuntimeException(
                    operation + " failed: HTTP " + httpStatus + " " + truncate(responseBody));
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** 根据 HTTP 状态码构造对应的错误记录。 */
    static WeixinClientError fromStatus(int status, String operation, String body) {
        if (status == 401 || status == 403) return new TokenExpired(status, operation, body);
        if (status >= 400 && status < 500) return new BadRequest(status, operation, body);
        if (status >= 500 && status < 600) return new ServerError(status, operation, body);
        return new Unknown(status, operation, body);
    }
}
