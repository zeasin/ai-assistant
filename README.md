# AI 助理 v1.0.0

> **自带 AI 大脑，零外部依赖。多知识库，一例运行。**

一个完全自包含的 AI 助理应用——基于 **Spring AI 2.0** + **Spring Boot 4.1**，原生集成多知识库管理、AI 对话（LLM 推理、`@Tool` 工具编排、语义向量检索）、编程 AI（Pi CLI 代码自动排查）、多模态图片识别。支持同时管理多个知识库（如工作、学习），数据、对话、记忆、模块完全隔离。`java -jar` 一步启动，你的数据就在你自己的笔记库里。

---

## 目录

- [核心定位](#-核心定位)
- [功能全景](#-功能全景)
- [快速开始](#-快速开始)
- [功能详解](#-功能详解)
- [技术架构](#-技术架构)
- [数据存储](#-数据存储)
- [项目结构](#-项目结构)
- [配置参考](#-配置参考)
- [版本演进](#-版本演进)

---

## 🌟 核心定位

| 你 | AI 助理 |
|----|---------|
| 提供数据（笔记库 → JSON / Markdown） | 直接理解、分析、操作你的数据 |
| 定义规则（Prompt / 配置页） | Spring AI 2.0 原生推理 + `@Tool` 工具编排 |
| 接收结果（Web UI / 飞书 / 定时推送） | 多端推送，定时任务，自动化工作流 |

**一句话**：AI 助理就是大脑本身，不是桥梁。

---

## 🚀 功能全景

```
┌─────────────────────────────────────────────────────────────┐
              │  AI 助理 v1.0 (Spring Boot 4.1 + Spring AI 2.0)  │
  │                                                             │
  │  ┌──────────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
  │  │  多知识库管理  │  │ 知识库AI │  │ @Tool    │  │ 语义检索│ │
  │  │  工作 / 学习  │──┤ SSE 流式  │──┤ 工具编排  │──┤ Ollama │ │
  │  │  ... 完全隔离  │  │ 连续对话  │  │ NoteTools│  │ Embed  │ │
  │  └──────┬───────┘  └────┬─────┘  └────┬─────┘  └────┬───┘ │
  │         │               │             │             │      │
  │         │          ┌────▼─────────────▼──────────────▼───┐ │
  │         │          │   知识库笔记库 (当前 KB 的目录)      │ │
  │         │          │   JSON / Markdown / 图片             │ │
  │         │          └─────────────────────────────────────┘ │
  │         │                                                   │
  │  ┌──────▼──────────────────────────────────────────────┐   │
  │  │              全局共享功能                             │   │
  │  │  🏠 首页  💬 对话  🖼️ 识图  📦 数据  ⚙️ 配置         │   │
  │  └──────────────────────────────────────────────────────┘   │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │           飞书集成 (WebSocket 长连接)                  │   │
  │  │   消息接收 → AI 处理 → 自动回复 / 定时推送             │   │
  │  └──────────────────────────────────────────────────────┘   │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │   💻 编程 AI (独立飞书机器人 + Pi CLI 代码排查)       │   │
  │  └──────────────────────────────────────────────────────┘   │
  └─────────────────────────────────────────────────────────────┘
```

| 功能 | 说明 | 入口 |
|------|------|------|
| 🏠 **首页** | 全局状态 + 多知识库摘要看板，按当前 KB 展示详情 | `/` |
| 💬 **AI 对话** | 连续对话（去 session）+ SSE 流式 + 工具编排 + 历史语义召回，按 KB 隔离 | `/chat` |
| 🗂️ **知识库系统** | 一级导航菜单，每个 KB 独立管理模块/任务/提醒/笔记/配置 | `/kb/{id}` |
| 📄 **笔记浏览** | 浏览当前知识库文件目录与内容 | 各 KB 内容页 |
| 📋 **任务/学习计划** | 任务看板，按 KB 隔离，名称可自定义（任务/学习计划） | `/kb/{id}/tasks` |
| ⏰ **提醒/复习提醒** | 动态提醒，按 KB 隔离，名称可自定义（提醒/复习提醒） | `/kb/{id}/reminders` |
| 🧩 **模块/科目** | 自定义业务模块，按 KB 分离（工作模块/学习科目） | `/kb/{id}/modules` |
| 🖼️ **图片识别** | 基于当前 KB 路径的多模态 AI 分析 | `/image` |
| 📦 **数据中心** | 多数据集管理，Schema 定义，Excel/JSON/Markdown 导入 | `/datacenter` |
| ⚙️ **配置管理** | 全局配置 LLM / 知识库 / 系统设置 | `/config` |
| 🧠 **AI 引导** | 当前 KB 的 AGENTS.md + AI 记忆编辑 | `/ai-guide` |
| 📜 **操作日志** | 查看系统操作记录 | `/log` |
| 🏥 **健康检查** | 服务状态 API | `/health` |
| 💻 **编程 AI** | 独立飞书机器人 + Pi CLI 代码自动排查 | `/coding` |

---

## 🚀 快速开始

### 环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| JDK | 17+ | |
| Maven | 3.8+ | 仅编译需要 |
| LLM API Key | 必填 | DeepSeek / 商汤 / 智谱等兼容 OpenAI 格式的 API |
| Ollama | **可选** | 本地语义检索加速（nomic-embed-text），不装也能用 |

### 启动

```bash
# 1. 编译
mvn package -q

# 2. 启动（一条命令）
java -jar target/ai-assistant-1.0.0.jar

# 3. 打开浏览器
# http://localhost:6790
# 进入 /config 配置 LLM API Key + 笔记库根目录即可使用
```

> 💡 **零外部服务**：不需要启动任何 opencode / claude-code 等外部进程。

### 环境变量（可选，优先级高于 Web 配置）

```bash
# 方式一：通过 LLM_API_KEY（通用）
export LLM_API_KEY=sk-xxxxx
export LLM_BASE_URL=https://api.deepseek.com
export LLM_MODEL=deepseek-chat

# 方式二：通过 DEEPSEEK_API_KEY（DeepSeek 专用）
export DEEPSEEK_API_KEY=sk-xxxxx
```

### 支持的 LLM 提供商

由于底层使用 Spring AI 的 OpenAI 兼容协议，任何兼容的 API 都可以接入：

| 提供商 | Base URL | 示例模型 |
|--------|----------|---------|
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| 商汤 SenseNova | `https://token.sensenova.cn/v1` | `SenseChat` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5` |

在 `/config` 页面修改 Base URL 和 Model 即可切换，无需重启。

---

## ✨ 功能详解

### 1. 多知识库系统（`/kb/{id}`）

v1.0 核心升级——一个实例同时管理多个知识库，完全隔离。

**导航结构**：

```
[AI 助理]  首页  对话  识图  工作▾  学习▾  编程AI  配置  日志  帮助
                               │      │
                    ┌──────┴──┐│      │┌──────┴──┐
                    │📋 任务   ││      ││📋 学习计划│
                    │🔔 提醒   ││      ││🔔 复习提醒│
                    │🤖 模块   ││      ││🤖 科目   │
                    │📄 笔记   ││      ││📄 笔记   │
                    │⚙️ 配置   ││      ││⚙️ 配置   │
                    └─────────┘│      │└─────────┘
```

**隔离机制**：

| 维度 | 隔离方式 |
|------|---------|
| 聊天历史 | `sessions.kb_id` + 向量召回限定 `kb_id` |
| 模块/科目 | `modules.kb_id` 过滤 |
| AI 记忆 | `{notesDir}/AI/记忆/` 隔离 |
| 任务/提醒 | `{notesDir}/AI/任务/` + `{notesDir}/AI/提醒/` 隔离 |
| 文件操作 | NoteTools 路径基于当前 KB |

**菜单名可配置**：每个 KB 可自定义二级菜单名称（如"任务"→"学习计划"），共享同一套后端服务。

### 2. AI 对话（`/chat`）

连续对话模式（去 session），按当前知识库加载聊天历史，SSE 流式输出。

**工作流**：

```
用户输入 → 注入历史上下文（语义召回） → ChatClient + @Tool
                                             ↓
                              AI 自动判断是否调用工具
                              (readFile / writeFile / listDir / searchFiles)
                                             ↓
                              工具执行 → 结果回传 AI → 最终回复
```

**历史记忆**：
- 当前 KB：自动注入最近 3 轮对话
- 跨会话语义召回：通过 Ollama 向量检索，从当前 KB 的所有历史对话中找出最相关的 8 轮上下文
- 无 Ollama 时回退为简单时间窗口（最近 N 轮）

**对话导出**：支持将对话导出为 Markdown 保存至笔记库。

---

### 2. AI 工具编排（`@Tool`）

AI 通过 Spring AI 的 `ToolCallingAdvisor` 自动判断何时调用工具，无需手写 ReAct 循环。

| 工具 | 功能 | 触发场景 |
|------|------|---------|
| `readFile` | 读取笔记库文件内容 | AI 需要了解数据格式或历史内容 |
| `writeFile` | 写入文件（先读后写，防覆盖） | 创建/更新 JSON 记录、Markdown 文档 |
| `listDir` | 列出目录内容 | AI 需要探索笔记库结构 |
| `searchFiles` | 按文件名关键词搜索 | 查找特定客户、BUG、日报等 |

**典型场景**：

| 你说 | AI 自动执行 | 结果 |
|------|-----------|------|
| "查张三的跟进记录" | `searchFiles("张三")` → `readFile(...)` → 总结 | 文字回复 |
| "记录：拜访 ABC 公司" | `readFile("AGENTS.md")` → `readFile("客户/data.json")` → `writeFile(...)` | 确认已写入 |
| "张三改为已签约" | `readFile(...)` → 修改 → `writeFile(...)` | 确认已更新 |
| "生成本周周报" | 读取多文件 → 汇总 → AI 撰写 → `writeFile(...)` | 保存+回复 |

---

### 3. 综合日报（`/`）

每天自动从笔记库生成综合日报，支持自定义提示词。

**日报流程**：
1. 读取 `AI/综合日报/分析提示词.md`（可在页面编辑）
2. 汇总笔记库中的工作数据（客户、开发、文章等）
3. AI 生成结构化日报
4. 可选通过飞书 Webhook 推送

**定时任务**：
- 每日 09:00（Asia/Shanghai）自动生成并推送日报

---

### 4. 数据中心（`/datacenter`）

集中管理结构化数据集，支持多数据集、Schema 定义、批量导入。

**功能**：
- 创建数据集（定义 Schema：字段名、类型、描述）
- 数据导入：Excel（.xlsx）、JSON、Markdown、文本
- AI 辅助导入：自动解析非结构化数据
- 记录 CRUD（最多 10,000 条/数据集）
- 数据去重（基于 content hash）

**存储**：SQLite 表 `data_center_datasets` + `data_center_records`

---

### 5. 数据采集器（`/collector`）

定时从外部数据源采集数据，AI 解析后写入数据中心或笔记库。

**功能**：
- 定义采集任务（URL/来源 + Cron 表达式）
- 动态调度（`CollectorScheduler`）
- AI 解析采集到的原始数据
- 采集日志（最多 100 条）与结果（最多 50 条）

---

### 6. 图片识别（`/image`）

基于多模态 Vision API 的图片分析功能。

**功能**：
- 自动扫描笔记库中的图片（PNG/JPG/GIF/BMP/WebP）
- 缩略图浏览
- AI 多模态分析：上传图片后 AI 返回文字描述/解读

---

### 7. 飞书集成

两种方式接入飞书：

**Webhook 推送**（出站）：
- 日报推送、提醒推送
- 配置页填写 Webhook URL 即可

**WebSocket 长连接**（双向）：
- 通过飞书 OpenAPI SDK 建立长连接
- 实时接收群内消息
- AI 处理后自动回复（支持单聊和群聊）
- 去重机制防止重复处理

**配置步骤**：
1. 在飞书开放平台创建应用，获取 App ID / App Secret
2. 在配置页填写凭据并选择目标群
3. 开启"消息接收"开关

---

### 8. 模块/科目系统（`/kb/{id}/modules`）

每个知识库独立管理自己的模块（工作 KB 叫"模块"，学习 KB 叫"科目"），每个模块对应知识库中的一个目录。

**模块定义**：
- `id`：唯一标识
- `name`：显示名称（如"项目"、"数学"）
- `dir`：知识库中的相对路径
- `icon`：图标
- `prompt`：模块专属 AI 提示词
- `dataFiles`：关联的数据文件列表

---

### 9. 任务管理（`/kb/{id}/tasks`）

轻量级任务看板，按知识库隔离，数据保存在各自的 `{notesDir}/AI/任务/data.json`。学习 KB 可自定义显示名称为"学习计划"。

**功能**：
- 任务创建（标题、描述、优先级、截止日期）
- 状态管理
- Web UI 看板视图

---

### 10. 动态提醒（`/kb/{id}/reminders`）

基于时间的动态提醒系统，按知识库隔离，数据保存在各自的 `{notesDir}/AI/提醒/reminders.json`。学习 KB 可自定义显示名称为"复习提醒"。

**默认提醒**（工作 KB）：
- 每天 18:00 下班日报提醒（自动创建）

**提醒类型**：
- `daily`：每天定时
- 自定义 Cron 表达式
- 触发时通过飞书推送

---

### 11. 待办清单（首页展示）

自动解析笔记库中的 `代办.md` 文件，按优先级分类展示：

- 🔴 高优先级
- 🟡 中优先级
- 🟢 低优先级 / 随缘
- 🔄 每日循环
- 📌 临时提醒

---

## 🏗️ 技术架构

### 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **后端框架** | Spring Boot | 4.1.0 |
| **AI 框架** | Spring AI (ChatClient + `@Tool` + ToolCallingAdvisor) | 2.0.0 |
| **LLM** | DeepSeek（OpenAI 兼容协议，可切换任意厂商） | — |
| **嵌入模型** | Spring AI Ollama (nomic-embed-text) | — |
| **ORM** | MyBatis-Plus (Spring Boot 4 Starter) | 3.5.16 |
| **数据库** | SQLite | — |
| **前端** | Thymeleaf + 原生 JS | — |
| **IM 集成** | 飞书 OpenAPI SDK + WebSocket | 2.3.2 |
| **Markdown** | commonmark + gfm-tables 扩展 | 0.24.0 |
| **Excel** | Apache POI | 5.4.0 |
| **文件操作** | Commons IO | 2.18.0 |

### AI 调用链路

```
                    ┌────────────────────────────┐
                    │   LlmConfigResolver        │
                    │   优先级: Web > 环境变量     │
                    └──────────┬─────────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
       ┌──────────┐    ┌──────────────┐  ┌──────────────┐
       │LlmService│    │NoteAssistant │  │CollectorSvc  │
       │  直连LLM  │    │  工具编排     │  │  采集解析     │
       └─────┬────┘    └──────┬───────┘  └──────┬───────┘
             │                │                 │
             └────────────────┼─────────────────┘
                              ▼
                    ┌────────────────────┐
                    │ DeepSeekChatModel  │
                    │ (OpenAI 兼容协议)   │
                    └────────┬───────────┘
                             ▼
                    LLM API (DeepSeek/商汤/智谱...)
```

### 语义检索流程

```
用户提问 → Ollama embed(text) → 768维向量
                                      ↓
                              全库 turn_embeddings 表
                                      ↓
                         余弦相似度排序 (threshold ≥ 0.5)
                                      ↓
                    取 Top 8 轮（每会话最多 4 轮）
                                      ↓
                         注入为历史上下文给 AI
```

### ChatClient 懒初始化 + 配置热更新

`LlmService` 和 `NoteAssistantService` 均使用双重检查锁懒初始化 ChatClient，
当 `/config` 页面修改 LLM 配置后，下次请求会自动重建客户端，无需重启。

---

## 📂 数据存储

> **核心原则：除了 SQLite 元数据，所有业务数据都在你的笔记库里。**

| 数据类型 | 存储位置 | 说明 |
|---------|---------|------|
| **业务数据** | 各知识库的 notesDir | JSON / Markdown，全部在本地文件系统 |
| **聊天记录** | `memory.db` (SQLite) | `sessions` + `messages` 表（含 `kb_id`） |
| **对话向量** | `memory.db` (SQLite) | `turn_embeddings` 表，按 `kb_id` 隔离 |
| **知识库** | `memory.db` (SQLite) | `knowledge_bases` 表（名称 + 路径 + 菜单配置） |
| **模块/科目** | `memory.db` (SQLite) | `modules` 表（含 `kb_id`） |
| **数据集** | `memory.db` (SQLite) | `data_center_datasets` + `data_center_records` 表 |
| **配置信息** | `config.json`（配置目录） | LLM 凭据、飞书配置、全局设置 |
| **操作日志** | `assistant_log.json`（配置目录） | 操作时间、类型、结果 |

---

## 🏗️ 项目结构

```
src/main/java/com/laoqi/assistant/
├── AssistantApplication.java           # Spring Boot 启动入口
│
├── config/                             # 配置层
│   ├── AppConfig.java                  # @ConfigurationProperties(prefix="app")
│   ├── MybatisPlusConfig.java          # MyBatis-Plus 配置
│   ├── PortHealthChecker.java          # Ollama 健康检测（语义检索用）
│   └── SchedulingConfig.java           # 定时任务配置
│
├── controller/                         # Web 控制器（共 19 个）
│   ├── ChatController.java             # /chat — AI 对话（SSE 流式）
│   ├── IndexController.java            # / — 多 KB 首页
│   ├── ConfigController.java           # /config — 全局配置页
│   ├── ApiConfigController.java        # /api/config/* — 配置 API
│   ├── KnowledgeBaseController.java    # /kb/* — 知识库页面
│   ├── DataCenterPageController.java   # /datacenter — 数据中心页
│   ├── CollectorPageController.java    # /collector — 采集器页
│   ├── ImageRecognitionController.java # /image — 图片识别
│   ├── TaskController.java             # /kb/{id}/tasks — 任务看板
│   ├── ReminderController.java         # /kb/{id}/reminders — 提醒管理
│   ├── ModuleController.java           # /kb/{id}/modules — 模块系统
│   ├── BrowseController.java           # /kb/{id}/notes — 笔记浏览
│   ├── DataEditorController.java       # /data-editor — 数据编辑器
│   ├── AiProxyController.java          # /api/ai/* — AI 辅助录入
│   ├── AiGuideController.java          # /kb/{id}/config — AI 引导
│   ├── LogController.java              # /log — 日志查看
│   ├── HelpController.java             # /help — 帮助页
│   ├── HealthController.java           # /health — 健康检查
│   ├── GlobalModelAdvice.java          # 全局模板变量注入（含 KB 信息）
│   └── BaseController.java             # 基础控制器
│
├── service/                            # 业务服务
│   ├── KnowledgeBaseService.java       # 多知识库管理（CRUD + 当前 KB 上下文）
│   ├── LlmService.java                 # LLM 直连（ChatClient 封装）
│   ├── LlmConfigResolver.java          # LLM 配置统一解析
│   ├── NoteAssistantService.java       # AI 编排（ChatClient + @Tool）
│   ├── NoteTools.java                  # @Tool 声明式工具集（基于当前 KB）
│   ├── SessionService.java             # 会话管理 + 语义检索（含 kb_id）
│   ├── OllamaEmbeddingService.java     # Spring AI Ollama 嵌入
│   ├── ChatSessionService.java         # Web 聊天会话
│   ├── FeishuChatSessionService.java   # 飞书聊天会话
│   ├── FeishuService.java              # 飞书消息推送
│   ├── FeishuLongConnectionService.java# 飞书 WebSocket 长连接
│   ├── ReportService.java              # 日报生成（基于当前 KB）
│   ├── SchedulerService.java           # 定时任务（日报/提醒）
│   ├── ReminderService.java            # 动态提醒管理（按 KB 隔离）
│   ├── TodoService.java                # 待办清单解析
│   ├── TaskService.java                # 任务管理（按 KB 隔离）
│   ├── ModuleService.java              # 模块系统（按 KB 过滤）
│   ├── ModuleDataService.java          # 模块数据操作
│   ├── ConfigService.java              # 配置读写（委托 KB 获取路径）
│   ├── LogService.java                 # 操作日志
│   └── db/                             # MyBatis-Plus DB 服务
│       ├── KnowledgeBaseDbService.java
│       ├── SessionDbService.java
│       ├── MessageDbService.java
│       ├── TurnEmbeddingDbService.java
│       ├── DataSetDbService.java
│       └── DataSetRecordDbService.java
│
├── datacenter/                         # 数据中心模块
│   ├── DataSetController.java          # REST API
│   ├── DataSetService.java             # 数据集管理
│   ├── DataSetImportService.java       # 数据导入
│   ├── DataCenterPageController.java   # 页面控制器
│   └── model/                          # 数据模型
│       ├── DataSet.java
│       ├── DataSchema.java
│       ├── DataField.java
│       ├── DataSetInfo.java
│       └── ImportConfig.java
│
├── collector/                          # 数据采集模块
│   ├── CollectorService.java           # 采集逻辑
│   ├── CollectorScheduler.java         # 动态 Cron 调度
│   ├── CollectorController.java        # REST API
│   ├── CollectorPageController.java    # 页面控制器
│   └── model/
│       ├── CollectorTask.java
│       ├── CollectorLog.java
│       └── CollectorResult.java
│
├── entity/                             # MyBatis-Plus 实体
│   ├── KnowledgeBaseEntity.java        # 多知识库
│   ├── SessionEntity.java
│   ├── MessageEntity.java
│   ├── TurnEmbeddingEntity.java
│   ├── DataSetEntity.java
│   └── DataSetRecordEntity.java
│
├── mapper/                             # MyBatis-Plus Mapper
│   ├── KnowledgeBaseMapper.java        # 多知识库
│   ├── SessionMapper.java
│   ├── MessageMapper.java
│   ├── TurnEmbeddingMapper.java
│   ├── DataSetMapper.java
│   └── DataSetRecordMapper.java
│
├── model/                              # POJO 模型
│   ├── Config.java
│   ├── ChatSession.java
│   ├── TaskData.java
│   ├── ReminderData.java
│   ├── ModuleDefinition.java
│   ├── CustomerData.java
│   ├── LogEntry.java
│   ├── AjaxResult.java
│   └── TableDataInfo.java
│
└── util/                               # 工具类
    ├── FileUtil.java
    ├── TimeUtil.java
    ├── MarkdownUtil.java
    └── ThymeleafUtil.java

src/main/resources/
├── application.yml                     # Spring Boot 配置
└── templates/                          # Thymeleaf 页面模板
    ├── layout.html                     # 公共布局（含 KB 菜单）
    ├── index.html                      # 多 KB 首页
    ├── chat.html                       # AI 对话（连续对话）
    ├── config.html                     # 全局配置
    ├── kb/                             # 知识库页面
    │   ├── overview.html               # KB 概览
    │   ├── tasks.html                  # 任务/学习计划
    │   ├── reminders.html              # 提醒/复习提醒
    │   ├── modules.html                # 模块/科目
    │   ├── notes.html                  # 笔记浏览
    │   └── config.html                 # KB 配置（含 AI 指南）
    ├── datacenter.html                 # 数据中心
    ├── collector.html                  # 采集器
    ├── image.html                      # 图片识别
    ├── data_editor.html                # 数据编辑器
    ├── log.html                        # 操作日志
    ├── help.html                       # 帮助页
    └── (历史页面移至 KB 内)              # module/tasks/reminders/browse/ai_guide
```

---

## ⚙️ 配置参考

### application.yml（服务基础配置）

```yaml
server:
  port: 6790

app:
  config-dir: ${ASSISTANT_CONFIG_DIR:.}    # 配置目录
  timezone: Asia/Shanghai
  max-history-chars: 6000

spring:
  datasource:
    url: jdbc:sqlite:${ASSISTANT_CONFIG_DIR:.}/memory.db
  ai:
    ollama:
      base-url: http://127.0.0.1:11434
      embedding:
        options:
          model: nomic-embed-text
```

### config.json（通过 Web 配置页管理）

```json
{
  "notesDir": "D:\\projects\\your_notes",
  "feishuWebhookUrl": "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
  "feishuAppId": "cli_xxx",
  "feishuAppSecret": "xxx",
  "feishuChatId": "oc_xxx",
  "feishuPollingEnabled": false,
  "codingFeishuAppId": "cli_xxx",
  "codingFeishuAppSecret": "xxx",
  "codingFeishuChatId": "oc_xxx",
  "codingProjectDir": "D:\\projects\\some_code",
  "codingPiEnabled": true,
  "codingPiTimeout": 300,
  "embeddingModel": "nomic-embed-text",
  "embeddingBaseUrl": "http://127.0.0.1:11434",
  "embeddingApiKey": "",
  "embeddingProvider": "Ollama"
}
```

> 注意：v1.0 中 `notesDir` 在首次启动时自动迁移到 `knowledge_bases` 表，后续由知识库管理页面控制。
> 模块定义已迁移至 SQLite `modules` 表（含 `kb_id`）。

---

## 📊 版本演进

| 版本 | 描述 |
|------|------|
| **v0.1** | AI 助理 Python 原型 |
| **v0.2** | Java 借助 opencode serve 实现 AI 助理 |
| **v0.3** | Spring AI 2.0 接管 OpenCode AI 大脑 |
| **v0.4** | 加入编程 AI（Pi CLI 飞书机器人 + 代码排查），双 AI 助理 |
| **v1.0**（当前） | **多知识库架构**：一个实例同时管理多个知识库（工作/学习等），数据、对话、记忆、模块完全隔离；连续对话去 session；知识库独立管理模块/任务/提醒；首页多 KB 摘要看板 |

### v1.0 vs v0.4 关键升级

| 对比维度 | v0.4 | v1.0 |
|---------|------|------|
| **知识库数量** | 单知识库 | **多知识库（工作/学习等）** |
| **数据隔离** | 不隔离 | **聊天/模块/记忆/文件 全隔离** |
| **对话方式** | 多会话管理 | **连续对话（去 session）** |
| **导航结构** | 平铺导航 | **知识库一级菜单 + 各自二级菜单** |
| **任务/提醒** | 全局单一 | **按知识库隔离，名称可自定义** |
| **菜单名称** | 固定 | **知识库各自配置** |
| **首页** | 单 KB 日报 | **多 KB 摘要看板 + 当前 KB 详情** |


---

## 🤝 数据隐私

- 所有业务数据（客户、日报、任务等）都保存在**你自己的笔记库**中，绝不上传云端
- LLM API 调用只发送当前对话内容，不传输笔记库原始数据
- Ollama 语义检索完全本地运行，向量不出本机
- 聊天记录（SQLite）仅作为操作日志，不混入知识库

---

> **自带 AI 大脑，一步启动，你的数据始终在你手里。**
