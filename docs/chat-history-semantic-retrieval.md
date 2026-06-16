# Chat History Semantic Retrieval

> 重构数据库设计，用 LangChain4j + Ollama 本地 embedding 实现聊天历史的语义相关度筛选。
>
> **✅ 已实现 (2026-06-16)** — 见 `SessionService`、`OllamaEmbeddingService` 及相关文件。

## 问题

1. **不相关历史污染**：`buildHistoryContext()` 全量拼接聊天记录，浪费 token、误导 AI
2. **表结构重复**：`chat_sessions` / `feishu_sessions`、`chat_messages` / `feishu_messages` 四张表结构高度重叠，逻辑重复，维护成本高

## 方案总览

- **DB 层**：四表合并为两表 `sessions` + `messages`，新增 `turn_embeddings` 表存向量
- **语义检索**：LangChain4j `OllamaEmbeddingModel` 生成向量，余弦相似度选取相关轮次
- **服务层**：合并 `ChatSessionService` + `FeishuChatSessionService` 为统一 `SessionService`

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
CREATE TABLE IF NOT EXISTS turn_embeddings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,                    -- → sessions.id
    turn_order  INTEGER NOT NULL,                 -- 轮次序号（0 起始）
    embedding   BLOB NOT NULL,                    -- float[768] → 4×768 = 3072 bytes
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
- `embedding`：`float[]` → `ByteBuffer.allocate(4 * 768).putFloat(v).array()` → 3072 bytes
- 查询时：`ByteBuffer.wrap(blob).asFloatBuffer().get(targetArray)`

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

### 2.4 查询时语义筛选

`SessionService.buildHistoryContext(sessionId, currentQuery)`：

```
1. 查询该 session 的 messages（按 created_at ASC）
2. 如果 currentQuery 为空 or Ollama 不可用 or 消息数 <= 3:
   → 返回最近 3 轮（不做筛选）

3. embeddingModel.embed(currentQuery) → queryEmb

4. 加载 turn_embeddings → List<turnOrder, float[]>
   对每条: score = cosineSimilarity(queryEmb.vector(), turn.vector)

5. 筛选:
   - 保留 score > 0.3 的轮次
   - 无命中 → 回退最近 3 轮

6. 选中轮次按 turn_order ASC 排序
7. 取出对应 messages → 格式化为 context 字符串
```

### 2.5 降级策略

| 情况 | 行为 |
|------|------|
| Ollama 未运行 / 请求异常 | 捕获异常，返回最近 3 轮 |
| 旧数据没有 embedding | 返回最近 3 轮 |
| 冷启动 (≤ 3 条) | 全部注入不做筛选 |
| Embedding 维度不匹配 | 清除旧向量，重新生成 |

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
┌──────────┐          ┌──────────────┐          ┌──────────────┐
│ Web 聊天  │          │   SessionService │          │  飞书聊天     │
│ ChatController │──────►  buildHistoryContext│◄───────│ FeishuService │
│  POST /chat │          │  saveMessage      │          │  WebSocket    │
└──────────┘          └──────┬───────┬───┘          └──────────────┘
                             │       │
                     ┌───────▼┐ ┌───▼──────────┐
                     │ SQLite  │ │ turn_embeddings │
                     │ sessions│ │ + messages     │
                     └────────┘ └───────────────┘
```
