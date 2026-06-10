# AI 助理 v2.0 技术文档

## 1. 项目概述

**AI 助理**是一个基于 Spring Boot 3 的个人 AI 连接器，提供 Web UI + 飞书机器人双入口的 AI 对话、日报生成、客户管理、自媒体运营分析、笔记浏览等功能。聊天记录存储在 SQLite 中（通过 MyBatis-Plus 操作），业务数据存储在文件系统中（JSON + Markdown）。

- **版本**: 2.0.0
- **端口**: 6790
- **语言**: Java 17
- **框架**: Spring Boot 3.3.13 + Thymeleaf
- **数据层**: MyBatis-Plus 3.5.16 + SQLite
- **构建工具**: Maven

## 2. 技术栈

| 层次 | 技术 | 用途 |
|------|------|------|
| 后端框架 | Spring Boot 3.3.13 | Web 服务、DI、定时任务 |
| ORM | MyBatis-Plus 3.5.16 (mybatis-plus-spring-boot3-starter) | SQLite 数据库操作 |
| 数据库 | SQLite (sqlite-jdbc 3.47.0.0) | 聊天记录持久化 |
| 模板引擎 | Thymeleaf 3.3.0 + layout-dialect | 服务端渲染 HTML |
| JSON | Jackson (spring-boot-starter-json) | 序列化/反序列化 |
| Markdown | commonmark 0.24.0 + gfm-tables | Markdown 转 HTML |
| WebSocket | Java-WebSocket 1.6.0 | 飞书长连接客户端 |
| 飞书 SDK | larksuite oapi 2.3.2 | 飞书 Bot API + WS |
| 工具库 | commons-io 2.18.0 | 文件 I/O 工具 |
| HTTP 客户端 | java.net.http.HttpClient | 调用 opencode serve API |
| 连接池 | HikariCP (spring-boot-starter-jdbc) | SQLite 连接管理 |
| UI 组件 | marked.js (CDN) | 前端 Markdown 渲染 |

## 3. 项目结构

```
D:/projects/assistant-v2/
├── pom.xml                          # Maven 构建文件
├── config.json                      # 应用配置（飞书凭据、路径、功能开关）
├── assistant_log.json               # 操作日志文件
├── AGENTS.md                        # AI 代理说明
├── CLAUDE.md                        # Claude Code 项目指南
├── docs/
│   ├── technical-document.md        # 本文档
│   ├── feishu-bot-setup.md          # 飞书机器人配置
│   └── 客户看板-功能说明.md          # 客户看板功能说明
├── src/main/
│   ├── java/com/laoqi/assistant/
│   │   ├── AssistantApplication.java    # 入口
│   │   ├── config/                      # 配置类
│   │   │   ├── AppConfig.java           # 应用配置属性
│   │   │   ├── MybatisPlusConfig.java   # MyBatis-Plus 配置（@MapperScan）
│   │   │   ├── PortHealthChecker.java   # 端口健康检查
│   │   │   └── SchedulingConfig.java    # 定时任务线程池
│   │   ├── entity/                      # 数据库实体（MyBatis-Plus）
│   │   │   ├── ChatSessionEntity.java   # Web 聊天会话
│   │   │   ├── ChatMessageEntity.java   # Web 聊天消息
│   │   │   ├── FeishuSessionEntity.java # 飞书会话
│   │   │   └── FeishuMessageEntity.java # 飞书消息
│   │   ├── mapper/                      # MyBatis-Plus Mapper
│   │   │   ├── ChatSessionMapper.java
│   │   │   ├── ChatMessageMapper.java
│   │   │   ├── FeishuSessionMapper.java
│   │   │   └── FeishuMessageMapper.java
│   │   ├── model/                       # 数据模型（POJO）
│   │   │   ├── ChatSession.java         # 对话会话
│   │   │   ├── Config.java              # 配置模型
│   │   │   ├── CustomerData.java        # 客户数据模型
│   │   │   ├── LogEntry.java            # 日志条目
│   │   │   ├── ReminderData.java        # 提醒数据
│   │   │   ├── TaskData.java            # 任务数据
│   │   │   └── Prompt.java              # 提示词配置模型
│   │   ├── service/                     # 业务逻辑层
│   │   │   ├── db/                      # 数据库操作层（仿 qihang-erp-open 模式）
│   │   │   │   ├── ChatSessionDbService.java          # Interface
│   │   │   │   ├── ChatSessionDbServiceImpl.java      # Impl
│   │   │   │   ├── ChatMessageDbService.java
│   │   │   │   ├── ChatMessageDbServiceImpl.java
│   │   │   │   ├── FeishuSessionDbService.java
│   │   │   │   ├── FeishuSessionDbServiceImpl.java
│   │   │   │   ├── FeishuMessageDbService.java
│   │   │   │   └── FeishuMessageDbServiceImpl.java
│   │   │   ├── OpenCodeService.java        # opencode AI 客户端
│   │   │   ├── ChatSessionService.java     # 对话记录管理（业务逻辑）
│   │   │   ├── FeishuChatSessionService.java # 飞书对话管理
│   │   │   ├── FeishuService.java          # 飞书消息推送
│   │   │   ├── FeishuLongConnectionService.java # 飞书长连接
│   │   │   ├── ConfigService.java          # 配置管理
│   │   │   ├── ReportService.java          # 日报生成
│   │   │   ├── SchedulerService.java       # 定时任务调度
│   │   │   ├── MediaDataCollectorService.java # 自媒体数据采集
│   │   │   ├── CustomerService.java        # 客户数据管理
│   │   │   ├── OperationsService.java      # 运营数据分析
│   │   │   ├── PromptService.java          # 提示词管理
│   │   │   ├── TodoService.java            # TODO 解析
│   │   │   ├── ReminderService.java        # 动态提醒
│   │   │   ├── TaskService.java            # 任务管理
│   │   │   ├── LogService.java             # 操作日志
│   │   │   └── StartupReportGenerator.java # 启动报告
│   │   ├── controller/                     # Web 控制器
│   │   │   ├── IndexController.java        # 首页 / 日报
│   │   │   ├── ChatController.java         # AI 对话（SSE 流式）
│   │   │   ├── BrowseController.java       # 笔记浏览
│   │   │   ├── WorkReportController.java   # 工作日报/周报
│   │   │   ├── OperationsController.java   # 运营管理
│   │   │   ├── CustomerController.java     # 客户管理
│   │   │   ├── TaskController.java         # 任务看板
│   │   │   ├── ReminderController.java     # 提醒管理
│   │   │   ├── ConfigController.java       # 配置页面
│   │   │   ├── PromptConfigController.java # 提示词配置页面
│   │   │   ├── ApiConfigController.java    # 配置 API
│   │   │   ├── DataController.java         # 通用数据 CRUD API
│   │   │   ├── DataEditorController.java   # 数据编辑器页面
│   │   │   ├── AiProxyController.java      # AI 录入代理
│   │   │   ├── HealthController.java       # 健康检查
│   │   │   ├── LogController.java          # 操作日志查看
│   │   │   ├── HelpController.java         # 帮助页面
│   │   │   └── GlobalModelAdvice.java      # 全局模板属性
│   │   └── util/                           # 工具类
│   │       ├── FileUtil.java               # JSON/文本文件读写
│   │       ├── MarkdownUtil.java           # Markdown 渲染
│   │       ├── TimeUtil.java               # 时间工具
│   │       └── ThymeleafUtil.java          # Thymeleaf 辅助
│   └── resources/
│       ├── application.yml                 # Spring 配置
│       └── templates/                      # Thymeleaf 模板
│           ├── layout.html                 # 全局布局
│           ├── index.html                  # 综合日报首页
│           ├── chat.html                   # AI 对话
│           ├── browse.html                 # 笔记浏览
│           ├── view.html                   # 笔记查看
│           ├── config.html                 # 配置页
│           ├── prompts.html                # 提示词配置页
│           ├── customers.html              # 客户管理
│           ├── customers_all.html          # 客户列表
│           ├── leads_all.html              # 线索列表
│           ├── operations.html             # 运营管理
│           ├── work_reports.html           # 工作报告
│           ├── daily_reports.html          # 日报列表
│           ├── weekly_reports.html         # 周报列表
│           ├── tasks.html                  # 任务看板
│           ├── reminders.html              # 提醒管理
│           ├── data_editor.html            # 数据编辑器
│           ├── log.html                    # 操作日志
│           └── help.html                   # 帮助页面
```

## 4. 核心架构

### 4.1 总体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    用户访问入口                           │
│  ┌──────────┐  ┌──────────┐  ┌────────────────────┐    │
│  │ Browser  │  │ Feishu   │  │ Feishu Webhook     │    │
│  │ Web UI   │  │ Bot Chat │  │ (推送目标)          │    │
│  └────┬─────┘  └────┬─────┘  └────────────────────┘    │
│       │              │                                   │
├───────┼──────────────┼───────────────────────────────────┤
│       ▼              ▼                                   │
│  ┌─────────────────────────────────────┐                 │
│  │       Spring Boot (端口 6790)        │                 │
│  │                                      │                 │
│  │  ┌────────────┐  ┌────────────────┐  │                 │
│  │  │ Controllers│  │ GlobalModelAdv.│  │                 │
│  │  └─────┬──────┘  └────────────────┘  │                 │
│  │        │                              │                 │
│  │  ┌─────▼──────────────────────────┐   │                 │
│  │  │        业务 Services            │   │                 │
│  │  │  ┌──────────┐ ┌─────────────┐  │   │                 │
│  │  │  │OpenCode  │ │ChatSession  │  │   │                 │
│  │  │  │Service   │ │Service      │  │   │                 │
│  │  │  ├──────────┤ ├─────────────┤  │   │                 │
│  │  │  │Feishu    │ │MediaData    │  │   │                 │
│  │  │  │Service   │ │Collector    │  │   │                 │
│  │  │  ├──────────┤ ├─────────────┤  │   │                 │
│  │  │  │Report    │ │Customer     │  │   │                 │
│  │  │  │Service   │ │Service      │  │   │                 │
│  │  │  └──────────┘ └─────────────┘  │   │                 │
│  │  └──────────┬─────────────────────┘   │                 │
│  │             │                          │                 │
│  │  ┌──────────▼─────────────────────┐   │                 │
│  │  │      数据库 Service 层           │   │                 │
│  │  │  (service.db, 仿 IService 模式)  │   │                 │
│  │  │  ┌──────────┐ ┌─────────────┐  │   │                 │
│  │  │  │ChatSess. │ │ChatMsg.     │  │   │                 │
│  │  │  │DbService │ │DbService    │  │   │                 │
│  │  │  ├──────────┤ ├─────────────┤  │   │                 │
│  │  │  │Feishu    │ │FeishuMsg    │  │   │                 │
│  │  │  │Sess.DbSvc│ │DbService    │  │   │                 │
│  │  │  └──────────┘ └─────────────┘  │   │                 │
│  │  └──────────┬─────────────────────┘   │                 │
│  │             │                          │                 │
│  │  ┌──────────▼──────────┐              │                 │
│  │  │  MyBatis-Plus       │              │                 │
│  │  │  Mapper 接口         │              │                 │
│  │  └──────────┬──────────┘              │                 │
│  └─────────────┼─────────────────────────┘                 │
│                │                                           │
│  ┌─────────────▼───────────┐                               │
│  │     SQLite (memory.db)  │  ← 聊天记录                   │
│  │  chat_sessions          │                               │
│  │  chat_messages          │                               │
│  │  feishu_sessions        │                               │
│  │  feishu_messages        │                               │
│  └─────────────────────────┘                               │
│                │                                           │
│                │ HTTP (Java HttpClient)                    │
│                ▼                                           │
│  ┌──────────────────┐  ┌──────────────────┐               │
│  │ opencode serve   │  │ opencode serve   │               │
│  │ 端口 14096       │  │ 端口 14099       │               │
│  │ (AI 后端/知识库)  │  │ (编程 AI)        │               │
│  └──────────────────┘  └──────────────────┘               │
│                                                            │
│  ┌──────────────────────────────────────────────────┐     │
│  │          文件系统 (笔记库)                          │     │
│  │  D:\projects\richie_learning_notes\               │     │    │     │
│  │  ├── 工作/日报/                   (日报文件)       │     │
│  │  ├── 工作/周报/                   (周报文件)       │     │
│  │  ├── 工作/综合日报/               (AI 综合日报)    │     │
│  │  ├── 工作/提醒/reminders.json    (动态提醒)       │     │
│  │  ├── 工作/任务/data.json         (任务数据)       │     │
│  │  ├── 客户管理/data/              (客户 JSON 文件) │     │
│  │  ├── 自媒体/data/                (运营数据 JSON)  │     │
│  │  ├── 记忆/当前重点.md            (待办事项)       │     │
│  │  └── 提醒.md                     (循环/临时提醒)  │     │
│  └──────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
```

### 4.2 AI 对话流程

```
浏览器 POST /chat  →  ChatController
                        │
                        ├─ 检查 /code 前缀 → 决定 mode (knowledge/coding)
                        ├─ 检查对应 opencode 端口健康状态
                        ├─ 创建 SseEmitter (300s timeout)
                        ├─ 后台线程:
                        │   ├─ openCodeService.findIdleSession()
                        │   │   └─ GET /session → 复用第一个可用会话
                        │   ├─ openCodeService.sendMessage(sessionId, message)
                        │   │   └─ POST /session/{id}/message (600s timeout)
                        │   │   └─ 流式读取响应 → 逐 part 解析 (text/reasoning/tool_use)
                        │   ├─ SSE: status → text → done
                        │   └─ sessionService.saveMessage()
                        │      → ChatSessionDbService → ChatSessionMapper → SQLite
                        │
飞书长连接 → FeishuLongConnectionService.handleMessage()
                        │
                        ├─ 解析消息内容 (Gson)
                        ├─ 去重检测 (ConcurrentHashMap)
                        ├─ FeishuChatSessionService.getOrCreate()
                        │  → FeishuSessionDbService → FeishuSessionMapper → SQLite
                        ├─ 立即回复 "正在思考..."
                        ├─ 后台线程:
                        │   ├─ processNormalMessage / processCodeMessage
                        │   │   ├─ buildHistoryContext()
                        │   │   │  → FeishuMessageDbService → FeishuMessageMapper → SQLite
                        │   │   ├─ openCodeService.createSession() + sendMessage()
                        │   │   └─ saveMessage()
                        │   │      → FeishuMessageDbService → FeishuMessageMapper → SQLite
                        │   └─ sendReply() → 飞书 API 回复
```

### 4.3 SSE 事件格式

ChatController 使用 Server-Sent Events 实现流式输出：

| 事件类型 | 格式 | 说明 |
|---------|------|------|
| status | `data:{"type":"status","content":"...","mode":"knowledge"}` | 状态提示 |
| text | `data:{"type":"text","content":"...","mode":"knowledge"}` | AI 回复内容 |
| error | `data:{"type":"error","content":"..."}` | 错误信息 |
| done | `data:{"type":"done","mode":"knowledge"}` | 完成信号 |

### 4.4 opencode API 协议

调用 opencode serve 的 REST API：

```
POST /session               → 创建会话 → { "id": "..." }
GET  /session               → 获取所有会话 → [{ "id": "...", ... }]
POST /session/{id}/message  → 发送消息 → 流式 JSON 响应

消息请求体: { "parts": [{ "type": "text", "text": "..." }] }
消息响应体: { "parts": [{ "type": "text", "text": "..." }, { "type": "reasoning", ... }] }
```

## 5. 模块详解

### 5.1 配置模块

**application.yml** — Spring Boot 核心配置：
- 服务器端口 6790，绑定 0.0.0.0
- Thymeleaf 模板（classpath:/templates/, .html, 缓存启用, UTF-8）
- Jackson 时区 Asia/Shanghai，忽略 null 字段
- 数据库：SQLite（`jdbc:sqlite:${ASSISTANT_CONFIG_DIR:.}/memory.db`）
- MyBatis-Plus：map-underscore-to-camel-case，id-type=auto
- 应用级配置：config-dir、notes-port(14096)、code-port(14099)、max-history-chars(6000)
- 日志级别：核心服务设为 DEBUG

**config.json** — 运行时配置（位于项目根目录或 ASSISTANT_CONFIG_DIR）：
- `baseDir`：笔记库根目录（`D:\projects\richie_learning_notes`）
- `feishuWebhookUrl`：飞书机器人 Webhook
- `feishuAppId` / `feishuAppSecret`：飞书开放平台凭据
- `feishuChatId`：接收消息的飞书群 ID
- `feishuPollingEnabled`：开启飞书长连接
- `customerDataDir` / `operationsDataDir`：客户/运营数据目录名
- `mediaCollectEnabled` / `mediaCollectTime`：自媒体数据定时采集开关
- `columnSettings`：客户/运营数据表格列配置
- `workDir` / `dailyDir` / `weeklyDir`：工作目录路径

> 注：`chatSessionsDir` / `chatSessionsFile` 旧配置项已移除。聊天记录不再保存为 JSON 文件，始终写入 SQLite `memory.db`。旧 `config.json` 中残留的这两个字段会被 `@JsonIgnoreProperties(ignoreUnknown = true)` 静默忽略。

**AppConfig.java**：`@ConfigurationProperties(prefix = "app")`，映射 application.yml 中的 app.* 属性。

**MybatisPlusConfig.java**：`@MapperScan("com.laoqi.assistant.mapper")`，扫描 Mapper 接口。

### 5.2 数据库层（新）

系统近期将聊天记录从 JSON 文件迁移到 SQLite 数据库，采用 MyBatis-Plus 作为 ORM 框架。

#### 数据库表结构

| 表名 | 用途 | 主要字段 |
|------|------|---------|
| `chat_sessions` | Web 聊天会话 | id(TEXT PK), title, mode, created_at, updated_at |
| `chat_messages` | Web 聊天消息 | id(INTEGER PK AUTO), session_id, role, content, mode, created_at |
| `feishu_sessions` | 飞书会话 | user_key(TEXT PK), chat_id, chat_type, open_code_session_id, open_code_code_session_id, created, updated |
| `feishu_messages` | 飞书消息 | id(INTEGER PK AUTO), user_key, role, content, mode, created_at |

#### 架构模式

采用仿 qihang-erp-open 的分层模式：

```
业务 Service (如 ChatSessionService)
  → 数据库 Service 接口 (extends IService<Entity>)
  → 数据库 Service 实现 (extends ServiceImpl<Mapper, Entity>)
  → MyBatis-Plus Mapper (extends BaseMapper<Entity>)
  → SQLite
```

每张表对应：
- **Entity**：`@TableName` 注解的实体类，字段映射
- **Mapper**：`extends BaseMapper<Entity>`，提供基础 CRUD + 自定义 `@Select` 查询
- **DbService 接口**：`extends IService<Entity>`，暴露额外查询方法
- **DbService 实现**：`extends ServiceImpl<Mapper, Entity> implements XXXDbService`

#### DDL 管理

表结构在 `ChatSessionService.init()`（`@PostConstruct`）中通过 `DataSource` 原生 JDBC `CREATE TABLE IF NOT EXISTS` 执行，无需外部 SQL 脚本。

#### 数据迁移

应用启动时自动检测旧的 `chat_sessions.json` 和 `feishu_sessions.json`，将其数据逐条插入 SQLite 后将旧文件重命名为 `.bak`。迁移完成后，聊天记录完全由 SQLite 管理，旧配置文件 `config.json` 中的 `chatSessionsDir` / `chatSessionsFile` 字段不再使用（已从代码中移除）。配置页中对应的"聊天记录保存位置"设置项也已删除。

### 5.3 AI 服务模块 — OpenCodeService

核心 AI 客户端，通过 `java.net.http.HttpClient` 与 opencode serve 通信。

关键方法：
- `createSession(title)` → POST /session，创建新会话
- `sendMessage(sessionId, message)` → POST /session/{id}/message，发送消息（600s 超时）
- `sendCodeMessage(sessionId, message)` → 通过 code-port (14099) 发送
- `findIdleSession()` → GET /session 取第一个会话复用
- `findIdleCodeSession()` → 同上，查 14099 端口的会话
- `isHealthy()` / `isCodeHealthy()` → TCP 端口检测
- `isSessionValid(port, sessionId)` → 验证会话是否仍有效

流式解析（`sendMessageStreamed`）：
1. 后台线程读取网络流 → `LinkedBlockingQueue<byte[]>`
2. 主线程轮询队列 → 累积 JSON 缓冲区
3. `tryExtractParts()` 增量解析 `parts` 数组 → 区分 text/reasoning/tool_use/tool_result
4. 拼接所有 text part 为最终回复

### 5.4 对话管理模块 — ChatSessionService

管理 Web 聊天的会话记录，数据存储在 SQLite 的 `chat_sessions` 和 `chat_messages` 表中。

- 通过 `ChatSessionDbService` / `ChatMessageDbService` 操作数据库
- `SessionsData`：包含 `current`（当前会话 ID）和 `sessions`（会话列表）
- `load()` / `save()`：全量读写（兼容旧 JSON 接口）
- `saveMessage()`：追加消息，自动更新会话标题和时间
- `buildHistoryContext()`：构造包含历史对话的 prompt（支持按 mode 过滤）
- `deriveTitle()`：取第一条用户消息的前 30 字符作为会话标题

### 5.5 飞书集成

#### FeishuService — 消息推送
- `getTenantToken()`：获取 tenant_access_token（缓存 1.5 小时）
- `sendTextMessage()`：通过飞书 API 发送文本消息
- `sendPost()`：通过 Webhook 发送富文本消息（post 格式）
- `listMessages()` / `listChats()`：查询消息和群列表
- HTTP 通信使用 `HttpURLConnection`（手动 JSON 转义，无第三方 HTTP 库）

#### FeishuLongConnectionService — 消息接收（WebSocket）
- 基于 larksuite oapi SDK 的 WebSocket 长连接
- `@PostConstruct init()`：自动启动
- `onP2MessageReceiveV1`：处理收到的消息
  - 按 `p2p:{openId}` 或 `group:{chatId}:{openId}` 为每个用户维护独立会话
  - 消息去重（`processedMessages` ConcurrentHashMap，上限 1000）
  - 支持 `/code` 前缀切换编程 AI 模式
  - 重试机制：失败后自动重试一次
- `isCodeRequest()`：检测 `/code` 前缀
- `processMessage()`：根据前缀路由到不同的 AI 服务
- `buildHistoryContext()`：从历史消息中构建上下文（按 mode 过滤，排除当前消息）

#### FeishuChatSessionService — 飞书对话持久化
- 管理 SQLite 的 `feishu_sessions` 和 `feishu_messages` 表
- 通过 `FeishuSessionDbService` / `FeishuMessageDbService` 操作数据库
- `getOrCreate()`：按 userKey 查找或新建会话
- `saveMessage()`：保存消息（支持 mode 标记）
- `buildHistoryContext()`：构造包含历史对话的 prompt
- `setOpenCodeSessionId()` / `setOpenCodeCodeSessionId()`：记录 opencode 会话 ID

### 5.6 日报系统 — ReportService

日报生成流程：
1. `generate()`：调用 opencode AI 生成日报
   - Prompt 包含当天日期、工作日、格式要求
   - 直接调用 AI 基于笔记库内容生成
2. `generateAndPush()`：生成后推送到飞书
3. `saveComprehensiveReport()`：保存到 `工作/综合日报/{date}.md`
4. `readTodayReport()`：读取当日已生成的日报文件
5. `sendPost()`：通过飞书 Webhook 推送富文本消息

定时触发：工作日 09:30 自动生成并推送（SchedulerService.morningReport）。

### 5.7 自媒体数据采集 — MediaDataCollectorService

AI 驱动自动采集 CSDN/知乎公开数据：
1. `collect()`：主入口
2. `searchPlatformData()`：构造 AI prompt → opencode AI 执行 webfetch
3. `parseAiResponse()`：从 AI 回复中提取 JSON（支持 ```json 包裹）
4. `mergeArticleData()`：按标题模糊匹配，更新/新增文章数据
5. `mergeAccountsDirect()`：更新账号级指标
6. 采集完成发送飞书通知

定时触发：根据 config 中 `mediaCollectTime` 设定时间（每分钟检查一次）。

依赖的 JSON 文件（`自媒体/data/`）：
- `自媒体文章.json`：各平台文章列表
- `自媒体账号.json`：各平台账号指标
- `自媒体日数据.json`：每日统计数据

### 5.8 客户管理 — CustomerService

基于 JSON 文件的 CRM：
- 客户数据 → `客户管理/data/*.json`
- 线索数据 → `线索数据.json`
- 跟进记录 → `跟进记录.json`

支持：
- `loadAllDataGroups()`：加载目录下所有 JSON 文件
- `aiAnalyze(force)`：AI 客户分析报告
- 线索/客户/跟进记录的 CRUD

### 5.9 运营分析 — OperationsService

基于 JSON 数据的自媒体运营分析：
- 读取 `自媒体账号.json`、`自媒体文章.json`、`自媒体日数据.json`
- `buildDataSummary()`：构建数据摘要用于 AI 分析
- `aiAnalyze(force)`：AI 运营分析报告
- 缓存分析结果（当日有效）

### 5.10 定时任务 — SchedulerService

| 任务 | Cron | 说明 |
|------|------|------|
| morningReport | 每天 09:30 | 生成综合日报并推送到飞书 |
| collectPlatformData | 每分钟 * * | 检查是否需要执行 CSDN 数据采集 |
| wechatDataRequest | 08:57 | 发送公众号数据采集请求 |
| dailyReportReminder | 每天 18:00 | 下班日报提醒 |
| articleTuesday | 每周二 09:00 | 码农老齐发文提醒 |
| articleThursday | 每周四 09:00 | 启航电商ERP发文提醒 |
| checkDynamicReminders | 每分钟 * * | 检查并触发动态提醒 |

### 5.11 提醒系统 — ReminderService

动态提醒管理，支持四种类型：
- **daily**：每天固定时间
- **weekly**：每周特定天
- **monthly**：每月特定日期
- **yearly**：每年特定月日

数据存储：`工作/提醒/reminders.json`
触发机制：每分钟检查一次，通过飞书 Webhook 推送通知，避免重复触发。

### 5.12 任务看板 — TaskService

管理 `工作/任务/data.json`，支持任务的 CRUD 和状态管理。
每个任务包含：id、title、description、status(pending/in_progress/done)、priority(high/mid/low)、dueDate。

### 5.13 TODO 管理 — TodoService

从 Markdown 文件解析待办事项：
- `记忆/当前重点.md` → 高/中/低优先级
- `提醒.md` → 每日循环 / 临时提醒
- 日报推送后自动清空临时提醒

### 5.14 笔记浏览 — BrowseController

文件系统浏览，目录白名单过滤（忽略 `.git`、`.obsidian` 等）。
- 列出目录和 .md 文件
- 面包屑导航
- 新建/删除 .md 文件
- 路径安全校验（`safeResolve()`，防止目录遍历）
- 查看页面（Markdown → HTML 渲染，支持 frontmatter 剥离）

### 5.15 通用数据 API — DataController

通用 JSON 数据 CRUD API，用于操作客户/运营数据目录下的 JSON 文件：
- `/api/data/list`：列出文件和数据结构
- `/api/data/file/{fileName}`：读取文件
- `/api/data/group`：读取指定分组
- `/api/data/update`：按 ID 更新记录
- `/api/data/add`：添加记录
- `/api/data/delete`：按 ID 删除记录
- `/api/data/column-settings`：列配置读写

### 5.16 提示词管理 — PromptService

提供提示词的动态管理能力：
- `getAllPrompts()` / `savePrompts()` / `resetPrompts()`：提示词的 CRUD
- Web UI 配置入口在 `/prompts` 独立页面
- API 端点：`GET/POST /api/config/prompts`，`POST /api/config/prompts/reset`

## 6. 数据模型

### Entity（MyBatis-Plus 映射）

#### ChatSessionEntity → chat_sessions
| 字段 | 类型 | 说明 |
|------|------|------|
| id | String (PK) | 会话 ID |
| title | String | 会话标题（取首条用户消息前 30 字） |
| mode | String | 模式 (knowledge/coding) |
| createdAt | String | 创建时间 |
| updatedAt | String | 更新时间 |

#### ChatMessageEntity → chat_messages
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Integer (PK, AUTO) | 消息 ID |
| sessionId | String | 所属会话 ID |
| role | String | user/assistant |
| content | String | 消息内容 |
| mode | String | 模式 (knowledge/coding) |
| createdAt | String | 创建时间 |

### POJO（文件存储模型）

#### ChatSession (model)
```
id, title, mode(knowledge/coding), created, updated
messages[] → { role(user/assistant), content, time, mode }
```

#### Config
```
baseDir, feishuWebhookUrl, feishuAppId, feishuAppSecret, feishuChatId
feishuPollingEnabled, customerDataDir, operationsDataDir
workDir, dailyDir, weeklyDir, remindFile
mediaCollectEnabled, mediaCollectTime
columnSettings → { type → { tableKey → [columns] } }
```

#### TaskItem
```
id(T{timestamp}), title, description, status(pending/in_progress/done)
priority(high/mid/low), createdAt, updatedAt, dueDate
```

#### Reminder
```
id(R{timestamp}), name, message, type(daily/weekly/monthly/yearly)
time, dayOfWeek(1-7), dayOfMonth(1-31), monthDay(M-D)
enabled, createdAt, lastTriggered
```

#### LogEntry
```
time, action, status(成功/失败/警告), detail
```

## 7. 工具类

### FileUtil
- `readJson(Path, Class/TypeRef, default)`：读取 JSON 文件，不存在则返回默认值
- `writeJson(Path, Object)`：写入 JSON（自动创建父目录），带详细日志
- `readText(Path)` / `writeText(Path, String)`：文本文件读写

### MarkdownUtil
- `toHtml(markdown)`：基于 commonmark 渲染 HTML（支持 GFM 表格）
- `preprocessReport()`：预处理日报格式（━ 分割线、【】标题标签）
- `markdownInline()`：简单行内 Markdown 渲染
- `stripFrontmatter()`：剥离 YAML frontmatter（---...---）

### TimeUtil
- 所有时间基于 `Asia/Shanghai` 时区
- 支持日期/时间格式化、周几中文名、问候语/表情（按小时）
- `sessionId()`：基于时间戳 + 随机数的唯一 ID 生成

## 8. 前端架构

### 模板布局
- `layout.html`：全局布局，含导航栏、端口状态提示、toast 通知函数、样式
- 所有页面通过 Thymeleaf layout:fragment 嵌入内容区域
- 全局属性通过 `GlobalModelAdvice` 注入：notesRunning, codeRunning, notesPort, codePort, requestURI, keyLabels

### 页面路由
| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | index.html | 综合日报仪表盘 + TODO 悬浮面板 |
| `/chat` | chat.html | AI 对话（支持 /code 模式切换） |
| `/browse` | browse.html | 笔记库文件浏览 |
| `/view` | view.html | Markdown 笔记渲染查看 |
| `/operations` | operations.html | 运营数据管理 + AI 分析 |
| `/work-reports` | work_reports.html | 日报/周报列表 + AI 分析 |
| `/daily-reports` | daily_reports.html | 日报列表 |
| `/weekly-reports` | weekly_reports.html | 周报列表 |
| `/customers` | customers.html | 客户管理（含 AI 分析） |
| `/customers/all` | customers_all.html | 客户完整列表 |
| `/leads/all` | leads_all.html | 线索完整列表 |
| `/tasks` | tasks.html | 任务看板 |
| `/reminders` | reminders.html | 动态提醒管理 |
| `/config` | config.html | 系统配置（飞书、目录、定时任务） |
| `/prompts` | prompts.html | 提示词配置 |
| `/data-editor` | data_editor.html | 通用 JSON 数据编辑器 |
| `/log` | log.html | 操作日志查看 |
| `/help` | help.html | 帮助页面 |

### 端口健康提示
首页和所有页面顶部显示 banner：笔记库(14096) 和 Java项目(14099) 的状态。每 30 秒自动检测。

## 9. 部署与运行

### 前置条件
- Java 17+、Maven
- opencode serve 运行在 14096 端口（知识库 AI）
- opencode serve 运行在 14099 端口（编程 AI）

### 启动
```powershell
mvn package -q
java -jar target/ai-assistant-2.0.0.jar
```

或开发模式直接运行：
```powershell
mvn spring-boot:run
```

### 配置
- 默认 `config.json` 位于项目根目录
- 可通过环境变量 `ASSISTANT_CONFIG_DIR` 指定配置目录
- 数据目录 `app.baseDir` 指向笔记库路径（`D:\projects\richie_learning_notes`）
- SQLite 数据库文件位于 `{ASSISTANT_CONFIG_DIR}/memory.db`

### 运行库说明
- 聊天记录存储在 SQLite 中，通过 MyBatis-Plus 操作
- 业务数据持久化为 JSON/Markdown 文件
- 无 Redis 或消息队列依赖
- 前端无构建步骤（纯 CDN 加载 marked.js）

## 10. 关键设计决策

1. **数据分层：文件系统 + SQLite 混合存储** — 聊天记录迁移到 SQLite（结构化数据，需按 session/mode 过滤查询），业务数据仍以 JSON/Markdown 文件存储（灵活、可读、可直接编辑）。

2. **MyBatis-Plus + IService 分层** — 采用仿 qihang-erp-open 的数据库 Service 层模式（IService → ServiceImpl → Mapper → SQLite），保持与公司项目的风格统一。

3. **无外部 DDL 脚本** — 表结构在 `@PostConstruct` 中通过 DataSource 的原生 JDBC 执行，无需 SQL 脚本文件或 Flyway/Liquibase 等迁移工具。

4. **opencode serve 作为 AI 后端** — 通过 REST API 调用外部 AI 服务，解耦 AI 模型与业务逻辑。

5. **双入口架构** — Web UI（Thymeleaf SSR）和飞书 Bot（Webhook + WebSocket），满足不同使用场景。

6. **SSE 流式输出** — AI 回复通过 Server-Sent Events 推送，提升对话体验的实时性。

7. **数据库自动迁移** — 旧 JSON 聊天记录在首次启动时自动迁移到 SQLite，旧文件重命名为 `.bak`，迁移幂等（已有数据则跳过）。

## 11. 已知限制与风险

- **ReportService.generate() 为桩方法**：当前始终返回 "AI 引擎未配置"，需集成 opencode serve
- **opencode serve 单线程**：避免对同一会话的并发请求
- **JSON 手动解析**：FeishuService 使用手动 JSON 转义，未使用 Jackson 序列化
- **无认证机制**：Web UI 无登录保护，仅限本地或内网使用
- **文件锁**：多线程写 JSON 文件可能存在竞态条件（LogService 使用了 synchronized，但其他 Service 未加锁）
- **定时任务时区**：通过 `zone = "Asia/Shanghai"` 硬编码
- **DDL 与服务代码耦合**：建表语句在 ChatSessionService.java 中，不属于专门的数据库初始化模块

## 12. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.1 | 2026-06 | 新增 SQLite + MyBatis-Plus 数据层，聊天记录从 JSON 迁移至数据库；新增数据库 Service 分层模式；新增提示词管理功能 |
| 2.0.0 | 2026-06 | 重构为 Spring Boot 版本，Web UI 双入口，飞书长连接，多模块功能 |
