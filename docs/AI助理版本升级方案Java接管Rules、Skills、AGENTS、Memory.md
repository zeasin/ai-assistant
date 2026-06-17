# AI助理版本升级方案：Java接管Rules、Skills、AGENTS.md与Memory

> ✅ *此升级方案已全部完成。当前 v3.0 实现了所有目标：Spring AI 2.0 直连 LLM、@Tool 替代手写 ReAct、零外部服务依赖。详见 README.md*

## 一、总体架构

- **前端入口**：飞书（接收用户对话、发送回复）
- **后端核心**：Spring Boot（Java 17+）+ MyBatis-Plus ORM
- **AI大脑**：OpenCode（负责推理、任务执行）
- **数据层**：文件系统（JSON + Markdown）+ SQLite（Memory 长期记忆 + 聊天记录）
- **数据库访问**：MyBatis-Plus（自动 CRUD + Lambda 查询 + 分页）
- **检索引擎**：SQLite FTS5（内置 BM25 排序，零额外依赖）

**核心原则**：Java管理所有可配置、可持久化的AI资产（Rules、AGENTS.md、Skills、Memory），OpenCode只负责推理与工具调用。

---

## 二、模块详细设计

### 2.1 Rules（规则）—— 数据库全量管理

**目标**：定义AI始终遵守的行为边界（安全、格式、身份等）。

| 项目 | 内容 |
|------|------|
| 存储介质 | JSON 文件（`rules.json`），与 `config.json` 同级目录 |
| 关键字段 | `id`, `name`, `content`(TEXT), `enabled`(BOOLEAN), `priority`(INT), `triggers`(TEXT), `scope`(VARCHAR) |
| 加载方式 | 每次对话启动时，Java 读取 `rules.json`，筛选 `enabled=true` 的规则，按 `priority` 排序，拼接成字符串。 |
| 注入位置 | 作为 System Prompt 的第一部分发给 OpenCode。 |
| 管理接口 | 提供 REST API 与简单管理页面（增删改查、启用/禁用）。 |

**第一期实施**：全量注入（规则数 < 100 条时完全可行）。预留 `triggers`、`scope` 字段，后期可按需实现基于关键词或语义的规则筛选。

---

### 2.2 AGENTS.md —— Java读写 + 文件系统

**目标**：项目/会话级的背景知识、编码规范、常用命令，支持人工手动编辑。

| 项目 | 内容 |
|------|------|
| 存储介质 | 文件系统（笔记库目录），例如 `./notebooks/AGENTS.md` |
| 加载方式 | Java 在调用 OpenCode 前读取文件内容，可追加到 System Prompt 或作为用户消息前缀。 |
| 管理方式 | Java 提供文件上传、下载、在线编辑（简单文本编辑器）接口；同时允许直接 SSH 修改文件。 |
| 多租户支持 | 可按项目/团队拆分文件，如 `agents/projectA.md`、`agents/projectB.md`，根据 `tenant_id` 动态选择。 |

**与 Rules 的区别**：Rules 是不可违背的全局行为准则，AGENTS.md 是可变的工作指引。

---

### 2.3 Skills —— 遵循 Agent Skills 标准，Java 读写目录

**目标**：封装可复用的“能力包”，让 AI 能按需加载执行复杂任务（如周报生成、代码审查）。

| 项目 | 内容 |
|------|------|
| 存储介质 | 文件系统，遵循 [Agent Skills](https://agentskills.io) 标准目录结构。<br> - 项目级：`.opencode/skills/`<br> - 全局级：`~/.config/opencode/skills/` |
| 目录结构 | 每个 Skill 是一个子文件夹，内含 `SKILL.md`（YAML 元数据 + Markdown 指令），可选 `scripts/`、`templates/` |
| Java 职责 | 1. 扫描 Skills 目录，读取 `name`、`description`、`triggers` 做索引<br>2. 提供 API/UI 对 Skill 文件进行增删改查（本质是文件操作）<br>3. 当用户意图匹配时，读取完整 `SKILL.md` 并注入给 OpenCode |
| 触发匹配 | 第一期：基于 `description` 与 `triggers` 的关键词匹配<br>后期：引入 BM25 或向量检索 |

**管理界面示例**：
- 列表展示所有 Skill（名称、描述、启用状态）
- 点击编辑：在线 Markdown 编辑器
- 新建 Skill：创建文件夹 + 生成 `SKILL.md` 模板

---

### 2.4 Memory（长期记忆）—— SQLite + FTS5 全文检索

**目标**：跨会话记住用户偏好、事实、历史信息，并实现检索。

**技术选型**：
- 存储：SQLite 单文件（`memory.db`），与 `config.json` 同级目录（`app.config-dir`）
- 检索引擎：**SQLite FTS5**（内置 BM25 排序，零额外依赖）
- 为何选 SQLite：① 单文件零配置，无需独立进程 ② FTS5 全文检索天然支持 BM25 ③ ACID 事务，读写安全 ④ `sqlite-jdbc` 单一 jar 无原生依赖
- 对比向量化的优势：无需 GPU、无需下载模型文件（~200MB）、无冷启动延迟

#### 2.4.1 数据库表设计

```sql
-- 记忆主表
CREATE TABLE IF NOT EXISTS memories (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    content     TEXT NOT NULL,              -- 原始记忆文本（由 OpenCode 规范化后写入）
    source      TEXT NOT NULL DEFAULT 'user', -- user / inferred / system
    tags        TEXT,                       -- JSON 数组字符串，如 '[“客户”,”电话”]'
    created_at  TEXT NOT NULL               -- ISO-8601 时间戳
);

-- FTS5 全文索引（关联 memories 表）
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
    content,
    tokenize='unicode61 tokenchars',
    content='memories',
    content_rowid='id'
);

-- 触发器：保持 FTS 索引与主表同步
CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
    INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, content) VALUES('delete', old.id, old.content);
END;

CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, content) VALUES('delete', old.id, old.content);
    INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content);
END;
```

- `content` — 原始记忆文本（支持中英文混合）
- `source` — 来源（`user` 用户主动告知、`inferred` AI 推断、`system` 系统记录）
- `tags` — 标签 JSON 数组，辅助分类过滤
- FTS5 `unicode61 tokenchars` 分词器对中文做单字索引 + 保留英文单词完整，配合 BM25 足以应对事实检索
- 记忆数量 < 1 万条时，FTS5 单次 BM25 排序检索 < 5ms

#### 2.4.2 数据访问层（MyBatis-Plus）

MyBatis-Plus 是社区推荐的 MyBatis 增强框架，提供通用的 CRUD 方法，无需手写 SQL，同时支持自定义注解方法处理 FTS5 等特殊查询。

```xml
<!-- 完整依赖：mybatis-plus + sqlite-jdbc -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <version>3.5.9</version>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.47.0.0</version>
</dependency>
```

**实体类**：

```java
@Data
@TableName(“memories”)
public class Memory {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String content;
    private String source;  // user / inferred / system
    private String tags;    // JSON 数组字符串
    private String createdAt;
}
```

**Mapper 接口**：

```java
@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {

    /** FTS5 全文检索（标准 CRUD 无法表达 MATCH 语法，手写 SQL） */
    @Select(“””
        SELECT m.id, m.content, m.source, m.tags, m.created_at,
               bm25(memories_fts, 0.0, 0.0, 1.0, 1.0) AS score
        FROM memories_fts f
        JOIN memories m ON f.rowid = m.id
        WHERE memories_fts MATCH #{ftsQuery}
        ORDER BY score
        LIMIT #{topK}
        “””)
    List<Memory> searchFts(@Param(“ftsQuery”) String ftsQuery, @Param(“topK”) int topK);
}
```

**Service 层**：

```java
@Service
public class MemoryService {

    @Autowired
    private MemoryMapper memoryMapper;

    /** 写入一条记忆 */
    public void remember(String content, String source, List<String> tags) {
        Memory m = new Memory();
        m.setContent(content);
        m.setSource(source);
        m.setTags(tags == null ? “[]” :
            “[“ + tags.stream().map(t -> “\”” + t + “\””).collect(Collectors.joining(“,”)) + “]”);
        m.setCreatedAt(LocalDateTime.now().toString());
        memoryMapper.insert(m);
        // FTS5 触发器自动同步索引
    }

    /** BM25 检索 Top-K */
    public List<Memory> search(String query, int topK) {
        String ftsQuery = String.join(“ AND “, query.split(“\\s+”));
        return memoryMapper.searchFts(ftsQuery, topK);
    }

    /** 按 ID 删除（遗忘） */
    public void forget(Integer id) {
        memoryMapper.deleteById(id);
        // FTS5 触发器自动同步索引
    }
}
```

**MyBatis-Plus 配置**：

```yaml
# application.yml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true   # created_at → createdAt
  global-config:
    db-config:
      id-type: auto                      # SQLite AUTOINCREMENT
  # SQLite 分页方言：需注册 PaginationInnerInterceptor + 自定义方言
```

> **注意**：MyBatis-Plus 默认不内置 SQLite 分页方言，首次集成时需注册一个 `PaginationInnerInterceptor`，并指定 `SqliteDialect`（或直接使用 `JsqlParserCountSqlParser` 自动解析 COUNT 语句）。标准 CRUD 和 FTS5 查询无需分页插件也能正常运行。

#### 2.4.3 记忆存取流程

**写入（用户说”记住X”）**：
1. 用户：”记住客户张三的电话是 138xxxx”
2. Java 调用 OpenCode 生成规范化的记忆文本
3. 调用 `MemoryService.remember()` 写入 SQLite（FTS 触发器自动建索引）

**检索（用户提问时）**：
1. 收到用户问题（如”张三电话多少”）
2. 调用 `MemoryService.search()` 执行 FTS5 BM25 检索
3. 取 Top 5 记忆文本拼接到 System Prompt 中

**遗忘**：
- 调用 `MemoryService.forget(id)` 删除，触发器自动同步 FTS 索引

#### 2.4.4 第一期上线策略

- 不要求完美语义匹配，FTS5 BM25 评分足够应对大多数事实类记忆
- 中文按单字索引，对查找姓名、电话、项目名等关键词效果良好
- 后期如需更好中文分词，可升级为 FTS5 + Jieba 自定义分词器，或替换为 Lucene
- `memory.db` 单文件，备份即迁移

---

### 2.5 聊天记录 —— SQLite 存储，独立于笔记库

**目标**：将对话历史从笔记库 `chat/chat_sessions.json` 迁移到 Java 端 SQLite，实现笔记与机器数据的干净分离。

**现状问题**：
- 当前聊天记录保存到 `{笔记库根目录}/chat/chat_sessions.json`，与人工笔记混在一起
- 对话记录更新频繁（每次 AI 回复都追加），笔记库若使用 Git 管理会产生大量噪音
- 聊天记录是 AI 对话的副产品，不属于"人写的内容"，应独立于笔记库管理

**技术选型**：
- 存储：与 Memory 共用 `memory.db` 文件，新增 `chat_sessions` 和 `chat_messages` 两张表
- 数据访问：与 Memory 共用 MyBatis-Plus ORM，CRUD 全部继承 `BaseMapper`
- 一个 `memory.db` 即可完成长期记忆 + 对话历史 + 未来其他结构化数据的统一管理

#### 2.5.1 数据库表设计

```sql
-- 对话会话表
CREATE TABLE IF NOT EXISTS chat_sessions (
    id          TEXT PRIMARY KEY,             -- 会话 ID（UUID 风格）
    title       TEXT NOT NULL DEFAULT '新对话',
    mode        TEXT NOT NULL DEFAULT 'knowledge', -- knowledge / coding
    created_at  TEXT NOT NULL,                -- ISO-8601
    updated_at  TEXT NOT NULL                 -- ISO-8601
);

-- 聊天消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,                -- 外键 → chat_sessions.id
    role        TEXT NOT NULL,                -- 'user' / 'assistant'
    content     TEXT NOT NULL,
    mode        TEXT NOT NULL DEFAULT 'knowledge',
    created_at  TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

-- 按会话加载消息的索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_session
    ON chat_messages(session_id, created_at);
```

**与 JSON 文件方案的对比**：

| 维度 | 当前（笔记库 JSON） | 迁移后（SQLite） |
|------|--------------------|-----------------|
| 存储位置 | 笔记库目录 | `memory.db`（程序根目录） |
| 数据性质 | 机器数据混入笔记 | 纯程序数据，笔记库干净 |
| 消息追加 | 读整个 JSON → 修改 → 写整个 JSON | INSERT 单行，无 IO 放大 |
| 历史加载 | 读整个 JSON → 内存过滤 → 截取上下文 | SELECT 按会话分页，毫秒级 |
| 并发安全 | 无（多线程同时写可能冲突） | ACID 事务，安全 |
| 笔记浏览页可见 | 是（与笔记混在一起） | 否（需单独聊天历史页面） |

#### 2.5.2 迁移策略

1. **停写旧文件**：Java 改为读写 `memory.db` 中的 `chat_messages` 表
2. **首次迁移**：应用启动时检查 `{笔记库根目录}/chat/chat_sessions.json` 是否存在，若存在则逐条插入 `chat_messages` 表，迁移完成后重命名旧文件为 `chat_sessions.json.bak`
3. **渐进迁移**：迁移过程不可见、不影响用户操作，旧文件保留备份供回滚
4. **管理界面**：聊天历史页面改为查 SQLite，不再依赖文件系统

#### 2.5.3 数据访问（MyBatis-Plus）

与 Memory 共用同一数据源，复用同一套 MyBatis-Plus 配置，无需额外依赖。

**实体类**：

```java
@Data
@TableName("chat_sessions")
public class ChatSession {
    @TableId
    private String id;
    private String title;
    private String mode;     // knowledge / coding
    private String createdAt;
    private String updatedAt;
}

@Data
@TableName("chat_messages")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String sessionId;
    private String role;     // user / assistant
    private String content;
    private String mode;
    private String createdAt;
}
```

**Mapper 接口**：

```java
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
    // 全部继承自 BaseMapper：insert / deleteById / selectById / selectList / updateById
    // 按更新时间倒序获取会话列表
    @Select("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    List<ChatSession> listAllOrderByUpdate();
}

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    // 按会话加载消息（时间正序）
    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<ChatMessage> listBySession(@Param("sessionId") String sessionId);

    // 加载最近 N 条消息作为上下文
    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatMessage> listRecentBySession(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
```

Mapper 继承 `BaseMapper` 后自动获得单表 CRUD 能力：
- `chatSessionMapper.insert(session)` — 新建会话
- `chatMessageMapper.insert(msg)` — 追加消息
- `chatSessionMapper.updateById(session)` — 更新标题/时间
- `chatMessageMapper.selectCount(queryWrapper)` — 统计消息数

无需手写任何 INSERT/UPDATE/DELETE SQL，MyBatis-Plus Lambda QueryWrapper 即可构建动态查询条件。

#### 2.5.4 与 Memory 的关联

- 共用同一 `memory.db` + 同一 MyBatis-Plus 数据源配置，无需额外组件
- 记忆检索时可选 CHAT 上下文补充：查询 `chat_messages` 中当前会话的近 N 条消息，作为对话历史注入 Prompt
- 后期可实现"从聊天记录中提炼长期记忆"的能力

---

## 三、对话处理流程（飞书 → Java → OpenCode）

```
用户飞书发送消息
    │
    ▼
Spring Boot 接收（验证、解密）
    │
    ├─► 从 rules.json 加载 Rules（全量）
    ├─► 读取 AGENTS.md 文件内容
    ├─► 用 BM25 检索 Memory（Top 5）
    ├─► 识别意图，匹配 Skill（若命中）
    │
    ▼
组装最终 Prompt：
    [System Rules]
    [AGENTS.md 内容]
    [相关长期记忆]
    [Skill 指令（如有）]
    [用户问题]
    │
    ▼
调用 OpenCode CLI（或 HTTP API）
    │
    ▼
OpenCode 推理 + 执行（可能调用 MCP 工具、读写文件等）
    │
    ▼
返回结果 → Spring Boot → 回复飞书
```

---

## 四、实施路线图（分阶段交付）

| 阶段 | 核心任务 | 预计工时 | 交付物 |
|------|----------|----------|--------|
| **Phase 1** | ① Rules 数据库表 + 全量加载<br>② AGENTS.md 读写<br>③ 基础对话流程打通 | 3 人天 | Java 服务可调用 OpenCode 返回结果 |
| **Phase 1b** | ① `memory.db` 建库建表（Memory + Chat 共用）<br>② 聊天记录从 JSON 迁移到 SQLite<br>③ 兼容旧文件读取（渐进迁移） | 2 人天 | 聊天记录不再依赖笔记库，`chat_sessions.json` 停写 |
| **Phase 2** | ① Skills 目录扫描与匹配<br>② 提供 Skills CRUD 接口<br>③ 简单管理界面（HTML） | 5 人天 | 可在线管理 Skills，AI 能按指令执行特定技能 |
| **Phase 3** | ① SQLite FTS5 集成<br>② 记忆存取与检索<br>③ 记忆管理 UI（查看/删除/遗忘） | 3 人天 | 长期记忆功能可用，用户可”记住/遗忘” |
| **Phase 4** | ① 性能优化（缓存、异步）<br>② 日志与监控<br>③ 多租户隔离 | 3 人天 | 生产级稳定版本 |

**总计**：约 16 人天完成 MVP，可支撑个人使用及小团队内测。

---

## 五、运维与扩展要点

- **SQLite 数据库备份**：`memory.db` 单文件，停止服务后直接复制即可完整备份。建议定时（如每天）复制到备份目录。
- **Skills 目录同步**：建议将 `.opencode/skills/` 纳入 Git 仓库，支持版本管理。
- **Memory 数据量**：单表建议不超过 10 万条（FTS5 索引文件约 50MB），超出后可按月归档分表。
- **安全**：所有 Rules 和 Memory 按 `tenant_id` 或 `user_id` 隔离，防止跨用户数据泄露。

---

## 六、总结

本方案以 **Java 全面接管 AI 资产的配置、存储与检索** 为核心，保留了 OpenCode 的强大推理能力。长期记忆采用 **SQLite + FTS5（BM25 全文检索）** 替代向量化方案，聊天记录从笔记库 JSON 迁移至同一 `memory.db` 统一管理，实现零外部依赖、无需 GPU、无需下载模型文件，存储与检索合二为一，保持与现有架构一致的单文件、零运维风格。

按照上述路线图实施，你将获得一个**轻量、无依赖、可演化**的 AI 工作助手。

---

## 七、代码规范

本项目的 Java 代码规范参照 `d:/projects/qihang-erp-open` 项目（ERP 管理系统）的编码风格，统一团队协作基准。

### 7.1 包结构

```
com.laoqi.assistant
  ├── controller    # Web 层（@Controller / @RestController）
  ├── service       # 业务接口
  ├── service.impl  # 业务实现（@Service）
  ├── mapper        # MyBatis-Plus Mapper（extends BaseMapper）
  ├── entity        # ORM 实体（@Data + @TableName）
  ├── config        # 配置类
  └── ……
```

- 保持 `com.laoqi.assistant` 根包不变（与该项目的 Spring Boot 启动类一致）
- Mapper 与 Entity 一一对应，按业务模块分包（若模块增多可参考 ERP 的 `sys`/`oms` 模块拆分）

### 7.2 Controller 层

与 ERP 项目的 `BaseController` 模式保持一致，所有 Controller **继承统一基类**，提供标准返回辅助方法。

```java
@RestController
@RequestMapping("/api/memory")
public class MemoryController extends BaseController {

    @Autowired
    private IMemoryService memoryService;

    @GetMapping("/search")
    @Validated
    public TableDataInfo search(String query, @RequestParam(defaultValue = "5") int topK) {
        List<Memory> list = memoryService.search(query, topK);
        return getDataTable(list);
    }

    @PostMapping("/remember")
    public AjaxResult remember(@RequestBody MemorySaveRequest req) {
        memoryService.remember(req.getContent(), req.getSource(), req.getTags());
        return success("已记住");
    }

    @DeleteMapping("/forget/{id}")
    public AjaxResult forget(@PathVariable Integer id) {
        memoryService.forget(id);
        return success("已遗忘");
    }
}
```

| 规则 | 说明 |
|------|------|
| 注解 | 类上 `@RestController` + `@RequestMapping`，方法上 `@GetMapping`/`@PostMapping`/`@DeleteMapping` |
| 继承 | `extends BaseController`（获取 `getDataTable()`、`success()`、`error()` 等辅助方法） |
| 注入 | 使用 `@Autowired` 注入**服务接口**（而非实现类）——如 `IMemoryService` |
| 参数校验 | Controller 方法参数上使用 `@Validated`，配合 `@NotNull`、`@NotEmpty` 等注解 |
| 返回类型 | 单条/操作结果返回 `AjaxResult`；分页列表返回 `TableDataInfo` |

**BaseController 提供的辅助方法**：

| 方法 | 用途 |
|------|------|
| `getDataTable(list)` | 将列表包装为 `TableDataInfo`（含总记录数、页码等分页信息） |
| `success()` / `success(data)` | 返回操作成功的 `AjaxResult` |
| `error(msg)` / `error(code, msg)` | 返回操作失败的 `AjaxResult` |
| `toAjax(boolean)` / `toAjax(int)` | 将影响行数转换为 `AjaxResult` |
| `getUserId()` / `getUsername()` | 获取当前登录用户信息（预留权限场景） |

### 7.3 Service 层

遵循**接口 + 实现类分离**模式：

```java
public interface IMemoryService {
    void remember(String content, String source, List<String> tags);
    List<Memory> search(String query, int topK);
    void forget(Integer id);
}

@Service
public class MemoryServiceImpl implements IMemoryService {

    @Autowired
    private MemoryMapper memoryMapper;

    @Override
    public void remember(String content, String source, List<String> tags) {
        // 业务逻辑
    }
}
```

命名约定：
- 接口：`I{Name}Service`（字母 `I` 前缀，如 `IMemoryService`）
- 实现：`{Name}ServiceImpl`（`Impl` 后缀，如 `MemoryServiceImpl`）
- 类上标注 `@Service`

### 7.4 Mapper 层（MyBatis-Plus）

所有 Mapper 继承 `BaseMapper<Entity>`，自动获得单表 CRUD。特殊查询（如 SQLite FTS5 `MATCH`）使用 `@Select` 手写 SQL：

```java
@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {

    @Select("""
        SELECT m.id, m.content, m.source, m.tags, m.created_at,
               bm25(memories_fts, 0.0, 0.0, 1.0, 1.0) AS score
        FROM memories_fts f
        JOIN memories m ON f.rowid = m.id
        WHERE memories_fts MATCH #{ftsQuery}
        ORDER BY score
        LIMIT #{topK}
        """)
    List<Memory> searchFts(@Param("ftsQuery") String ftsQuery, @Param("topK") int topK);
}
```

- `@Mapper` 标注在接口上（或在启动类使用 `@MapperScan` 批量扫描）
- `extends BaseMapper<Entity>` 后无需手写 INSERT / UPDATE / DELETE / SELECT 的基本 SQL
- 自定义查询参数使用 `@Param` 注解

### 7.5 Entity 层

参照 ERP 项目的实体模式：

```java
@Data
@TableName("memories")
public class Memory {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String content;
    private String source;
    private String tags;
    @TableField("created_at")
    private String createdAt;
}
```

| 注解/约定 | 说明 |
|-----------|------|
| `@Data` | Lombok 生成 getter/setter/toString/equals/hashCode |
| `@TableName` | 指定映射的数据库表名 |
| `@TableId` | 主键字段，`AUTO` 类型对应 SQLite `AUTOINCREMENT` |
| `@TableField` | 字段名映射（`map-underscore-to-camel-case` 开启时可省略，仅特殊映射时使用） |

- 字段类型：SQLite 无原生 `datetime` 类型，统一使用 `String` + ISO-8601 格式
- 字段命名：Java 使用驼峰（`createdAt`），数据库使用下划线（`created_at`），通过 MyBatis-Plus `map-underscore-to-camel-case: true` 自动映射

### 7.6 代码格式

- **缩进**：4 个空格（不使用 Tab）
- **花括号**：左大括号不换行（同行风格，K&R 风格）
- **Javadoc**：公共方法需写 Javadoc 注释，包含 `@param` 和 `@return`，类注释含 `@author`
- **Lombok**：使用 `@Data`、`@Slf4j`（日志），减少样板代码
- **注释掉的代码**：保留在文件中不做删除（参照 ERP 项目风格），可通过版本历史追溯

示例：

```java
/**
 * 记忆服务实现
 *
 * @author your-name
 */
@Slf4j
@Service
public class MemoryServiceImpl implements IMemoryService {

    @Autowired
    private MemoryMapper memoryMapper;

    /**
     * 写入一条记忆并同步 FTS5 全文索引
     *
     * @param content 记忆文本
     * @param source  来源（user / inferred / system）
     * @param tags    标签列表
     */
    @Override
    public void remember(String content, String source, List<String> tags) {
        Memory m = new Memory();
        m.setContent(content);
        m.setSource(source);
        m.setTags(serializeTags(tags));
        m.setCreatedAt(LocalDateTime.now().toString());
        memoryMapper.insert(m);
        log.info("记忆已保存: id={}, source={}", m.getId(), source);
    }
}
```

### 7.7 与现有项目的风格差异说明

当前 `assistant-v2` 项目风格与 ERP 项目的对照：

| 维度 | 当前项目 | 升级后（参照 ERP） |
|------|----------|-------------------|
| Controller 基类 | 无统一基类 | `extends BaseController` |
| 返回封装 | 直接返回 `String` / `ModelAndView` | `AjaxResult` + `TableDataInfo` |
| 分层 | Controller ↔ Service 混合 | Controller → Service 接口 → Impl 实现 |
| ORM | 无（纯 JSON 文件） | MyBatis-Plus `BaseMapper<Entity>` |
| 代码生成 | 无 | 可按模块统一生成实体、Mapper、Service、Controller |

升级时逐步对齐增量代码，存量页面（如 Thymeleaf 模板页面）不做强行重构。