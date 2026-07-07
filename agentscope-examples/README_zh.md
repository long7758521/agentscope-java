# AgentScope Java 示例

本目录包含演示 AgentScope Java 框架核心功能的示例。

## 🚀 快速开始

### 前置条件

- **JDK 17** 或更高版本
- **Maven 3.6+**
- **DashScope API Key** - 在 https://dashscope.console.aliyun.com/apiKey 获取

### 构建示例

```bash
# 在项目根目录，构建并安装主库
cd agentscope-core-java
mvn clean install

# 构建示例
cd examples
mvn compile
```

### 环境配置

设置你的 API Key（可选 - 如未设置，示例运行时会提示输入）：

```bash
export DASHSCOPE_API_KEY=your_api_key_here
```

## 📚 示例概览

| 示例 | 描述 | 核心概念 | 运行命令 |
|------|------|----------|----------|
| **BasicChatExample** | 最简单的 Agent 对话 | Agent、Model、Memory | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"` |
| **ToolCallingExample** | 为 Agent 配备工具 | @Tool、Toolkit、工具调用 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolCallingExample"` |
| **StructuredOutputExample** | 生成类型化的结构化输出 | 结构化输出、Schema 校验 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.StructuredOutputExample"` |
| **ToolGroupExample** | 自主工具组管理 | 元工具、工具组、自激活 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolGroupExample"` |
| **McpToolExample** | MCP 工具服务器集成 | MCP、外部工具 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.McpToolExample"` |
| **HookExample** | 监控 Agent 执行 | Hook、生命周期回调 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.HookExample"` |
| **StreamingWebExample** | Spring Boot + SSE 流式输出 | Web API、实时流 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.StreamingWebExample"` |
| **SessionExample** | 持久化对话 | JsonSession、状态管理 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.SessionExample"` |
| **InterruptionExample** | Agent 中断机制 | 用户中断、恢复 | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.InterruptionExample"` |

## 📖 详细示例

### 1. BasicChatExample

创建 Agent 并与之对话的最简方式。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"
```

**你将学到：**
- 创建 ReActAgent
- 配置 Model、Memory 和 Formatter
- 交互式对话

**试试问：**
- "你好，介绍一下你自己"
- "你能帮我做什么？"

---

### 2. ToolCallingExample

学习如何让 Agent 使用工具。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolCallingExample"
```

**你将学到：**
- 使用 `@Tool` 注解定义工具
- 将工具注册到 Toolkit
- Agent 自动调用工具

**试试问：**
- "东京现在几点？"
- "计算 123 * 456"
- "搜索'人工智能'"

---

### 3. StructuredOutputExample

从自然语言查询中生成结构化、类型化的输出。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.StructuredOutputExample"
```

**你将学到：**
- 使用 Java 类定义结构化输出 Schema
- 请求 Agent 返回结构化响应
- 提取和校验类型化数据

**工作原理：**
本示例演示三个用例：

1. **产品需求提取**
   - 输入：自然语言产品描述
   - 输出：结构化的 `ProductRequirements` 对象，包含类型、品牌、规格、预算、特性

2. **联系信息提取**
   - 输入：包含联系方式的文本
   - 输出：结构化的 `ContactInfo` 对象，包含姓名、邮箱、电话、公司

3. **情感分析**
   - 输入：客户评论文本
   - 输出：结构化的 `SentimentAnalysis` 对象，包含情感、评分、主题、摘要

**示例输出：**
```
=== Example 1: Product Information ===
Query: I'm looking for a laptop. I need at least 16GB RAM, prefer Apple brand...

Extracted structured data:
  Product Type: laptop
  Brand: Apple
  Min RAM: 16 GB
  Max Budget: $2000.0
  Features: [lightweight, travel-friendly]
```

**核心特性：**
- ✅ 类型安全的数据提取
- ✅ 从 Java 类自动生成 Schema
- ✅ 兼容任何支持工具调用的模型
- ✅ 无需手动解析 JSON

---

### 4. ToolGroupExample

Agent 使用元工具自主管理工具组。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolGroupExample"
```

**你将学到：**
- 创建工具组来组织工具
- Agent 使用 `reset_equipped_tools` 元工具自主激活工具组
- Agent 根据任务需求决定激活哪些工具

**工作原理：**
- 所有工具组初始状态为 **未激活（INACTIVE）**
- Agent 可以访问 `reset_equipped_tools` 元工具
- 当你给 Agent 一个任务时，它会：
  1. 判断需要哪些工具组
  2. 调用 `reset_equipped_tools` 激活对应的工具组
  3. 使用已激活工具组中的工具

**试试以下提示：**

1. **激活单个工具组：**
   ```
   You> Calculate the factorial of 5
   ```
   观察：Agent 激活 `math_ops`，然后使用 `factorial` 工具

2. **激活不同工具组：**
   ```
   You> Ping google.com
   ```
   观察：Agent 激活 `network_ops`，然后使用 `ping` 工具

3. **激活另一个工具组：**
   ```
   You> List files in /tmp
   ```
   观察：Agent 激活 `file_ops`，然后使用 `list_files` 工具

4. **一个任务激活多个工具组：**
   ```
   You> Calculate factorial of 7 and then ping github.com
   ```
   观察：Agent 同时激活 `math_ops` 和 `network_ops`

5. **复杂多组任务：**
   ```
   You> Check if 17 is prime, then list files in /tmp
   ```
   观察：Agent 激活 `math_ops` 和 `file_ops`

本示例演示了**自主工具管理**——Agent 会根据你的请求智能决定启用哪些工具！

---

### 5. McpToolExample

使用模型上下文协议（MCP）连接外部工具服务器。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.McpToolExample"
```

**前置条件：**
安装 MCP 服务器：
```bash
npm install -g @modelcontextprotocol/server-filesystem
```

**你将学到：**
- 连接 MCP 服务器（StdIO、SSE、HTTP）
- 使用 MCP 服务器提供的外部工具
- 交互式 MCP 配置

**试试问：**
- "列出 /tmp 目录下的文件"
- "读取 /tmp/test.txt 的内容"

---

### 6. HookExample

实时监控和拦截 Agent 执行过程。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.HookExample"
```

**你将学到：**
- 完整的 Hook 生命周期回调
- 流式输出监控
- 工具执行追踪
- ToolEmitter 进度更新

**试试问：**
- "Process the customer dataset"

你将看到以下详细日志：
- Agent 启动
- 推理片段（流式）
- 工具调用和结果
- 进度更新
- 执行完成

---

### 7. StreamingWebExample

基于 Spring Boot 的 Web 应用，使用 Server-Sent Events（SSE）实现流式输出。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.StreamingWebExample"
```

**你将学到：**
- 构建带响应式端点的 Spring Boot REST API
- 使用 Server-Sent Events（SSE）实现实时流
- 基于 Hook 的流式响应收集
- Web 环境下的会话持久化

**使用方法：**
启动服务器后，打开浏览器或使用 curl：

```bash
# 简单查询
curl -N "http://localhost:8080/chat?message=Hello"

# 带会话持久化
curl -N "http://localhost:8080/chat?message=What%20is%20AI?&sessionId=my-session"

# 或在浏览器中打开
http://localhost:8080/chat?message=Hello
```

你将看到 Agent 的响应逐字符实时流式输出。

---

### 8. SessionExample

跨运行保持持久化的对话历史。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.SessionExample"
```

**你将学到：**
- 使用 JsonSession 实现持久化
- 保存/加载对话状态
- 会话管理

**试试以下流程：**
```
# 第一次运行
Enter session ID: alice_session
You> My name is Alice and I love pizza

# 第二次运行（使用相同的 session ID）
Enter session ID: alice_session
You> What's my name and what do I like?
Agent> Your name is Alice and you love pizza!
```

---

### 9. InterruptionExample

优雅地中断长时间运行的 Agent 任务。

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.InterruptionExample"
```

**你将学到：**
- 用户主动中断
- 协作式中断机制
- 伪造工具结果生成
- 优雅恢复

本示例通过启动一个长任务并在 2 秒后自动中断来演示中断机制。

---

## 🛠️ 常用操作

### 运行指定示例

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"
```

### 调试示例

添加调试日志：
```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

### 代码格式化

```bash
mvn spotless:apply
```

## 📝 API Key 配置

示例支持两种方式提供 API Key：

1. **环境变量**（推荐）：
   ```bash
   export DASHSCOPE_API_KEY=your_key_here
   mvn exec:java -Dexec.mainClass="..."
   ```

2. **交互式输入**：
   如果未设置环境变量，示例会提示你输入 API Key。

## 🤔 常见问题

### "DASHSCOPE_API_KEY not found"

设置环境变量：
```bash
export DASHSCOPE_API_KEY=sk-xxx
```

或者示例会提示你交互式输入。

### MCP 服务器连接失败

对于 McpToolExample，请确保已安装 MCP 服务器：
```bash
# 文件系统服务器
npm install -g @modelcontextprotocol/server-filesystem

# Git 服务器
npm install -g @modelcontextprotocol/server-git
```

### 编译错误

请确保先构建了主库：
```bash
cd /path/to/agentscope-core-java
mvn clean install
```

## 📚 更多资源

- [AgentScope 文档](https://github.com/modelscope/agentscope)
- [API 参考](../docs/)
- [CLAUDE.md](../CLAUDE.md) - 开发指南

## 💡 贡献

添加新示例时：

1. 每个示例聚焦单一功能
2. 添加清晰的文档和注释
3. 包含交互式配置提示
4. 遵循现有代码风格
5. 提交前运行 `mvn spotless:apply`

## 📄 许可证

Apache License 2.0 - 详见 [LICENSE](../LICENSE)。
