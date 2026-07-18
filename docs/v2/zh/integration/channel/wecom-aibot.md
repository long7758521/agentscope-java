# 企业微信智能机器人 Channel（长连接）

`agentscope-extensions-channel-wecom-aibot` 通过企业微信**智能机器人 API 长连接**将 Agent 接入企微。与回调版 [`wecom`](wecom.md)（自建应用加密 HTTP 回调）是**两套独立产品形态**，不要混用配置。

| | `wecom` | `wecom-aibot`（本模块） |
|--|---------|------------------------|
| 产品 | 自建应用 | 智能机器人（API 模式 > 长连接） |
| 凭证 | corpId / agentId / secret / token / encodingAesKey | botId / secret |
| 传输 | HTTP 加密回调 | `wss://openws.work.weixin.qq.com` |
| 扫码授权 | 无 | 由宿主 UI 使用官方 `wecom-aibot-sdk`（本模块不包含扫码 UI） |

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-wecom-aibot</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

在宿主 `ChannelTypeRegistry` 中注册：

```java
register(WeComAibotChannel.TYPE, WeComAibotChannel::fromProperties);
```

`TYPE` 常量为 `"wecom-aibot"`。

## 前置准备

1. 打开 [企业微信管理后台](https://work.weixin.qq.com/)
2. 工作台 → **智能机器人** → 创建机器人 → **API 模式** → **长连接**
3. 取得 **Bot ID** 与 **Secret**（也可在宿主侧用官方 JS SDK 扫码授权后回填）

## 快速开始

```java
WeComAibotChannel channel = WeComAibotChannel.fromProperties(
    "my-aibot",
    ChannelConfig.of("my-aibot", "main"),
    Map.of(
        "botId",  "your-bot-id",
        "secret", "your-secret"
    ));

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(channel)
    .build();
gw.start();
```

延迟填凭证（先建 channel，扫码后再连）：

```java
WeComAibotChannel channel = WeComAibotChannel.fromProperties(
    "my-aibot", ChannelConfig.of("my-aibot", "main"), Map.of());
// start() 仅注册，不连 WS
channel.onCredentialsUpdated(botId, secret); // 热更新并连接
```

凭证预检（可选）：

```java
WeComAibotVerifier.Result r = WeComAibotVerifier.verify(botId, secret);
if (!r.success()) { /* 提示用户 */ }
```

## 配置属性

| 属性 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `botId` / `bot_id` | 运行时必填 | — | 可双缺创建，不可只填其一 |
| `secret` | 同上 | — | |
| `wsUrl` | 否 | `wss://openws.work.weixin.qq.com` | |
| `welcomeText` | 否 | `""` | `enter_chat` 欢迎语 |
| `mediaDownloadEnabled` | 否 | `true` | 入站媒体是否下载解密 |
| `mediaDir` | 否 | `data/wecom-aibot/{channelId}` | |
| `maxReconnectAttempts` | 否 | `8` | `-1` = 无限 |
| `heartbeatIntervalMs` | 否 | `30000` | |
| `maxMissedPong` | 否 | `2` | |
| `replyAckTimeoutMs` | 否 | `5000` | |
| `replyWorkerIdleMs` | 否 | `60000` | |
| `keepaliveRefreshSec` | 否 | `20` | stream 占位续命 |
| `keepaliveMaxSec` | 否 | `180` | 强制结束占位 |

## agentscope.json 示例

```json
{
  "channels": {
    "wecom-bot-1": {
      "type": "wecom-aibot",
      "defaultAgentId": "default",
      "dmScope": "PER_PEER",
      "properties": {
        "botId": "...",
        "secret": "...",
        "welcomeText": "你好，我是助手",
        "mediaDownloadEnabled": true
      }
    }
  }
}
```

## 能力摘要

- 入站：text / image / voice / file / mixed / appmsg + quote；`enter_chat` 欢迎；`template_card_event`（CardHandler SPI）
- 出站：markdown、stream（思考中占位 + 最终覆盖）、welcome、群聊 `lastChatReqIds` → `aibot_respond_msg`
- 媒体：AES-256-CBC 入站解密；出站分块上传（字段必须为 `base64_data`）；上传限制与图片压缩
- 可靠性：心跳、指数退避重连、stale socket 忽略、reply 队列串行与 gate、IdempotencyStore / BotLoopGuard

模板卡片：宿主通过 `channel.cardDispatcher().register(prefix, handler)` 注册；更新卡片须使用**事件帧** `req_id`，并在约 5s 内完成。

## 已知限制

1. 群聊在无 `lastChatReqIds` 缓存时主动推送会降级为 `aibot_send_msg`，可能被平台拒绝。
2. 入站 `video` 未支持（忽略）。
3. 无 bot 自消息专用过滤。
4. 卡片更新依赖平台约 5s 窗口，超时无重试。
5. 同 bot 凭证多进程会重复消费（single-leader）。
6. 扫码授权 UI 不在本模块，由宿主集成官方 `wecom-aibot-sdk`。
