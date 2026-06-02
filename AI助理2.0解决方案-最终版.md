---
tags:
  - AI助理
  - 解决方案
tags:
  - AI助理
  - 解决方案
  - 实际架构
created: 2026-05-30
modified: 2026-05-31
---

# AI助理 2.0 解决方案（最终版）

> Spring Boot 3.4 + Thymeleaf + opencode serve 混合AI架构
> 实际实现文档

---

## 一、项目概述

### 1.1 背景

从 AI助理 1.0（Python CLI）升级为 2.0（Java Web 服务），解决以下问题：

| 问题 | 说明 |
|------|------|
| **CLI模式无上下文** | opencode/claudecode 以 CLI 启动，每次对话独立 |
| **Python技术栈** | 与用户 Java 技术栈不匹配，维护成本高 |
| **功能分散** | 多个工具混用，无统一入口 |

### 1.2 当前能力

| 能力 | 说明 |
|------|------|
| **AI对话** | Web 聊天界面，SSE 流式输出，支持 opencode serve |
| **客户看板** | JSON 文件驱动，动态表格，列设置，AI 分析 |
| **运营看板** | 自媒体运营数据展示，AI 分析报告 |
| **数据编辑器** | 通用 JSON 数据在线编辑 |
| **飞书集成** | Webhook 推送 + Bot API 消息接收 + 自动回复 |
| **定时任务** | 日报生成、下班提醒、发文提醒 |
| **配置管理** | Web UI 配置基础路径、数据目录、飞书凭据等 |
| **笔记库浏览** | 文件/目录浏览 |
| **工作汇报** | 日报、周报、综合日报查看 |

---

## 二、系统架构

### 2.1 架构图

```
                        ┌──────────────────────────────────────────┐
                        │          AI Assistant 2.0                │
                        │        Spring Boot 3.4 + Java 17         │
                        ├──────────────────────────────────────────┤
                        │                                          │
  ┌─────────────────┐  │  ┌──────────┐ ┌──────────┐ ┌──────────┐  │
  │  Thymeleaf 页面  │  │  │Controller│ │ Service  │ │  Model   │  │
  │  15个模板       │◄─┼─►│  13个    │◄┤  10个    │◄┤  5个     │  │
  │  layout装饰器    │  │  │          │ │          │ │          │  │
  └─────────────────┘  │  └──────────┘ └──────────┘ └──────────┘  │
                        │         │                               │
                        │  ┌──────▼──────┐  ┌──────────────────┐  │
                        │  │ OpenCode    │  │ FeishuService    │  │
                        │  │ Service     │  │ Webhook + Bot    │  │
                        │  │ HTTP Client │  │ + LongConn       │  │
                        │  └──────┬──────┘  └──────────────────┘  │
                        └─────────┼────────────────────────────────┘
                                  │
                     ┌────────────┼────────────┐
                     │            │            │
              ┌──────▼─────┐ ┌───▼────┐ ┌────▼─────┐
              │ opencode   │ │ 笔记库  │ │  飞书    │
              │ serve:14096│ │:14099  │ │  API     │
              └────────────┘ └────────┘ └──────────┘
```

### 2.2 模块职责

| 模块 | 职责 | 实际类 |
|------|------|--------|
| **OpenCodeService** | 封装 opencode serve HTTP API，会话复用 | `OpenCodeService.java` |
| **ChatSessionService** | 聊天会话 CRUD，JSON 文件持久化 | `ChatSessionService.java` |
| **ChatController** | SSE 流式聊天，会话管理 | `ChatController.java` |
| **DataController** | 通用 JSON 数据读取/写入/分组，列设置 | `DataController.java` |
| **FeishuService** | 飞书 Webhook 推送 + Bot API 消息收发 | `FeishuService.java` |
| **FeishuLongConnectionService** | 飞书 WebSocket 长连接接收消息 | `FeishuLongConnectionService.java` |
| **ConfigService** | config.json 读写 | `ConfigService.java` |
| **SchedulerService** | Spring @Scheduled 定时任务 | `SchedulerService.java` |
| **ReportService** | 日报生成（当前桩，未接 AI） | `ReportService.java` |
| **CustomerService** | 客户数据 CRUD + AI 分析 | `CustomerService.java` |
| **OperationsService** | 运营数据 CRUD + AI 分析 | `OperationsService.java` |
| **LogService** | 操作日志记录 | `LogService.java` |

### 2.3 与设计文档的关键差异

| 方面 | 设计文档（旧） | 实际实现 |
|------|---------------|----------|
| **包名** | `com.qihangerp.ai` | `com.laoqi.assistant` |
| **端口** | 8080 / 4096 | 6790 / 14096(聊天) / 14099(编码) |
| **HTTP客户端** | OkHttp | `java.net.http.HttpClient` (JDK内置) |
| **聊天方式** | JSON 请求/响应 | SSE 流式输出 |
| **会话存储** | 内存缓存 | JSON 文件持久化 |
| **任务路由** | TaskRouter + TaskType 枚举 | 无，直接调用对应 Service |
| **看板数据** | 强类型 POJO 映射 | 通用 `Map<String, Object>` 动态处理 |
| **模板** | Bootstrap + jQuery | 自定义 CSS，无前端框架 |

---

## 三、技术选型

### 3.1 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 核心语言 |
| Spring Boot | 3.4.4 | Web 框架 |
| Jackson | 2.x | JSON 处理（文件持久化） |
| commons-io | 2.18.0 | 文件操作 |
| commonmark | 0.24.0 | Markdown 渲染 |
| Java-WebSocket | 1.6.0 | 飞书长连接 |
| Feishu OpenAPI SDK | 2.3.2 | 飞书消息接收 |
| thymeleaf-layout-dialect | - | 模板布局 |

### 3.2 前端

| 技术 | 用途 |
|------|------|
| Thymeleaf | 服务端模板 |
| 原生 JS | 无框架，纯手写 |
| SSE (EventSource) | AI 对话流式输出 |

### 3.3 外部依赖

| 依赖 | 说明 | 端口 | 费用 |
|------|------|------|------|
| opencode serve | AI 服务后端 | 14096 | 免费 |
| 笔记库服务 | 编码相关 AI | 14099 | 免费 |
| 飞书 API | Webhook 推送 + Bot 消息 | - | 免费 |

---

## 四、项目结构

### 4.1 文件组织

```
ai-assistant/
├── src/main/java/com/laoqi/assistant/
│   ├── AssistantApplication.java        # 启动入口
│   ├── config/
│   │   ├── AppConfig.java               # @ConfigurationProperties(app.*)
│   │   └── PortHealthChecker.java       # 端口健康检测
│   ├── model/
│   │   ├── ChatSession.java             # 聊天会话+消息模型
│   │   ├── Config.java                  # config.json 映射
│   │   ├── CustomerData.java            # 客户数据 POJO
│   │   ├── OperationsData.java          # 运营数据 POJO（含分析结果）
│   │   └── LogEntry.java                # 日志条目
│   ├── controller/
│   │   ├── IndexController.java         # 首页 + 日报手动触发
│   │   ├── ChatController.java          # 聊天 SSE 接口
│   │   ├── CustomerController.java      # 客户看板页面
│   │   ├── OperationsController.java    # 运营看板页面 + AI 分析 SSE
│   │   ├── DataController.java          # 通用 JSON 数据 API（list/group/update/add/delete）
│   │   ├── DataEditorController.java    # 数据编辑器页面
│   │   ├── ApiConfigController.java     # 配置 REST API
│   │   ├── ConfigController.java        # 配置页面
│   │   ├── BrowseController.java        # 笔记浏览
│   │   ├── WorkReportController.java    # 工作汇报页面
│   │   ├── HealthController.java        # 健康检查
│   │   ├── LogController.java           # 操作日志
│   │   ├── HelpController.java          # 帮助页面
│   │   ├── AiProxyController.java       # AI 代理
│   │   └── GlobalModelAdvice.java       # 全局 Thymeleaf 变量
│   ├── service/
│   │   ├── OpenCodeService.java         # opencode serve HTTP 客户端
│   │   ├── ChatSessionService.java      # 会话文件管理
│   │   ├── ConfigService.java           # config.json 读写
│   │   ├── FeishuService.java           # 飞书 Webhook + Bot API
│   │   ├── FeishuLongConnectionService.java  # 飞书 WebSocket 长连接
│   │   ├── SchedulerService.java        # @Scheduled 定时任务
│   │   ├── ReportService.java           # 日报生成（桩）
│   │   ├── CustomerService.java         # 客户数据 + AI 分析
│   │   ├── OperationsService.java       # 运营数据 + AI 分析
│   │   ├── TodoService.java             # TODO 管理
│   │   └── LogService.java              # 操作日志
│   └── util/
│       ├── FileUtil.java                # JSON/文件读写工具
│       ├── MarkdownUtil.java            # Markdown 渲染
│       ├── ThymeleafUtil.java           # 模板工具
│       └── TimeUtil.java                # 时间工具
├── src/main/resources/templates/
│   ├── layout.html                      # 公共布局模板
│   ├── index.html                       # 首页（综合日报）
│   ├── chat.html                        # AI 对话页
│   ├── customers.html                   # 客户看板页
│   ├── operations.html                  # 运营看板页
│   ├── data_editor.html                 # 数据编辑器
│   ├── config.html                      # 配置页
│   ├── browse.html                      # 笔记库浏览
│   ├── view.html                        # 文件查看
│   ├── work_reports.html                # 工作汇报
│   ├── daily_reports.html               # 日报列表
│   ├── weekly_reports.html              # 周报列表
│   ├── log.html                         # 操作日志
│   ├── help.html                        # 帮助页
│   └── leads_all.html                   # 线索列表（备用）
├── src/main/resources/
│   ├── application.yml                  # Spring Boot 配置
│   └── static/                          # 静态资源
├── config.json                          # 运行配置（gitignored? 实际已跟踪）
└── pom.xml
```

### 4.2 数据流

#### AI 聊天流程
```
浏览器 → POST /chat → ChatController → OpenCodeService(sendMessage)
                                           ↓
                                    opencode serve:14096
                                           ↓
ChatController (SSE SseEmitter) ← OpenCodeService(解析文本)
                                           ↓
浏览器 EventSource ← SSE 事件流 (status/text/error/done)
```

#### 数据看板流程
```
浏览器 → GET /operations → OperationsController → OperationsService.loadData()
                                                      ↓
                                               config.json(operationsDataDir)
                                                      ↓
                                               baseDir/自媒体/data/运营数据.json
                                                      ↓
                                               返回 Model → Thymeleaf 渲染

原始数据表格通过 JS 异步加载:
浏览器 → GET /api/data/list?type=operations → DataController
                                                      ↓
                                               JSON 数据 → 前端 JS 动态渲染表格
```

---

## 五、核心功能详解

### 5.1 AI 对话

| 特性 | 实现方式 |
|------|----------|
| **流式输出** | SSE (SseEmitter)，timeout 300s |
| **会话复用** | `findIdleSession()` 复用最近空闲会话 |
| **会话管理** | `chat_sessions.json` 持久化，支持多条会话 |
| **消息保存** | 每次对话自动保存到 JSON 文件 |
| **多模式** | 支持普通对话和"编程"模式（不同端口） |

### 5.2 数据看板

**通用数据 API** (`DataController.java`):

| API | 说明 |
|-----|------|
| `GET /api/data/list?type=xxx` | 列出 JSON 文件及分组元信息 |
| `GET /api/data/file/xxx?type=yyy` | 获取单个文件完整数据 |
| `GET /api/data/group?file=&group=&type=` | 获取指定分组数据（展开嵌套数组） |
| `POST /api/data/update` | 更新记录（按 idField/idValue 定位） |
| `POST /api/data/add` | 新增记录 |
| `POST /api/data/delete` | 删除记录 |
| `GET/POST /api/data/column-settings` | 列设置持久化 |

**数据目录规范**: `baseDir/配置目录/data/*.json`

**特性**:
- 自动发现目录下所有 JSON 文件
- 时间字段自动检测并倒序排序
- 列设置保存/恢复（config.json > columnSettings）
- 默认显示 6 行，可展开全部
- 嵌套 Map 结构自动展平为数组（如 `articles: { 账号名: [...] }`）

### 5.3 飞书集成

| 功能 | 实现 | 说明 |
|------|------|------|
| **Webhook 推送** | `HttpURLConnection` POST | 发送综合日报、提醒到飞书群 |
| **Bot 消息** | 飞书 OpenAPI (tenant_token) | 发送文本消息到指定 chat |
| **消息轮询** | 每15秒检查未读消息 | 通过飞书 API 轮询接收消息 |
| **WebSocket 长连接** | Java-WebSocket | 实时接收飞书消息 |
| **自动回复** | 收到消息后调用 opencode AI 回复 | 群聊中 @机器人 自动响应 |

### 5.4 定时任务

| 任务 | 时间 | 说明 |
|------|------|------|
| 综合日报推送 | 工作日 09:30 | 调用 ReportService（当前桩） |
| 下班提醒 | 每日 18:00 | 飞书推送下班提醒 |
| 周二发文提醒 | 周二 09:00 | 飞书提醒码农老齐发文 |
| 周四发文提醒 | 周四 09:00 | 飞书提醒启航电商ERP发文 |

### 5.5 配置管理

**配置项** (config.json):

| 配置 | 说明 |
|------|------|
| baseDir | 笔记库根目录 |
| customerDataDir | 客户数据目录（相对 baseDir） |
| operationsDataDir | 运营数据目录（相对 baseDir） |
| feishuWebhookUrl | 飞书 Webhook URL |
| feishuAppId/Secret | 飞书 Bot 凭据 |
| feishuChatId | 监听的群聊 ID |
| feishuPollingEnabled | 是否启用消息轮询 |
| chatSessionsDir | 聊天记录保存目录 |
| columnSettings | 数据表格列显示设置 |
| keyLabels | 字段中文标签映射 |

### 5.6 AI 分析

| 分析对象 | 触发方式 | 说明 |
|----------|----------|------|
| **客户数据** | 客户看板页自动 + 手动 | 分析客户阶段分布、跟进情况 |
| **运营数据** | 运营看板页自动 + 手动 | 分析各平台表现、文章效果、改进建议 |
| **分析缓存** | 按日期缓存 | 同一天内重复访问返回缓存结果 |

---

## 六、页面路由

| 路径 | 说明 |
|------|------|
| `/` | 首页（综合日报） |
| `/chat` | AI 对话 |
| `/customers` | 客户看板 |
| `/operations` | 运营看板 |
| `/data-editor` | 数据编辑器（参数驱动） |
| `/config` | 系统配置 |
| `/browse` | 笔记库浏览 |
| `/browse/view` | 文件查看 |
| `/work_reports` | 工作汇报 |
| `/daily_reports` | 日报列表 |
| `/weekly_reports` | 周报列表 |
| `/log` | 操作日志 |
| `/help` | 帮助 |
| `/health` | 健康检查 |

---

## 七、部署

### 7.1 环境要求

| 组件 | 要求 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| opencode | 已安装，`opencode serve --port 14096` 运行中 |
| 笔记库服务 | 端口 14099 |
| 网络 | 能访问 open.feishu.cn（如需飞书） |

### 7.2 启动步骤

```bash
# 1. 启动 opencode serve（AI 聊天后端）
opencode serve --port 14096

# 2. 编译
mvn package -q

# 3. 启动
java -jar target/ai-assistant-2.0.0.jar

# 4. 访问
open http://localhost:6790
```

### 7.3 配置文件

`config.json` 在项目根目录（或 `ASSISTANT_CONFIG_DIR` 环境变量指定），包含运行时所有配置。

---

## 八、与 1.0 对比

| 维度 | 1.0 (Python) | 2.0 (Java) |
|------|-------------|------------|
| **技术栈** | Python + 脚本 | Java + Spring Boot |
| **AI 服务** | CLI 直接调用 | opencode serve API |
| **界面** | 命令行 | Web UI (Thymeleaf) |
| **会话管理** | 无 | 持久化 JSON 文件 |
| **数据看板** | 无 | 客户/运营看板 + 数据编辑器 |
| **飞书集成** | 仅 Webhook | Webhook + Bot API + 长连接 |
| **定时任务** | crontab | Spring @Scheduled |
| **维护成本** | 高（Python 非主栈） | 低（与用户技术栈匹配） |

---

> 文档版本：v2.1（实际实现）
> 创建日期：2026-05-30
> 最后更新：2026-05-31
> 作者：老齐