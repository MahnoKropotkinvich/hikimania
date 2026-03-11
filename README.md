<b><font size=50> !!UNDER CONSTRUCTION!! </font></b>  

# Hikimania

基于 Kotlin + Amper 的混沌极简 QQ VTuber 机器人，使用 Claude API 理解群友偏好并生成个性化回复。

## 架构

参考 x-algorithm 的 pipeline 设计：

- **Thunder (消息摄取)** → NapCat WebSocket 接收群聊消息
- **User Action Sequence** → RocksDB 存储每个用户的消息历史
- **Phoenix (理解 & 排序)** → Claude API 分析用户偏好、生成回复
- **Home Mixer (编排)** → 主循环协调上述流程

## 快速开始

### 方式 1：Docker Compose（推荐）

1. **配置环境变量**

```bash
cp .env.example .env
# 编辑 .env，填入 Claude API Key
# BOT_QQ 先留空，登录后再填
```

2. **启动服务**

```bash
docker-compose up -d
```

3. **登录 QQ**

- 访问 `http://localhost:6099`
- 扫码或密码登录你的 QQ 机器人账号
- 登录成功后，查看日志获取 QQ 号：`docker-compose logs napcat`
- 把 QQ 号填入 `.env` 的 `BOT_QQ`，然后重启：`docker-compose restart hikimania`

4. **查看日志**

```bash
docker-compose logs -f hikimania
```

### 方式 2：本地开发

1. **安装依赖**

需要 JDK 21+ 和 Amper wrapper（已包含在项目中）。

2. **配置**

```bash
cp config.example.json config.json
# 编辑 config.json，填入配置
```

3. **运行**

```bash
./amper run
```

## 配置说明

配置优先级：**环境变量 > config.main.kts**

### 方式 1：Kotlin DSL（本地开发推荐）

```bash
cp config.example.kts config.main.kts
# 编辑 config.main.kts
```

```kotlin
Config(
    napcatWSUrl = "ws://127.0.0.1:3001",
    claudeAPIUrl = "https://crs2.itssx.com/api/v1",
    claudeAPIKey = "your_key",
    claudeModel = "claude-sonnet-4-6",
    botQQ = 123456789L,
)
```

### 方式 2：环境变量（Docker 推荐）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `BOT_QQ` | 机器人 QQ 号（登录后填入） | 必填 |
| `CLAUDE_API_KEY` | Claude API Key | 必填 |
| `CLAUDE_API_URL` | Claude API 地址 | `https://api.anthropic.com` |
| `CLAUDE_MODEL` | Claude 模型 | `claude-sonnet-4-6` |
| `NAPCAT_WS_URL` | NapCat WebSocket 地址 | `ws://127.0.0.1:3001` |
| `HEALTH_CHECK_TIMEOUT_MS` | 健康检查超时（毫秒） | `10000` |

## 功能

- **自动学习用户偏好**：每 20 条消息自动调用 Claude 更新用户画像
- **个性化回复**：被 @ 时根据用户画像生成针对性回复
- **群聊上下文感知**：记录最近 20 条群聊消息作为回复上下文
- **断线重连**：WebSocket 断开后自动重连

## 项目结构

```
src/
├── main.kt          # 入口
├── Bot.kt           # 主循环
├── Config.kt        # 配置读取
├── UserProfile.kt   # RocksDB 用户画像
├── Claude.kt        # Anthropic API
└── Napcat.kt        # OneBot 11 WebSocket
```

5 个文件，不到 300 行代码，零架构模式。

## 技术栈

- **Kotlin** + **Amper** (构建工具)
- **Ktor Client** (HTTP + WebSocket)
- **RocksDB** (用户画像存储)
- **kotlinx.serialization** (JSON)
- **kotlin-logging** (日志)
- **NapCatQQ** (OneBot 11 协议)
- **Claude API** (LLM)
