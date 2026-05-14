# Travel Agent ✈️

AI 旅行价格助手，基于 Java 21 + Spring Boot 3 + DeepSeek Function Calling 实现。

手写 ReAct Agent 循环（未使用 LangChain 等框架），具备并行工具调用、Reflexion 自评、多轮对话、长期记忆（Redis）等生产级特性。

## 功能演示

**示例问题：**
> "我想从新加坡飞东京，6月底出发，帮我看看汇率和天气，顺便找最便宜的航班"

**Agent 自动完成：**
1. 并行查询 SGD → JPY 实时汇率 + 东京未来天气
2. 搜索航班价格
3. 计算总旅行费用
4. Reflexion 自评答案质量（1-5分），低于3分自动重写
5. 异步提取用户偏好存入 Redis，下次对话主动应用

## Tech Stack

| 层级 | 技术 |
|------|------|
| 后端 | Java 21 · Spring Boot 3.2 · Maven |
| AI | DeepSeek API（OpenAI 兼容格式，Function Calling） |
| 持久化 | Redis（长期记忆，Docker 部署） |
| 工具 | ExchangeRate-API · Open-Meteo · Mock Flight Search |
| 前端 | 纯 HTML/CSS/JS（Spring Boot static 自动 serve） |

## 架构

```
Browser
  └── POST /api/agent/chat {message, sessionId, userId}
              ↓
      AgentController
              ↓
      AgentService.chat()
        ├── MemoryService.get(userId)      ← Redis 读长期记忆，注入 system prompt
        ├── sessions.computeIfAbsent()     ← 取/建 session 历史（短期记忆）
        │
        └── ReAct 循环（最多8轮）
              ├── compressHistoryIfNeeded() ← 超24条时 LLM 压缩历史
              ├── callDeepSeek()            ← 发请求 + 工具定义
              │
              ├── [有 tool_calls]
              │     └── CompletableFuture.supplyAsync() × N  ← 并行执行工具
              │           ├── ToolService.getExchangeRate()  ← 实时 API + 重试 + 缓存
              │           ├── ToolService.getWeather()       ← 实时 API + 重试
              │           ├── ToolService.searchFlights()    ← Mock 数据
              │           └── ToolService.calculateTripCost() ← 纯计算
              │
              └── [finish_reason=stop]
                    ├── selfEvaluate()     ← LLM 自评 1-5 分
                    │     └── score < 3 → 追加批评重写（最多2次）
                    ├── runAsync()         ← 异步提取记忆写入 Redis
                    └── return AgentResult(answer, toolCallCount, evalScore, memoryUsed)
```

## 快速启动

### 前置要求

- Java 21+
- Maven 3.8+
- DeepSeek API Key（[申请地址](https://platform.deepseek.com/)）
- Docker（运行 Redis）

### 1. 启动 Redis

```bash
docker run -d \
  --name travel-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  -v travel-redis-data:/data \
  redis:7-alpine
```

### 2. 配置 API Key

创建 `src/main/resources/application-local.properties`：

```properties
deepseek.api.key=sk-你的真实key
```

> ⚠️ 此文件已在 `.gitignore` 中，不会上传 GitHub

### 3. 启动服务

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

浏览器打开 `http://localhost:8080` 即可使用。

### 停止服务

```bash
# 停 Spring Boot
lsof -ti:8080 | xargs kill -9

# 停 Redis（数据保留在 Docker Volume）
docker stop travel-redis
```

## API 文档

### 对话

```
POST /api/agent/chat
Content-Type: application/json

{
  "message":   "帮我查新加坡飞东京的汇率和航班",
  "sessionId": "session_abc123",   // 短期对话 ID，新对话时重置
  "userId":    "user_xyz789"       // 长期用户 ID，记忆跨 session 持久
}
```

响应：
```json
{
  "answer":        "综合分析报告...",
  "toolCallCount": 3,
  "evalScore":     4,
  "memoryUsed":    "用户常从新加坡出发，偏好新加坡航空，预算5000新币"
}
```

> `memoryUsed` 非空表示本次注入了长期记忆；前端会显示黄色提示条 🧠

### 其他端点

```
DELETE /api/agent/session/{sessionId}   清空指定对话历史
GET    /api/agent/memory/{userId}       查看用户长期记忆
DELETE /api/agent/memory/{userId}       清除用户长期记忆
GET    /api/agent/health                健康检查
```

## 核心实现亮点

### ReAct 循环
Agent 不是一问一答，而是循环推理：思考 → 调工具 → 观察结果 → 再思考，直到得出完整答案。

### 并行工具调用
多个工具用 `CompletableFuture.supplyAsync()` 并行提交到 ForkJoinPool，延迟从串行的 3N 降到 max(N)。

### Reflexion 自评
答案生成后额外调一次 LLM 打分（1-5），低于 3 分追加批评让 LLM 重写，最多重试 2 次。实现了生成 → 评估 → 反思 → 重新生成的闭环。

### 长期记忆（Redis）
对话结束后异步提取用户偏好，用 `StringRedisTemplate` 写入 Redis（key: `travel:memory:{userId}`，TTL 30天）。新 session 开始时注入 system prompt，LLM 主动应用历史偏好，服务重启不丢失。

### 工具重试 + 缓存降级
汇率工具最多重试 2 次（300ms/600ms 递增），全部失败时返回上次成功的缓存值并标注"缓存汇率"，不让 Agent 因单个工具失败而崩溃。

### 对话历史压缩
超过 24 条消息时，调一次轻量 LLM 把旧消息压缩成 100 字摘要替换，只保留最近 8 条完整历史，防止超出 context window。

## 项目结构

```
src/main/
├── java/com/example/agent/
│   ├── TravelAgentApplication.java     程序入口
│   ├── controller/
│   │   └── AgentController.java        REST 接口层
│   ├── model/
│   │   ├── ChatRequest.java            请求体（message + sessionId + userId）
│   │   └── ChatResponse.java           响应体（answer + toolCallCount + evalScore + memoryUsed）
│   └── service/
│       ├── AgentService.java           ReAct 循环 + 记忆注入 + Reflexion 自评
│       ├── ToolService.java            工具实现（汇率/天气/航班/费用）
│       └── MemoryService.java          长期记忆（Redis 读写，TTL 30天）
└── resources/
    ├── application.properties          默认配置（无密钥）
    ├── application-local.properties    本地密钥（gitignore）
    └── static/
        └── index.html                  前端聊天界面
```
