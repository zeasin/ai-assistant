# LLM 编排方案 — LangChain4j 自建

用 LangChain4j 替代 opencode / pi，Java 直接管理 LLM 调用、记忆、工具编排。

## 目录

1. [核心概念](#1-核心概念)
2. [最小实现：直调 LLM](#2-最小实现直调-llm)
3. [加入记忆（Memory）](#3-加入记忆memory)
4. [加入工具（Tool）—— 编排核心](#4-加入工具tool--编排核心)
5. [两种编排模式的选择](#5-两种编排模式的选择)
6. [知识库场景的编排方案](#6-知识库场景的编排方案)
7. [流式输出（SSE）](#7-流式输出sse)
8. [与现有架构的整合路径](#8-与现有架构的整合路径)

---

## 1. 核心概念

### 什么是编排

LLM 原生只是"根据输入生成文本"。编排就是让你的程序能在 LLM 思考过程中**介入**——读文件、查数据库、执行命令，把结果再喂给 LLM，让它继续推理。

```
无编排：用户 → prompt → LLM → 回答

有编排：用户 → prompt → LLM → "我需要读文件 X"
                        ↓
                  Java 读文件 X → 结果给 LLM
                        ↓
                  LLM → "还需要搜索 Y"
                        ↓
                  Java 搜索 Y → 结果给 LLM
                        ↓
                  LLM → 最终回答
```

### LangChain4j 的三个核心组件

| 组件 | 作用 | 类比 pi/opencode 的什么 |
|------|------|----------------------|
| **ChatLanguageModel** | 调 LLM API | 模型调用 |
| **ChatMemory** | 多轮对话记忆 | session 上下文 |
| **Tool（@Tool）** | 定义 AI 可调用的函数 | read/write/bash 工具 |

---

## 2. 最小实现：直调 LLM

这是基础，`pom.xml` 加依赖：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>1.0.0-beta1</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-deepseek</artifactId>
    <version>1.0.0-beta1</version>
</dependency>
```

### 最简单的调用

```java
ChatLanguageModel model = DeepSeekChatModel.builder()
    .apiKey("sk-xxxxx")
    .modelName("deepseek-chat")
    .temperature(0.7)
    .build();

String answer = model.generate("你好");
System.out.println(answer);
```

### 带 System Prompt

```java
String answer = model.generate(
    SystemMessage.from("你是一个知识库助手，基于笔记库内容回答问题"),
    UserMessage.from("上个月的日报写了什么")
);
```

### 这就是你替换 `OpenCodeService.sendMessage()` 的最小单元

---

## 3. 加入记忆（Memory）

### 为什么需要

现有 `assistant-v2` 的 `SessionService` 已经在 SQLite 里管理会话历史了。你有两个选择：

| 方案 | 做法 | 适合 |
|------|------|------|
| **方案 A：重用 SQLite 历史** | Java 从 SQLite 查出历史 → 拼成 prompt 发给 LLM | 你现有架构，不改动 |
| **方案 B：用 LangChain4j ChatMemory** | LangChain4j 内部自动管理消息列表 | 新功能 |

### 方案 A（推荐，与现有架构兼容）

```java
// SessionService 已经做了这个
String history = sessionService.buildHistoryContext(sessionId, mode, message);

// 直接拼进 prompt
String fullPrompt = history + "\n\n---\n\n" + "用户最新消息:\n" + message;

// 一次性发给 LLM（无状态）
String answer = model.generate(UserMessage.from(fullPrompt));
```

**这样你现有的 `SessionService`、`TurnEmbeddingEntity`、语义检索全部不用动。**

### 方案 B：LangChain4j 自动记忆

```java
ChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .chatId(sessionId)
    .build();

String answer = model.generate(memory.messages());
memory.add(new UserMessage(message));
memory.add(new AiMessage(answer));

// 持久化：取出消息列表，自己存 SQLite
List<ChatMessage> all = memory.messages();
// → 转成你的 MessageEntity 存进 messages 表
```

---

## 4. 加入工具（Tool）—— 编排核心

### 定义工具

```java
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

public class NoteTools {

    @Tool("列出笔记库中指定目录下的所有文件和子目录")
    public String listDir(@P("目录路径，相对于笔记库根目录") String path) {
        Path dir = baseDir.resolve(path);
        if (!Files.isDirectory(dir)) return "目录不存在: " + path;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .map(p -> (Files.isDirectory(p) ? "📁 " : "📄 ") + p.getFileName())
                .sorted()
                .collect(Collectors.joining("\n"));
        }
    }

    @Tool("读取笔记库中指定文件的内容")
    public String readFile(@P("文件路径，相对于笔记库根目录") String path) {
        Path file = baseDir.resolve(path);
        if (!Files.isRegularFile(file)) return "文件不存在: " + path;
        return FileUtil.readText(file);
    }

    @Tool("将 JSON 数据写入笔记库指定文件。如果文件已存在，需要先读取原有内容，合并后写入")
    public String writeFile(@P("文件路径，相对于笔记库根目录") String path,
                            @P("要写入的 JSON 内容") String json) {
        Path file = baseDir.resolve(path);
        FileUtil.writeText(file, json);
        return "写入成功: " + path;
    }
}
```

### 创建带工具的 AI

```java
ChatLanguageModel model = DeepSeekChatModel.builder()
    .apiKey("sk-xxxxx")
    .modelName("deepseek-chat")
    .build();

Assistant assistant = AiServices.builder(Assistant.class)
    .chatLanguageModel(model)
    .tools(new NoteTools())
    .build();

interface Assistant {
    String chat(String userMessage);
}

String answer = assistant.chat("帮我找找关于客户 XX 的文档");
```

### 工具编排的全过程（LangChain4j 自动完成）

```
用户："帮我找找客户 XX 的信息"
  ↓
Java 调 model.generate()
  ↓
LLM → ToolExecutionRequest(tool="searchNotes", args={"query": "客户 XX"})
  ↓
LangChain4j 自动调 NoteTools.searchNotes("客户 XX")
  ↓
结果给 LLM → LLM → ToolExecutionRequest(tool="readNote", args={...})
  ↓
LangChain4j 自动调 NoteTools.readNote(...)
  ↓
结果给 LLM → LLM → 最终答案 → Java 拿到
```

**全程不需要你写任何循环。** `AiServices` 内部实现了完整的 ReAct 循环。

---

## 5. 两种编排模式的选择

### ⚠️ 核心原则：AI 编排优先，Java 绝不硬编码流程

笔记库的目录结构、数据格式、保存规则都是**随时可能变化的**。如果 Java 硬编码"先读 schema → 再读 rules → 再写 data"，那么哪天 BUG记录 目录改成了 BUG跟踪，或者 schema 新增了一个字段，Java 代码就得跟着改。

**所以涉及笔记库读写操作的编排，必须由 AI 自己控制流程，Java 只提供工具，不决定调用顺序。**

```
❌ 错误：Java 硬编码
  Java 执行：
    1. fileUtil.read("工作/BUG记录/schema.json")     ← 路径写死
    2. fileUtil.read("工作/BUG记录/rules.md")        ← 路径写死
    3. llm.chat(prompt)                                ← 只做转换
    4. fileUtil.write("工作/BUG记录/data.json", json)  ← 路径写死
  → BUG记录改名为 BUG跟踪 → 改 Java 代码重新部署

✅ 正确：AI 编排
  Java 只提供三个工具：listDir / readFile / writeFile
  AI 自己决定：
    1. listDir("") → 看到有 工作/
    2. listDir("工作") → 看到 BUG记录/
    3. readFile("工作/BUG记录/schema.json")
    4. readFile("工作/BUG记录/rules.md")
    5. readFile("工作/BUG记录/data.json")
    6. 生成 JSON → writeFile
  → BUG记录改名为 BUG跟踪 → AI 下次自己找到，不需要改代码
```

### 那 Java 编排还能用在哪

唯一例外是**纯文本生成，不涉及笔记库读写**的场景。例如：

- 日报生成：ReportService 拼好 prompt → 直调 LLM 写一段话 → Java 存文件
- 纯问答：SessionService 从 SQLite 查历史 + 语义检索 → 拼 prompt → 直调 LLM

**这些场景不涉及 AI 探索笔记库，流程完全在 Java 代码里已经确定了。**

### 一句话判断标准

> **LLM 是否需要自己去笔记库找信息？→ AI 编排。
> LLM 只需要根据 Java 给的信息生成文本？→ Java 直调。**

---

## 6. 知识库场景的编排方案

### 场景一：纯问答（不需要工具）

`SessionService` 已经从 SQLite 查了历史 + 做了语义检索，prompt 已经拼好了，LLM 只需要回答：

```java
String fullPrompt = sessionService.buildHistoryContext(...) + userMessage;
String answer = model.generate(UserMessage.from(fullPrompt));
```

走 Java 直调 LLM，~5 秒。这是最能体现"省 Token"的场景——没有中间层冗余。

### 场景二：探索式问答（需要工具）

用户问"我们有哪些客户是做电商的"——LLM 需要去笔记库翻：

```java
String answer = assistant.chat("我们有哪些客户是做电商的");
// 内部自动：searchNotes → readNote → 汇总回答
```

### 场景三：记录数据（需要工具编排，重点）

这是最关键的场景。用户说"记一条BUG"，AI 必须自己探索笔记库结构后写入。

**System Prompt**（控制 AI 的行为模式）：

```java
String SYSTEM_PROMPT = """
你是一个笔记库助手。用户的笔记库根目录下有按主题分类的目录。

当用户要求"记录"或"保存"信息时，你的工作流程：
1. 先探索笔记库结构：用 listDir 查看根目录下有哪些分类
2. 找到合适的目录后，查看该目录下的 schema.json 和 rules.md（如果存在）
3. 读取现有的 data.json，了解已有数据格式
4. 将新数据按规则转为 JSON
5. 合并到 data.json 中，用 writeFile 保存
6. 用中文告知用户已保存

注意：
- 目录结构可能随时变化，不要假设路径，每次都要先探索
- 数据必须符合 schema.json 定义的字段
- 如果 data.json 是数组，追加新记录；如果是对象，合并字段
""";
```

**创建 Assistant：**

```java
Assistant assistant = AiServices.builder(Assistant.class)
    .chatLanguageModel(DeepSeekChatModel.builder()
        .apiKey("sk-xxxxx")
        .modelName("deepseek-chat")
        .build())
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .tools(new NoteTools())
    .systemMessage(SYSTEM_PROMPT)
    .build();

interface Assistant {
    String chat(String userMessage);
}

// 用户说一句就够了
String result = assistant.chat("记一条BUG：登录页面500，紧急");
```

**AI 内部实际执行过程：**

```
用户："记一条BUG：登录页面500，紧急"
  ↓
LLM 思考：先看看笔记库结构
  → 调 listDir("") → 返回 ["工作/", "企业/", "自媒体/"]
  ↓
LLM 思考："工作"目录下应该放 BUG 记录
  → 调 listDir("工作") → 返回 ["BUG记录/", "日报/", "项目/"]
  ↓
LLM 思考：找到 BUG记录 目录，看看规则和结构
  → 调 readFile("工作/BUG记录/rules.md") → 返回保存规则
  → 调 readFile("工作/BUG记录/schema.json") → 返回字段定义
  → 调 readFile("工作/BUG记录/data.json") → 返回现有数据
  ↓
LLM 思考：规则要求 severity 字段，用户说了"紧急"
  → 生成 JSON {"title":"登录页面500","severity":"紧急","status":"待修复",...}
  → 合并到 data.json
  → 调 writeFile("工作/BUG记录/data.json", 合并后的JSON)
  ↓
LLM 返回："已记录BUG：登录页面500（紧急），保存在 工作/BUG记录/"
```

全程 3-5 轮工具调用。如果用 flash 模型，应该在 **10-20 秒**内完成。

### 为什么比 opencode 快

| 环节 | opencode serve | LangChain4j 直连 |
|------|---------------|-----------------|
| 服务启动 | 需提前启动一个独立进程 | 不需要 |
| Session 创建 | 每次请求先 POST /session | 不需要 |
| 响应格式 | 返回流式 parts 数组，Java 逐块解析 | 同步返回，直接拿 |
| 模型 | 取决于 opencode 配置（可能不是 flash） | 可控，用 flash |
| 网络 | → opencode 本地进程 → LLM | → LLM（少一跳） |

### 场景四：读取 + 写入混合（需要工具编排 + 记忆）

```java
Assistant assistant = AiServices.builder(Assistant.class)
    .chatLanguageModel(model)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .tools(new NoteTools())
    .build();

assistant.chat("帮我看看客户 XX 的信息");       // 探索 + 读文件
assistant.chat("他的联系方式是什么");            // 直接用前面读的结果
assistant.chat("帮我更新他的手机号为 138xxxx");   // 读 + 改 + 写
```

---

## 7. 流式输出（SSE）

你现在 ChatController 返回 `SseEmitter`，LLM 也需要流式。

### 流式调用

```java
StreamingChatLanguageModel streamingModel = DeepSeekStreamingChatModel.builder()
    .apiKey("sk-xxxxx")
    .modelName("deepseek-chat")
    .build();

streamingModel.generate(
    UserMessage.from(fullPrompt),
    new StreamingResponseHandler<AiMessage>() {
        @Override
        public void onNext(String token) {
            emitter.send(SseEmitter.event().data(token));
        }
        @Override
        public void onComplete(Response<AiMessage> response) {
            emitter.complete();
        }
        @Override
        public void onError(Throwable error) {
            emitter.completeWithError(error);
        }
    }
);
```

### 流式 + 工具调用

工具调用在流式模式下稍微复杂一些——LLM 输出中可能混有工具请求和文本。`StreamingAiServices` 处理了这个问题：

```java
StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
    .streamingChatLanguageModel(streamingModel)
    .tools(new NoteTools())
    .build();

interface StreamingAssistant {
    TokenStream chat(String userMessage);
}

assistant.chat("帮我查一下客户信息")
    .onNext(token -> emitter.send(SseEmitter.event().data(token)))
    .onToolExecuted(execution -> log.info("调用了: {}", execution.toolName()))
    .onComplete(response -> emitter.complete())
    .onError(error -> emitter.completeWithError(error))
    .start();
```

---

## 8. 与现有架构的整合路径

### 建议的分步迁移

```
Step 1：加 LlmService（直调 DeepSeek）
  → ChatController 新增 /chat/direct 接口，走 LlmService
  → 不需要工具，纯问答
  → 测试：与 opencode 并跑，对比结果和速度

Step 2：加 AiRouter（场景分流）
  → 判断是否"需要工具"
  → 纯问答、日报生成 → 走 LlmService 直连
  → 记录 BUG、写客户信息等 → 走 opencode（暂时）

Step 3：加 NoteToolService（自建编排）
  → 用 LangChain4j AiServices + NoteTools（listDir/readFile/writeFile）
  → 替换 opencode 的"记录"场景
  → AiRouter 变为：直连 / LangChain4j 编排

Step 4：全部切换
  → ChatController、FeishuLongConnectionService、ReportService 等全部迁移
  → 下线 opencode serve，不再需要启动两个服务
```

### 最终架构

```
ChatController / FeishuBot / ReportService / ...
  ↓
AiRouter（场景识别）
  ├─ 纯问答 ──→ LlmService ──→ DeepSeek API（流式，~5秒）
  └─ 需工具 ──→ LangChain4j Assistant ──→ DeepSeek API（~15秒）
                      │
               NoteTools（listDir / readFile / writeFile）
                      │
               笔记库 JSON 文件
               
SQLite（会话历史 + 语义检索） ←→ SessionService（不动）
```

---

## 关键点总结

1. **⚠️ AI 编排优先，Java 绝不硬编码笔记库操作流程**——目录和数据格式随时可能变化，AI 自己用 listDir/readFile/writeFile 探索后决定，Java 只提供工具不决定调用顺序
2. **LangChain4j 已在项目中**（OllamaEmbeddingService 依赖了它），加 DeepSeek 支持即可
3. **两种模式的判断标准**：需要 AI 去笔记库找信息 → AI 编排（@Tool）；只需要根据 Java 给的信息生成文本 → Java 直调 LLM
4. **现有 SessionService 不用动**：SQLite 管理会话历史 + 语义检索，与 LangChain4j 互补
5. **记录场景提速**：从 opencode 几分钟 → LangChain4j + flash 模型 10-20 秒
6. **分步迁移风险低**：直连和 opencode 可以并跑，逐个场景切换
