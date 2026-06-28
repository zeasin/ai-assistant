# 笔灵 AI — 基于 Spring AI 的个人智能体

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-brightgreen.svg)](https://spring.io/projects/spring-ai)

`AI Agent` `Spring AI` `Spring Boot` `Tool Calling` `Function Calling` `多知识库` `RAG` `Ollama` `DeepSeek` `飞书机器人` `SQLite` `Thymeleaf` `个人效率工具` `数据集管理` `日报生成` `多模态`

> **从笔记助理进化为个人智能体。知识库是记忆，工具是行动。**

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

---

## 🌟 核心定位

笔灵 AI 是一个**个人智能体系统**，不是简单的 AI 聊天工具。

它的架构围绕一个核心理念：**知识库（RAG）作为记忆，工具（Tools）作为行动，AI 自主编排两者。**

| 组件 | 角色 | 实现                                      |
|------|------|-------------------------------------------|
| 📚 **知识库** | 长期记忆 | Markdown 笔记文件，语义检索               |
| 🛠️ **工具集** | 行动能力 | 文件读写、数据集 CRUD、Git 操作等         |
| 🧠 **AI 编排** | 决策中枢 | Spring AI ChatClient + ToolCallingAdvisor |
| 📦 **数据集** | 结构化记忆 | SQLite 表，Schema 自定义，按条件查询      |

AI 能自主判断：问"我上周的笔记"→ 搜知识库；问"待修复的 Bug"→ 查数据集；问"生成日报"→ 同时搜笔记 + 查数据 → 综合输出。

---

## 🚀 功能全景

```
┌─────────────────────────────────────────────────────────────────┐
│                    笔灵 AI 个人智能体系统                           │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     AI 编排层                              │  │
│  │    ChatClient + ToolCallingAdvisor (Spring AI 2.0)        │  │
│  │    用户提问 → AI 决策 → 调用工具 → 返回结果 → 生成回复     │  │
│  └──────┬────────────────────┬──────────────────────────────┘  │
│         │                    │                                  │
│  ┌──────▼──────────┐  ┌─────▼──────────────┐                   │
│  │   NoteTools     │  │    DataTools       │                   │
│  │   文件操作工具    │  │    数据集工具       │                   │
│  │                 │  │                    │                   │
│  │ • readFile      │  │ • listDatasets    │                   │
│  │ • writeFile     │  │ • searchRecords   │                   │
│  │ • listDir       │  │ • queryRecords    │  ← 按条件精确筛选  │
│  │ • searchFiles   │  │ • addRecord       │                   │
│  │ • searchNotes   │  │ • updateRecord    │                   │
│  │                 │  │ • deleteRecord    │                   │
│  │                 │  │ • getRecord       │                   │
│  └──────┬──────────┘  └─────┬──────────────┘                   │
│         │                   │                                  │
│  ┌──────▼───────────────────▼──────────────┐                   │
│  │              数据源                      │                   │
│  │  ┌──────────────┐  ┌──────────────────┐ │                   │
│  │  │ 知识库笔记文件  │  │ 数据集 (SQLite)   │ │                   │
│  │  │ JSON/Markdown  │  │ 客户/Bug/项目等   │ │                   │
│  │  │ 非结构化记忆    │  │ 结构化数据        │ │                   │
│  │  └──────────────┘  └──────────────────┘ │                   │
│  └──────────────────────────────────────────┘                   │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    业务功能                                │  │
│  │  💬 AI 对话  📦 数据中心  📊 综合日报  🖼️ 识图            │  │
│  │  📋 任务管理  ⏰ 提醒  🧩 模块  🤖 AI 引导                │  │
│  │  📄 笔记浏览  🏠 首页看板  ⚙️ 配置  📖 日志               │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    集成通道                                │  │
│  │  🌐 Web UI (Thymeleaf)  💬 飞书 WebSocket  ⏰ 定时任务     │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

| 功能 | 说明 | 入口 |
|------|------|------|
| 💬 **AI 对话** | 工具编排 + 语义召回 + 连续对话，按 KB 隔离 | `/chat` |
| 📦 **数据中心** | 多数据集管理，Schema 定义，导入导出，AI 查询 | `/data` |
| 📊 **综合日报** | AI 读取笔记 + 查询数据集，结合生成日报 | 首页/定时 |
| 🖼️ **图片识别** | 多模态分析，结果写入笔记库 | `/image` |
| 📋 **任务管理** | 看板视图，按 KB 隔离 | `/kb/{id}/tasks` |
| ⏰ **提醒系统** | 定时提醒，飞书推送 | `/kb/{id}/reminders` |
| 🧩 **模块/科目** | 自定义业务模块，关联目录 | `/kb/{id}/modules` |
| 📄 **笔记浏览** | 文件目录浏览 + AI 分析 | `/kb/{id}/browse` |
| 🤖 **AI 引导** | AGENTS.md + 记忆文件编辑 | `/kb/{id}/ai-guide` |
| 🏠 **首页看板** | 多知识库摘要 + 日报展示 | `/` |
| ⚙️ **配置管理** | LLM / 知识库 / 系统设置 | `/config` |

---

## 🚀 快速开始

### 环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| JDK | 17+ | |
| Maven | 3.8+ | 仅编译需要 |
| LLM API Key | 必填 | DeepSeek / 商汤 / 智谱等兼容 OpenAI 格式 |
| Ollama | 可选 | 本地语义检索加速（bge-m3） |

### 启动

```bash
# 1. 编译
mvn package -q

# 2. 启动
java -jar target/ai-assistant-1.0.0.jar

# 3. 浏览器打开 http://localhost:6790
#    进入 /config 配置 LLM API Key + 笔记库根目录
```

### 支持的 LLM

兼容所有 OpenAI 格式的 API 提供商，Web 页面配置 Base URL 和 API Key 即可：

| 类型 | 说明 |
|------|------|
| 文本模型 | DeepSeek、智谱 GLM、商汤、Ollama 等任意 OpenAI 兼容接口 |
| 多模态模型 | 支持图片识别的模型（如商汤日日新），需在 Web 页面标记为 multimodal |
| Embedding 模型 | Ollama 本地 或 在线 API（如硅基流动） |

---

## ✨ 功能详解

### 1. AI 工具编排（核心能力）

AI 通过 Spring AI 的 `ToolCallingAdvisor` 自主判断何时调用工具，无需手写 ReAct 循环。

**NoteTools — 知识库文件操作**

| 工具 | 功能 | 触发场景 |
|------|------|---------|
| `readFile` | 读取笔记库文件 | AI 需要了解已有数据 |
| `writeFile` | 写入文件（先读后写防覆盖） | 创建/更新笔记记录 |
| `listDir` | 列出目录 | 探索笔记库结构 |
| `searchFiles` | 按文件名搜索 | 查找特定文件 |
| `searchNotes` | 按内容搜索 | 查找相关笔记 |

**DataTools — 数据集结构化操作**

| 工具 | 功能 | 触发场景 |
|------|------|---------|
| `listDatasets` | 列出所有数据集 | 查看有哪些可用数据 |
| `queryRecords` | 按条件精确查询 | "待修复的 Bug"、"报价阶段的客户" |
| `searchRecords` | 关键词模糊搜索 | 不确定具体字段时全文搜索 |
| `addRecord` | 添加记录 | 记录新客户、新 Bug |
| `updateRecord` | 更新记录 | 修改状态、跟进信息 |
| `deleteRecord` | 删除记录 | 移除错误数据 |
| `getRecord` | 查看单条详情 | 获取完整记录信息 |

**典型场景**：

| 你说 | AI 执行 | 结果 |
|------|---------|------|
| "查一下报价阶段的客户" | `queryRecords("客户", {"阶段":"报价"})` | 列出匹配客户 |
| "今天有哪些待修复 Bug" | `queryRecords("Bug", {"状态":"待修复"})` | 列出所有 Bug |
| "记录：拜访了 ABC 公司" | `addRecord("客户", {...})` | 写入数据集 |
| "生成本周周报" | 查数据集 + 读笔记 → 综合输出 | 保存+回复 |

### 2. 综合日报

每天 09:00 定时生成，并发送到飞书群，也支持手动触发，支持按笔记库开启关闭。

**流程**：
1. AI 用 `listDatasets` + `queryRecords` 查询数据集中的任务/Bug/项目状态
2. AI 用 `searchNotes` + `readFile` 读取笔记文件中的工作记录
3. 两者结合生成完整日报
4. 可选通过飞书卡片推送

**特点**：
- 非结构化数据（笔记）+ 结构化数据（数据集）融合
- 自定义提示词模板
- 日报保存到 SQLite，自动清理 7 天前记录

### 3. 数据中心（数据集系统）

结构化数据管理，不依赖笔记文件。

**核心设计**：
- 记录分两部分：固定字段（编号、类型、状态、创建时间、更新时间）+ 动态字段（Schema 定义）
- Schema 自由定义：字段名、类型、下拉选项
- 数据存储在 SQLite，不走笔记库文件

**功能**：
- 数据集 CRUD（名称、描述、Schema）
- 记录增删改查
- 数据导入：JSON、Excel
- AI 辅助查询：`queryRecords` 按条件精确筛选

**使用示例**：
- 建一个"客户跟进"数据集，字段：公司名、联系人、需求、阶段、跟进时间
- 建一个"Bug追踪"数据集，字段：标题、项目、优先级、状态
- 然后直接问 AI："本周有哪些客户在报价阶段？"、"优先级高的 Bug 有哪些？"

### 4. 多知识库系统

一个实例同时管理多个知识库（工作/学习/项目等），数据完全隔离。

| 维度 | 隔离方式 |
|------|---------|
| 聊天历史 | SQLite `kb_id` + 向量召回限定 `kb_id` |
| 模块/科目 | SQLite `kb_id` 过滤 |
| AI 记忆 | `{notesDir}/AI/记忆/` 隔离 |
| 任务/提醒 | `{notesDir}/AI/任务/` + `{notesDir}/AI/提醒/` 隔离 |
| 文件操作 | NoteTools 路径基于当前 KB |

### 5. 飞书集成

| 方式 | 说明 |
|------|------|
| Webhook 推送 | 日报推送、提醒推送 |
| WebSocket 长连接 | 双向实时通信，AI 自动回复群消息 |

---

## 🏗️ 技术架构

### 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.1 |
| AI 框架 | Spring AI 2.0 (ChatClient + @Tool + ToolCallingAdvisor) |
| LLM | DeepSeek（OpenAI 兼容协议，可切换） |
| 嵌入模型 | Ollama (bge-m3) |
| ORM | MyBatis-Plus 3.5.16 |
| 数据库 | SQLite |
| 前端 | Thymeleaf + 原生 JS |
| IM 集成 | 飞书 OpenAPI SDK + WebSocket |
| Excel | Apache POI 5.4.0 |

### AI 调用链路

```
                     ┌────────────────────────────┐
                     │   LlmConfigResolver        │
                     │   优先级: Web > 环境变量     │
                     └──────────┬─────────────────┘
                                │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                     ▼
   ┌──────────┐       ┌──────────────────┐   ┌──────────────┐
   │LlmService│       │ NoteAssistantSvc │   │AgentAnalysis │
   │ 直连 LLM  │       │  对话 + 工具编排   │   │ 日报/分析生成  │
   └──────────┘       └────────┬─────────┘   └──────┬───────┘
                              │                     │
                     ┌────────▼─────────┐           │
                     │  ToolRegistry    │           │
                     │  @Tool 自动发现   │           │
                     └──┬───────┬──────┘           │
                        │       │                  │
                 ┌──────▼──┐ ┌──▼────────┐         │
                 │NoteTools│ │DataTools  │         │
                 │ 文件操作  │ │ 数据集操作 │         │
                 └─────────┘ └───────────┘         │
                              │                     │
                              └─────────┬───────────┘
                                        ▼
                              ┌────────────────────┐
                              │ DeepSeekChatModel  │
                              │ (OpenAI 兼容协议)   │
                              └────────┬───────────┘
                                       ▼
                              LLM API (DeepSeek/商汤/智谱/Ollama)
```

### 工具自注册机制

`ToolRegistry` 通过反射扫描所有 `@Component` 中的 `@Tool` 方法，自动注册到 `ChatClient`。新增工具只需：

1. 写一个 `@Component` 类
2. 加 `@Tool` 方法
3. 无需修改任何 Service 或 Controller

---

## 📂 数据存储

| 数据类型 | 存储位置 | 说明 |
|---------|---------|------|
| 业务数据 | 知识库 notesDir | JSON / Markdown，本地文件系统 |
| 聊天记录 | `memory.db` (SQLite) | `sessions` + `messages` 表 |
| 对话向量 | `memory.db` (SQLite) | `turn_embeddings` 表 |
| 知识库 | `memory.db` (SQLite) | `knowledge_bases` 表 |
| 模块/科目 | `memory.db` (SQLite) | `modules` 表 |
| 数据集 | `memory.db` (SQLite) | `data_center_datasets` + `data_center_records` 表 |
| 日报 | `memory.db` (SQLite) | `ai_analysis` 表 |
| 配置 | `config.json` | LLM 凭据、飞书配置、全局设置 |

---

## 🏗️ 项目结构

```
src/main/java/com/laoqi/assistant/
├── AssistantApplication.java            # 启动入口
│
├── controller/                          # Web 控制器
│   ├── ChatController.java              # AI 对话（SSE 流式）
│   ├── IndexController.java             # 首页
│   ├── ConfigController.java            # 配置页
│   ├── KnowledgeBaseController.java     # 知识库 CRUD + 日报
│   ├── DataSetController.java           # 数据集 REST API
│   ├── DataPageV2Controller.java        # 数据中心 v2 页面
│   ├── DataCenterPageController.java    # 数据中心 v1 页面
│   ├── DataController.java              # 数据 API
│   ├── ImageRecognitionController.java  # 图片识别
│   ├── TaskController.java              # 任务管理
│   ├── ReminderController.java          # 提醒管理
│   ├── ModuleController.java            # 模块系统
│   ├── AiGuideController.java           # AI 引导
│   ├── LogController.java               # 操作日志
│   ├── HelpController.java              # 帮助页
│   ├── CollectorController.java         # 采集器 API
│   ├── CollectorPageController.java     # 采集器页面
│   ├── HealthController.java            # 健康检查
│   └── GlobalModelAdvice.java           # 全局模板变量
│
├── service/                             # 业务服务
│   ├── NoteAssistantService.java        # AI 对话编排（ChatClient + Tools）
│   ├── AgentAnalysisService.java        # 自动分析（日报/分析）
│   ├── ReportService.java               # 日报生成
│   ├── LlmService.java                  # LLM 直连
│   ├── LlmConfigResolver.java           # LLM 配置解析
│   ├── ToolRegistry.java                # 工具自注册中心
│   ├── NoteTools.java                   # 文件操作工具
│   ├── DataTools.java                   # 数据集操作工具
│   ├── KnowledgeBaseService.java        # 多知识库管理
│   ├── SessionService.java              # 会话管理 + 语义检索
│   ├── SchedulerService.java            # 定时任务
│   ├── ReminderService.java             # 提醒管理
│   ├── TaskService.java                 # 任务管理
│   ├── ModuleService.java               # 模块系统
│   ├── ModuleDataService.java           # 模块数据操作
│   ├── ConfigService.java               # 配置读写
│   ├── TodoService.java                 # 待办解析
│   ├── LogService.java                  # 操作日志
│   ├── FeishuService.java               # 飞书推送
│   ├── FeishuLongConnectionService.java # 飞书长连接
│   └── db/                              # MyBatis-Plus DB 服务
│
├── datacenter/                          # 数据中心模块
│   ├── DataSetService.java              # 数据集核心业务
│   ├── DataSetImportService.java        # 数据导入
│   ├── DataModuleService.java           # 数据模块
│   └── model/                           # 数据模型
│
├── entity/                              # MyBatis-Plus 实体
├── mapper/                              # MyBatis-Plus Mapper
├── model/                               # POJO 模型
└── util/                                # 工具类

src/main/resources/
└── templates/
    ├── 2.0/                             # v2 UI（当前主要界面）
    │   ├── layout.html
    │   ├── data.html
    │   ├── data-module.html
    │   └── ...
    └── 1.0/                             # v1 界面
        ├── layout.html
        └── ...
```

---

## ⚙️ 配置参考

```yaml
server:
  port: 6790
  address: 0.0.0.0

spring:
  datasource:
    url: jdbc:sqlite:${ASSISTANT_CONFIG_DIR:.}/memory.db
    driver-class-name: org.sqlite.JDBC
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:dummy}   # 仅用于启动校验，实际 Key 由 Web 页面管理
    retry:
      max-attempts: 3
      backoff:
        initial-interval: 2s

app:
  config-dir: ${ASSISTANT_CONFIG_DIR:.}
  timezone: Asia/Shanghai
  max-history-chars: 6000
```

> **说明**：`application.yml` 中不配置 embedding 模型，通过 Web 页面动态管理（Ollama 本地 / API 在线均可）。LLM 模型支持多 profile（文本/多模态），均在 Web 页面配置。完整配置见 `src/main/resources/application.yml`。

---

## 🤝 数据隐私

- 所有数据保存在**本地**，绝不上传云端
- LLM API 只发送当前对话内容，不传输完整笔记库
- Ollama 检索完全本地运行
- 源码开源，可自行审计

---

> **知识库是记忆，工具是行动，AI 是大脑。**
