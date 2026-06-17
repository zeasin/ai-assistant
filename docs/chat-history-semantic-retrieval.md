# Chat History Semantic Retrieval / 长期记忆

> ⚠️ *本文档描述 v2.0 架构，已过时。当前 v0.4.0 使用 Spring AI 2.0 直接调用 LLM，不再依赖 opencode serve。详见 README.md*

> 用 LangChain4j + Ollama 本地 embedding 实现跨会话语义检索，让 AI 能从所有历史对话中找到相关内容。
>
> **✅ 已实现 (2026-06-16)** — 见 `SessionService`、`OllamaEmbeddingService` 及相关文件。
>
> 最后更新: 2026-06-16

## 问题

1. **不相关历史污染**：`buildHistoryContext()` 全量拼接聊天记录，浪费 token、误导 AI
2. **表结构重复**：`chat_sessions` / `feishu_sessions`、`chat_messages` / `feishu_messages` 四张表结构高度重叠，逻辑重复，维护成本高
3. **长对话早期信息丢失**：WebUI 和飞书都只取最近 N 轮对话，更早但相关的内容被忽略

## 方案总览

- **DB 层**：四表合并为两表 `sessions` + `messages`，新增 `turn_embeddings` 表存向量（Base64 TEXT）
- **语义检索**：LangChain4j `OllamaEmbeddingModel` 生成向量，余弦相似度选取相关轮次
- **服务层**：合并 `ChatSessionService` + `FeishuChatSessionService` 为统一 `SessionService`
- **上下文组装**：最近 3 轮（保对话连贯性）+ 全库语义检索（补早期信息）

---

## 一、数据库设计

### 1.1 合并分析

| 维度 | 当前（4 张表） | 合并后（3 张表） |
|------|:-:|:-:|
| sessions 结构差异 | `chat_sessions` 有 title，`feishu_sessions` 有 chat_id/chat_type/open_code_session_id | 统一用一张表，source 字段区分，各自字段 nullable |
| messages 结构差异 | 完全一致（id, role, content, mode, created_at），仅 FK 字段名不同 | 统一用 `session_id` FK |
| buildHistoryContext | 两套独立实现 | 一套实现 |
| 向量存储 | 需为两套各存一份 | 统一关联 session_id |
| 新增 source（如 API） | 再加两张表 | 加一个 source 值 |

**结论：合并利远大于弊，统一为 `sessions` + `messages`。**

### 1.2 完整 DDL

```sql
-- ============================================
-- 1. SESSIONS: 统一会话表
--    合并 chat_sessions + feishu_sessions
-- ============================================
CREATE TABLE IF NOT EXISTS sessions (
    id                        TEXT PRIMARY KEY,            -- Web: sessionId / Feishu: userKey
    source                    TEXT NOT NULL DEFAULT 'web', -- 'web' | 'feishu'
    title                     TEXT NOT NULL DEFAULT '新对话', -- Web: 会话标题
    chat_id                   TEXT NOT NULL DEFAULT '',    -- Feishu: 聊天 ID
    chat_type                 TEXT NOT NULL DEFAULT '',    -- Feishu: 'p2p' | 'group'
    open_code_session_id      TEXT,                        -- opencode 会话 ID（knowledge）
    open_code_code_session_id TEXT,                        -- opencode 会话 ID（code）
    mode                      TEXT NOT NULL DEFAULT 'knowledge',
    created_at                TEXT NOT NULL,
    updated_at                TEXT NOT NULL
);

-- ============================================
-- 2. MESSAGES: 统一消息表
--    合并 chat_messages + feishu_messages
-- ============================================
CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,                    -- → sessions.id
    source      TEXT NOT NULL DEFAULT 'web',     -- 'web' | 'feishu'
    role        TEXT NOT NULL,                    -- 'user' | 'assistant'
    content     TEXT NOT NULL,
    mode        TEXT NOT NULL DEFAULT 'knowledge',
    created_at  TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at);

-- ============================================
-- 3. TURN_EMBEDDINGS: 对话轮次向量表
--    每完成一轮（user + AI 配对）生成一条向量
-- ============================================
-- 注意: SQLite JDBC 不支持 getBlob()，所以 embedding 存 Base64 TEXT
--       而非 BLOB（byte[] → Base64.encode → String → TEXT）
CREATE TABLE IF NOT EXISTS turn_embeddings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,                    -- → sessions.id
    turn_order  INTEGER NOT NULL,                 -- 轮次序号（0 起始）
    embedding   TEXT NOT NULL,                    -- Base64(float[768]): 3072 bytes → 4096 chars
    created_at  TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_turn_embeddings_session ON turn_embeddings(session_id);
```

### 1.3 字段说明

**sessions 表**：

| 字段 | Web 聊天 | 飞书聊天 |
|------|----------|----------|
| id | sessionId（如 `20260616-xxx`） | userKey（如 `p2p:open_id_xxx`） |
| source | `'web'` | `'feishu'` |
| title | 用户首条消息前 30 字 | `''`（空，飞书无标题） |
| chat_id | `''` | 飞书 chat_id |
| chat_type | `''` | `'p2p'` 或 `'group'` |
| open_code_session_id | 当前使用的 opencode 会话 | 同上 |
| mode | `'knowledge'` / `'code'` | 同上 |

**messages 表**：四列核心数据（role、content、mode、created_at）与原表完全一致，仅 FK 统一为 `session_id`。

**turn_embeddings 表**：
- `turn_order`：第 N 轮对话，从 0 开始自增。保存 AI 回复时 `turn_order = 查询当前轮次数`
- `embedding`：`float[]` → `byte[]` → `Base64.encode` → String → TEXT
- 查询时：`Base64.decode` → `byte[]` → `ByteBuffer.wrap` → `float[]`

### 1.4 数据迁移策略

首次启动时自动执行（放在 `SessionService.init()` 的 `@PostConstruct`）：

```
1. 先执行 DDL 创建新表（sessions / messages / turn_embeddings）
2. 检测旧 chat_sessions 表是否存在且有数据
3. 是 → INSERT INTO sessions SELECT ... (chat_sessions + feishu_sessions UNION)
          INSERT INTO messages SELECT ... (chat_messages + feishu_messages UNION)
4. 成功后 RENAME 旧表为 .bak（或直接 DROP）
5. 迁移 JSON 文件数据的逻辑也走同样路径
```

---

## 二、语义检索架构

### 2.1 依赖

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-ollama</artifactId>
    <version>1.16.2</version>
</dependency>
```

### 2.2 OllamaEmbeddingService

封装 LangChain4j `OllamaEmbeddingModel`，统一异常处理和健康检查。

```java
@Component
public class OllamaEmbeddingService {

    private final OllamaEmbeddingModel model;
    private boolean available;

    @PostConstruct
    void checkHealth() {
        // 1. 尝试访问 Ollama /api/tags
        // 2. 检查配置的模型是否已拉取
        // 3. 不可用时 log.warn + logService.add + available=false
    }

    public Optional<Embedding> embed(String text) {
        try { return Optional.of(model.embed(text).content()); }
        catch (Exception e) {
            log.warn("Ollama embed failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isAvailable() { return available; }
}
```

**启动检测**：
- Ollama 未运行 → 日志 `⚠️ Ollama 未运行，语义检索不可用，将回退最近3轮`
- 模型未拉取 → 日志 `⚠️ Embedding 模型 'nomic-embed-text' 未安装，请执行: ollama pull nomic-embed-text`
- 告警也在 `/config` 页面顶部显示红色条，`/health` 返回 `ollama.available: false`

### 2.3 保存消息时生成向量

`SessionService.saveMessage(sessionId, role, content, mode)`：

```
saveMessage(role="user", ...)
  → 保存消息到 messages 表
  → 记录 lastRole = "user"（线程安全：AtomicReference 或查 DB）

saveMessage(role="assistant", ...)
  → 保存消息到 messages 表
  → 检查上一条消息 role == "user"（配对完整）
     ├── 是 → 拼接 user_msg + "\n" + ai_msg
     │        → OllamaEmbeddingService.embed(text)
     │        → 序列化 float[] → 存入 turn_embeddings
     └── 否 → 等待（不生成向量）
```

### 2.4 上下文组装策略（核心设计）

`SessionService.buildHistoryContext(sessionId, mode, currentQuery)`：

```
1. 查询当前 session 的 messages
2. 按 mode 过滤（knowledge / code）

3. 如果 currentQuery 为空 或 Ollama 不可用:
   → 返回所有过滤后的历史（不做筛选）

4. embeddingModel.embed(currentQuery) → queryEmb
   嵌入失败 → 回退步骤 6

5. 全库语义检索 searchGlobalContext(queryEmb, sessionId):
   a. 加载所有 session 的 turn_embeddings（含当前 session）
   b. 对每条: score = cosineSimilarity(queryEmb.vector(), turn.vector)
   c. 按分数降序排列
   d. 筛选:
      - 阈值 COSINE_THRESHOLD = 0.5（v0.2 从 0.3 调高，减少低质量匹配）
      - 每 session 最多 MAX_TURNS_PER_SESSION = 4 轮（避免单 session 占满）
      - 总计最多 MAX_GLOBAL_TURNS = 8 轮
   e. 命中 → 格式化为上下文（按 session 分组，带会话标题）
   f. 无命中 → 走步骤 6

6. 回退: 最近 DEFAULT_FALLBACK_TURNS = 3 轮
   （仅当前 session，保证对话连贯性和代词指代）

最终 prompt 组装:
┌──────────────────────────────────────────────┐
│ 【最近 3 轮】（保证连贯性，如"和华为比"能指代上轮） │
│ 【语义检索命中】（全库排序，补早期相关信息）        │
│ 用户最新消息: xxx                                │
└──────────────────────────────────────────────┘
```

#### 设计决策记录

| 版本 | 策略 | 理由 |
|------|------|------|
| v0.1 | 只检索当前 session，排除其他 | 漏掉跨 session 有价值信息 |
| v0.2 | 全库检索，排除当前 session | 当前 session 历史靠最近 N 轮回退；但早期信息仍丢失 |
| v0.3 **(当前)** | 全库检索（含当前 session）+ 最近 3 轮回退 | 兼顾跨 session + 同 session 早期信息 + 对话连贯性 |

#### Tokens 估算

- 最近 3 轮 ≈ 500-800 tokens
- 语义检索命中 8 轮 ≈ 1000-2000 tokens
- 合计 ≈ 1500-2800 tokens（可控，不会暴涨）

### 2.5 降级策略

| 情况 | 行为 |
|------|------|
| Ollama 未运行 / 请求异常 | 捕获异常，返回最近 3 轮 |
| 旧数据没有 embedding | 返回最近 3 轮 |
| 冷启动 (≤ 3 条) | 全部注入不做筛选 |
| Embedding 维度不匹配 | 清除旧向量，重新生成 |
| 语义检索命中但全来自当前 session | 仍然返回（重复最近 N 轮无害，AI 能处理） |

### 2.6 Session 管理（上下文边界）

不同的接入方使用不同的 session 策略：

| 接入方 | session ID | 特点 |
|--------|-----------|------|
| WebUI | 用户每次点"新对话"生成新 UUID | 用户自己控制上下文边界，粒度灵活 |
| 飞书 | userKey（用户 open_id） | 每个飞书用户一个 session，永不新建 |
| API（预留） | 外部传入 | 待定 |

当前 prompt 中的"长期记忆"场景：
- 当前 session 的最近 3 轮 → 自动拼接（保证连贯性）
- 跨 session 的语义检索 → 找到其他会话相关对话
- 当前 session 的早期轮次 → 同被语义检索覆盖（v0.3+）

---

## 三、配置

`application.yml`：

```yaml
app:
  ollama:
    base-url: http://127.0.0.1:11434
    model: nomic-embed-text
    timeout-seconds: 30
```

`AppConfig.java`：

```java
public class AppConfig {
    // ... 现有字段

    // Ollama
    private String ollamaBaseUrl = "http://127.0.0.1:11434";
    private String ollamaModel = "nomic-embed-text";
    private int ollamaTimeoutSeconds = 30;
}
```

---

## 四、文件清单

### 新建文件（5 个）

```
src/main/java/com/laoqi/assistant/
├── service/
│   └── OllamaEmbeddingService.java       ← LangChain4j 封装 + 健康检查
├── entity/
│   ├── SessionEntity.java                ← MyBatis 实体（sessions 表）
│   ├── MessageEntity.java                ← MyBatis 实体（messages 表）
│   └── TurnEmbeddingEntity.java          ← MyBatis 实体（turn_embeddings 表）
├── mapper/
│   ├── SessionMapper.java                ← MyBatis Mapper
│   ├── MessageMapper.java                ← MyBatis Mapper
│   └── TurnEmbeddingMapper.java          ← MyBatis Mapper
└── service/db/
    ├── SessionDbService.java
    ├── SessionDbServiceImpl.java
    ├── MessageDbService.java
    ├── MessageDbServiceImpl.java
    ├── TurnEmbeddingDbService.java
    └── TurnEmbeddingDbServiceImpl.java
```

### 修改文件（10 个）

| 文件 | 改动 |
|------|------|
| `pom.xml` | 添加 `langchain4j-ollama:1.16.2` |
| `config/AppConfig.java` | 添加 ollama 三个配置字段 |
| `application.yml` | 添加 `app.ollama.*` |
| `service/SessionService.java` | **新建**，替代原有的 ChatSessionService + FeishuChatSessionService |
| `service/ChatSessionService.java` | 改为委托 SessionService（兼容 ChatController） |
| `service/FeishuChatSessionService.java` | 改为委托 SessionService（兼容 FeishuLongConnectionService） |
| `controller/ChatController.java` | DDL 初始化移到 SessionService |
| `service/FeishuLongConnectionService.java` | 无改动（通过 FeishuChatSessionService 委托） |
| `controller/ConfigController.java` | 传递 ollama 状态到模板 |
| `templates/config.html` | 显示 Ollama 状态告警条 |

### 可删除文件（迁移后）

| 文件 | 替代 |
|------|------|
| `entity/ChatSessionEntity.java` | `SessionEntity` |
| `entity/ChatMessageEntity.java` | `MessageEntity` |
| `entity/FeishuSessionEntity.java` | `SessionEntity` |
| `entity/FeishuMessageEntity.java` | `MessageEntity` |
| `mapper/ChatSessionMapper.java` | `SessionMapper` |
| `mapper/ChatMessageMapper.java` | `MessageMapper` |
| `mapper/FeishuSessionMapper.java` | `SessionMapper` |
| `mapper/FeishuMessageMapper.java` | `MessageMapper` |
| `service/db/ChatSessionDbService*` | `SessionDbService*` |
| `service/db/ChatMessageDbService*` | `MessageDbService*` |
| `service/db/FeishuSessionDbService*` | `SessionDbService*` |
| `service/db/FeishuMessageDbService*` | `MessageDbService*` |

---

## 五、Embedding 模型

**推荐**：`nomic-embed-text`（274MB，768 维，CPU 友好）

```bash
ollama pull nomic-embed-text
curl http://127.0.0.1:11434/api/tags    # 验证：输出应包含模型名
```

**备选**：

| 模型 | 大小 | 维度 | 说明 |
|------|------|------|------|
| `nomic-embed-text` | 274MB | 768 | ✅ 推荐，轻量效果好 |
| `bge-m3` | 2.2GB | 1024 | 多语言更强，但较重 |
| `mxbai-embed-large` | 670MB | 1024 | 英文场景更优 |
| `all-minilm` | 134MB | 384 | 最轻量，精度稍低 |

---

## 六、数据流总图

```
                    ┌──────────────────────────────────────┐
                    │         Ollama (localhost:11434)       │
                    │   Model: nomic-embed-text             │
                    └──────────┬───────────────────────────┘
                               │ POST /api/embed
                               │
                    ┌──────────▼───────────┐
                    │ OllamaEmbeddingService │
                    │  embed(text) → float[] │
                    │  isAvailable() → bool   │
                    └──────────┬───────────┘
                               │
     ┌─────────────────────────┼──────────────────────────┐
     │                         │                          │
     ▼                         ▼                          ▼
 ┌──────────┐          ┌──────────────────┐       ┌──────────────┐
 │ Web 聊天  │          │  SessionService    │       │  飞书聊天     │
 │ ChatController │──────►  buildHistoryContext│◄────────│ FeishuService │
 │  POST /chat │          │  saveMessage       │       │  WebSocket    │
 └──────────┘          └──────┬────────┬───┘       └──────────────┘
                              │        │
                      ┌───────▼┐  ┌────▼──────────────┐
                      │ SQLite  │  │ turn_embeddings    │
                      │ sessions│  │ (全库检索，含当前)   │
                      │ messages│  │                    │
                      └────────┘  └────────────────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │ buildHistoryContext   │
                   │                      │
                    │ 1. 最近 3 轮(必含)   │
                    │ 2. 语义检索(全库)    │
                    │    → 排序→去重→拼入  │
                    │ 3. 用户最新消息       │
                    └─────────────────────┘

---

## 七、未来改进方向

> 以下是在开发过程中讨论过但未最终实现的方案，记录在此供后续参考。

### 7.1 按主题自动分割 Session

当前 WebUI 靠用户手动点"新对话"分割 session，飞书则永远用一个 session。
可考虑：

- **AI 检测话题变化**：当用户消息与当前 session 历史话题差异大时，自动结束当前 session、创建新 session
- **按时间分割**：飞书场景按天或按小时自动分割 session，避免单 session 无限增长

### 7.2 动态上下文窗口（替代固定最近 3 轮）

当前固定最近 3 轮，如果有更好的替代方案可参考：

- **最近 1 轮 + 全量语义检索**：只保留最近 1 轮保证代词指代，其余全部交给语义打分
- **Token 预算分配**：设总 token 上限（如 2048），语义检索按分数竞争，保证不超预算
- **滑动窗口 + 语义重排**：先滑动窗口取最近 N 轮，再用语义检索从中选出最相关的 K 轮

### 7.3 检索增强

- **HyDE（Hypothetical Document Embeddings）**：先让 AI 生成一个假设回复，用假设回复去检索，比直接搜用户问题更准
- **多路召回**：同时走关键词（BM25）和语义（向量），合并结果
- **重排序（Re-rank）**：召回后用一个轻量模型对结果重新打分排序

### 7.4 分块与摘要

- 当 session 轮次过多时（如 100+ 轮），对早期对话做 AI 摘要，存为摘要 embedding
- 检索时先匹配摘要，命中再展开详情

### 7.5 Session 隔离度可配置

当前策略对不同接入方相同。可考虑可配置策略：

```yaml
app:
  rag:
    web:
      context-recent-turns: 3
      max-global-turns: 8
      max-turns-per-session: 4
      threshold: 0.5
    feishu:
      context-recent-turns: 5        # 飞书对话更长，多保留几轮
      max-global-turns: 6
      max-turns-per-session: 3
      threshold: 0.4
