# AI 助理 v3.0

> **自带 AI 大脑，零外部依赖。**

一个完全自包含的 AI 助理应用——基于 Spring AI 2.0，原生集成 LLM 推理、工具编排、语义检索。不再需要任何外部 AI 编排服务，你的数据就在你的笔记库里。

---

## 🌟 核心定位

| 你 | AI 助理 |
|----|---------|
| 提供数据（笔记库 → JSON / Markdown） | 直接理解、分析、操作你的数据 |
| 定义规则（Prompt / 配置页） | Spring AI 2.0 原生推理 + `@Tool` 工具编排 |
| 接收结果（Web UI / 飞书） | 多端推送，定时任务 |

**一句话**：AI 助理就是大脑本身，不是桥梁。

---

## ✨ 为什么需要它

| 痛点 | 传统方案 | AI 助理 v3.0 |
|------|---------|-------------|
| **数据隐私** | SaaS 把数据传到云端 | 数据在你自己的笔记库，不出域 |
| **架构笨重** | 需要额外启动 AI 编排服务 | 零外部服务，`java -jar` 即可 |
| **响应慢** | HTTP 中转多次 | Java 直调 LLM API，最低延迟 |
| **场景单一** | 一个工具只做一件事 | 一个框架，无限场景（日报、CRM、运营...） |

---

## 💡 使用场景

```
┌────────────────────────────────────────────┐
│            AI 助理 (Spring Boot 4.1)       │
│  ┌─────────┐  ┌──────────┐  ┌───────────┐ │
│  │ Chat    │  │ @Tool    │  │ Embedding  │ │
│  │ Client  │◄─┤ 编排     │◄─┤ Model     │ │
│  │ DeepSeek│  │ NoteTools│  │ Ollama    │ │
│  └────┬────┘  └────┬─────┘  └─────┬─────┘ │
│       │            │              │        │
│       └───────┬────┘              │        │
│               │                   │        │
│         ┌─────▼─────────┐        │        │
│         │ 你的笔记库     │◄───────┘        │
│         │ JSON / Markdown│                 │
│         └───────────────┘                  │
└────────────────────────────────────────────┘
```

| 场景 | 你发送 | AI 做什么 | 结果 |
|------|--------|----------|------|
| **查数据** | "查张三的跟进记录" | 读笔记库 JSON → 搜索 → 总结 | 回复 |
| **记数据** | "记录：拜访 ABC 公司" | 解析自然语言 → 写入 JSON | 确认 |
| **更新数据** | "张三改为已签约" | 找到记录 → 更新 → 保存 | 确认 |
| **生成报告** | "生成本周周报" | 汇总数据 → @Tool 读文件 → AI 撰写 | 推送+保存 |
| **图片分析** | "分析这张截图" | 多模态识别 → 解读 → 总结 | 回复 |

---

## 🚀 快速开始

### 环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| JDK | 17+ | |
| Maven | 3.8+ | |
| Ollama | **可选** | 语义检索加速 (nomic-embed-text) |
| LLM API Key | DeepSeek 或其他兼容 API | 配置页 / 环境变量 |

### 启动

```bash
# 1. 编译
mvn package -q

# 2. 启动
java -jar target/ai-assistant-3.0.0.jar

# 3. 访问 http://localhost:6790
# 4. 进入 /config 配置 LLM API Key + 笔记库根目录
```

> 💡 **零外部服务**：不需要启动任何 opencode/claude-code 进程。

### 环境变量（可选，优先级高于 Web 配置）

```bash
export DEEPSEEK_API_KEY=sk-xxxxx
export LLM_BASE_URL=https://api.deepseek.com
export LLM_MODEL=deepseek-chat
```

---

## 📂 数据存储

> **核心原则：除了配置和日志，所有数据都来自你的笔记库。**

| 数据类型 | 存储位置 | 说明 |
|---------|---------|------|
| **业务数据** | 笔记库（用户指定目录） | JSON / Markdown，全部在用户自己的文件系统中 |
| **聊天历史** | SQLite (`memory.db`) | 操作日志级别，不混入知识库 |
| **配置信息** | 配置目录 (`config.json`) | 笔记库路径、LLM 配置、飞书凭据等 |
| **操作日志** | 配置目录 (`assistant_log.json`) | 仅操作时间、类型，不含业务数据 |

---

## 🔧 技术栈

| 类别 | 技术 |
|------|------|
| **后端** | Java 17 + Spring Boot 4.1.0 |
| **AI 框架** | Spring AI 2.0.0 (ChatClient + `@Tool` + ToolCallingAdvisor) |
| **LLM** | DeepSeek API (兼容 OpenAI 格式) |
| **嵌入模型** | Spring AI Ollama (nomic-embed-text，可选) |
| **ORM** | MyBatis-Plus 3.5.16 |
| **数据库** | SQLite (聊天记录) + JSON/Markdown (业务数据) |
| **前端** | Thymeleaf + 原生 JS |
| **IM 集成** | 飞书 Webhook + WebSocket |

---

## ✨ v3.0 新架构

v2.x 依赖两个 `opencode serve` 进程完成 AI 推理和编排，v3.0 完全去掉了这一切：

| 对比 | v2.x | v3.0 |
|------|------|------|
| 外部 AI 服务 | 需要 opencode serve x2 | **零依赖** |
| 工具编排 | opencode 的 tool-use | **`@Tool` 注解 + ToolCallingAdvisor** |
| LLM 调用 | 裸 HTTP 拼接 JSON | **ChatClient 流式 API** |
| 嵌入模型 | LangChain4j Ollama | **Spring AI OllamaEmbeddingModel** |
| 启动步骤 | 3 步（先启 opencode） | **1 步（java -jar）** |
| 代码质量 | 手写 ReAct 300 行 | **声明式 40 行** |

---

## 🏗️ 项目结构

```
src/main/java/com/laoqi/assistant/
├── AssistantApplication.java        # 启动入口
├── config/
│   ├── AppConfig.java               # @ConfigurationProperties(prefix="app")
│   └── PortHealthChecker.java       # Ollama 健康检测（仅语义检索用）
├── controller/                      # 控制器层
├── service/
│   ├── LlmService.java              # Spring AI ChatClient 封装
│   ├── NoteAssistantService.java    # ChatClient + @Tool 工具编排
│   ├── NoteTools.java               # @Tool 注解声明式工具
│   ├── SessionService.java          # 统一会话管理 + 语义检索
│   ├── OllamaEmbeddingService.java  # Spring AI Ollama 嵌入
│   ├── ConfigService.java           # 配置管理
│   ├── ReportService.java           # 日报生成
│   ├── FeishuService.java           # 飞书消息推送
│   ├── FeishuLongConnectionService.java # 飞书长连接
│   └── ...
├── entity/                          # MyBatis-Plus 实体
├── mapper/                          # MyBatis-Plus Mapper
├── model/                           # POJO 模型
├── collector/                       # 数据采集
└── util/                            # 工具类

src/main/resources/
├── application.yml                  # Spring Boot 配置
└── templates/                       # Thymeleaf 页面模板
```

---

## 📊 版本

| 版本 | 状态 | AI 引擎 | 外部依赖 |
|------|------|---------|---------|
| **v3.0** | **当前** | Spring AI 2.0 (DeepSeek) | 零依赖 |
| v2.x | 旧版 | opencode serve | 需启动两个外部进程 |

---

> **自带 AI 大脑，不连任何外部服务。**
