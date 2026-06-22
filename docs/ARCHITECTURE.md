# AI 助理 V2 系统架构文档

## 1. 核心设计理念：以知识库（KB）为中心，无 Session 感知

### 1.1 "无 Session 设计"的含义

系统**不区分会话**。用户感知不到 session 的存在：

- 每个知识库（KB）对应**一个连续对话**，不是多轮独立会话
- 前端按 KB 加载消息列表（`/chat/api/kb/{kbId}/messages`），不按 session 分页
- 「清空对话」按钮删除该 KB 下的**所有** session 和消息
- 没有「新建对话」按钮，只有「清空」来重置

### 1.2 Session 的实质角色

Session 在系统中只是一个**存储容器**，不是用户可见的实体：

```
ChatController.getOrCreateKbSession(kbId, mode)
  → 查找该 KB 下最新的 session
  → 如果不存在，创建一个
  → 一个 KB 始终只有一个活跃 session
```

Session 表的关键字段：
- `id`：UUID 前 12 位
- `kbId`：所属知识库
- `mode`：对话模式（默认为 "knowledge"）
- `title`：固定为 "连续对话"

### 1.3 数据模型关系

```
KnowledgeBase (知识库)
  ├── 1:N → SessionEntity (会话容器，通常 1 个)
  │           └── 1:N → MessageEntity (消息)
  │           └── 1:N → TurnEmbeddingEntity (轮次向量，用于跨 KB 长期记忆)
  └── notesDir → 磁盘上的 Markdown 笔记文件
                   └── 1:N → NoteEmbeddingEntity (笔记片段向量索引)
```

## 2. 对话流程

### 2.1 请求入口

```
POST /chat/send
  参数: message, kbId, mode, modelName
  → SSE 流式响应
```

### 2.2 完整链路

```
用户输入
  │
  ├─ ChatController.send()
  │   ├─ getOrCreateKbSession(kbId, mode) → 获取/创建 sessionId
  │   ├─ saveMessage(sessionId, "user", message) → 保存用户消息
  │   └─ noteAssistantService.streamChat(sessionId, message, mode, kbId, modelName)
  │
  └─ NoteAssistantService.streamChat()
      ├─ ContextBuilder.build(sessionId, userMessage, kbId)
      │   ├─ sessionService.buildHistoryContext(sessionId, "knowledge", userMessage)
      │   │   ├─ 始终包含最近 3 轮对话
      │   │   ├─ 如果 Ollama 可用 → 语义搜索当前 session 历史轮次
      │   │   │   └─ searchGlobalContext() → 扫描当前 session 的 turn_embeddings
      │   │   └─ 合并：「近期 3 轮 + 语义命中轮次」→ 注入 AI 上下文
      │   ├─ noteIndexService.hybridSearch(kbId, userMessage, 5)
      │   │   ├─ 加载所有 note_embeddings（一次）
      │   │   ├─ pre-decode 所有向量（一次）
      │   │   ├─ expandQuery → 生成扩展查询
      │   │   ├─ 对每个扩展查询：语义搜索 + 关键词搜索（复用预加载数据）
      │   │   └─ 排序 + 归一化展示分
      │   └─ 读取 AGENTS.md
      │
      ├─ ContextBuilder.merge(context, userMessage) → MergedContext
      │   ├─ systemContext: AGENTS.md + 搜索结果
      │   └─ userMessage: 历史对话 + 用户最新消息
      │
      └─ ChatClient.prompt()
          ├─ .system(SYSTEM_PROMPT)          → AI 角色指令
          ├─ .system(merged.systemContext())  → 规则 + 笔记内容
          ├─ .user(merged.userMessage())      → 历史 + 用户输入
          └─ .stream() → SSE 流式返回
```

## 3. 核心模块

### 3.1 ContextBuilder - 上下文构建器

**职责**：构建 AI 对话所需的完整上下文。

**输入**：
- `sessionId`：当前会话 ID
- `userMessage`：用户最新消息
- `kbId`：知识库 ID

**输出**：`MergedContext`
- `systemContext`：AGENTS.md 规则 + 前 5 条相关笔记（去重、截断到 800 字符/最后完整句子）
- `userMessage`：历史对话（截断到 8000 字符） + 用户最新消息

**关键设计决策**：
- 搜索结果放在 system 消息中（而非 user），确保 AI 认真对待
- 历史对话放在 user 消息中，模拟真实对话连续性
- 内容去重使用最长公共子串算法（对中文友好）
- 内容截断在句子边界，不切断中间

### 3.2 NoteIndexService - 笔记索引与搜索

**数据流**：

```
索引构建：
  Markdown 文件 → 读取内容 → 提取标题(# ) → 提取路径上下文
  → chunkContent(按 \n\n 分段) → 拼接 enhancedContent
  → embeddingService.embed() → 存入 note_embeddings 表

搜索：
  query → expandQuery → 对每个扩展查询：
    ├─ 语义搜索: embed(query) → cosine 相似度 → 过滤(SIMILARITY_THRESHOLD=0.3)
    └─ 关键词搜索: 遍历 entity content/pathContext 匹配
  → 排序(原始余弦分 + 路径/关键词加分) → 展示分归一化
```

**关键字段**：
- `title`：Markdown 第一个 `# 标题`（跳过 YAML frontmatter）
- `pathContext`：文件路径层级（如 `项目 北京本数科技`）
- `content`：纯文本片段（不含路径和标题）
- `enhancedContent`：`title + \n + pathContext + \n + chunk`（用于 embedding）

**向量存储**：
- `floatToBytes` / `bytesToFloat` 使用固定 `LITTLE_ENDIAN` 字节序
- 存储为 Base64 编码字符串

### 3.3 SessionService - 历史对话管理

**历史构建策略：近期聊天 + 语义搜索，二者叠加而非替换**

1. **始终包含最近 N 轮**（`DEFAULT_FALLBACK_TURNS = 3`）：
   - 取最近 3 轮（6 条消息）作为基础上下文
   - 确保 AI 始终知道最近的对话内容

2. **语义搜索叠加**（Ollama 可用时）：
   - 对当前 query 生成 embedding
   - 搜索当前 session 的 `turn_embeddings`（排除最近 N 轮，避免重复）
   - 找到语义相关的历史轮次，叠加到近期上下文中

3. **合并策略**：
   - 近期上下文 + 语义搜索命中 → 合并注入
   - 无语义命中 → 仅近期上下文
   - Ollama 不可用或嵌入失败 → 仅近期上下文

### 3.4 NoteAssistantService - AI 编排

**SYSTEM_PROMPT 设计原则**：
- 优先使用已注入的上下文（笔记搜索结果 + 历史对话）
- 只在信息不足时才调用工具（searchNotes、searchFiles 等）
- 不主动搜索，避免双重搜索浪费 token

## 4. 数据库表结构

### note_embeddings（笔记向量索引）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键 |
| kb_id | INTEGER | 知识库 ID |
| file_path | TEXT | 相对路径 |
| chunk_index | INTEGER | 片段序号 |
| title | TEXT | Markdown 标题 |
| path_context | TEXT | 路径上下文 |
| content | TEXT | 纯文本片段 |
| embedding | TEXT | Base64 编码的向量 |
| content_hash | TEXT | 内容 MD5 |

### messages（对话消息）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键 |
| session_id | TEXT | 所属会话 |
| role | TEXT | user/assistant |
| content | TEXT | 消息内容 |
| mode | TEXT | 对话模式 |
| kb_id | INTEGER | 知识库 ID |

### sessions（会话容器）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | TEXT | UUID 前 12 位 |
| kb_id | INTEGER | 知识库 ID |
| title | TEXT | "连续对话" |
| mode | TEXT | 对话模式 |

### turn_embeddings（轮次向量，跨 KB 长期记忆）
| 字段 | 类型 | 说明 |
|------|------|------|
| session_id | TEXT | 所属会话 |
| turn_order | INTEGER | 轮次序号 |
| embedding | TEXT | Base64 编码的向量 |

## 5. 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| SIMILARITY_THRESHOLD | 0.3 | 语义搜索最低余弦相似度 |
| DEFAULT_FALLBACK_TURNS | 3 | 近期上下文保留轮数 |
| MAX_GLOBAL_TURNS | 5 | 语义搜索命中轮次上限 |
| COSINE_THRESHOLD | 0.5 | 语义搜索余弦阈值 |

## 6. 设计注意事项

1. **无 Session 感知**：用户不应感知 session 的存在。所有与 session 相关的逻辑都是实现细节。
2. **KB 即对话**：一个 KB 就是一个连续对话，不需要「新建会话」。
3. **近期聊天 + 语义搜索**：历史上下文 = 最近 N 轮 + 语义搜索到的历史轮次，二者叠加而非替换。
4. **语义搜索在当前 session 内**：只搜索当前 KB 的对话历史，不跨 session。
5. **搜索结果在 system 中**：笔记搜索结果和 AGENTS.md 规则放在 system 消息中，确保 AI 认真对待。
6. **历史在 user 中**：历史对话放在 user 消息中，模拟真实对话连续性。
7. **索引去重**：`indexFile` 检查 `contentHash` + `pathContext` + `title` 决定是否跳过。
8. **展示分归一化**：`toDisplayScore` 仅用于前端展示，排序始终用原始余弦相似度。