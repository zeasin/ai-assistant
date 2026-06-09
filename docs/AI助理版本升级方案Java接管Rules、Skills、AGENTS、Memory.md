# AI助理版本升级方案：Java接管Rules、Skills、AGENTS.md与Memory

## 一、总体架构

- **前端入口**：飞书（接收用户对话、发送回复）
- **后端核心**：Spring Boot（Java 17+）
- **AI大脑**：OpenCode（负责推理、任务执行）
- **数据层**：文件系统（JSON + Markdown）+ SQLite（Memory 长期记忆）
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
- 存储：SQLite 单文件（`memory.db`），与 `config.json` 同级目录
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

#### 2.4.2 检索服务（Java 进程内）

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.47.0.0</version>
</dependency>
```

```java
@Service
public class MemoryService {

    private final javax.sql.DataSource dataSource;

    public MemoryService() {
        // 嵌入式 SQLite，无需连接池，单连接即可
        String url = “jdbc:sqlite:memory.db”;
        this.dataSource = new org.sqlite.SQLiteDataSource();
        ((org.sqlite.SQLiteDataSource) this.dataSource).setUrl(url);
        initSchema();
    }

    private void initSchema() {
        // 执行上面的建表 SQL，自动创建表和 FTS5 索引
    }

    /** 写入一条记忆 */
    public void remember(String content, String source, List<String> tags) {
        String tagsJson = tags == null ? “[]” :
            “[“ + tags.stream().map(t -> “\”” + t + “\””).collect(Collectors.joining(“,”)) + “]”;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     “INSERT INTO memories (content, source, tags, created_at) VALUES (?,?,?,datetime('now'))”)) {
            ps.setString(1, content);
            ps.setString(2, source);
            ps.setString(3, tagsJson);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** BM25 检索 Top-K 记忆 */
    public List<Memory> search(String query, int topK) {
        var result = new ArrayList<Memory>();
        String sql = “””
            SELECT m.id, m.content, m.source, m.tags, m.created_at,
                   bm25(memories_fts, 0.0, 0.0, 1.0, 1.0) AS score
            FROM memories_fts f
            JOIN memories m ON f.rowid = m.id
            WHERE memories_fts MATCH ?
            ORDER BY score
            LIMIT ?
            “””;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // FTS5 查询语法：空格分隔的词进行隐式 AND 匹配
            String ftsQuery = String.join(“ AND “, query.split(“\\s+”));
            ps.setString(1, ftsQuery);
            ps.setInt(2, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Memory(
                        rs.getInt(“id”),
                        rs.getString(“content”),
                        rs.getString(“source”),
                        rs.getString(“tags”),
                        rs.getString(“created_at”)
                    ));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /** 按 ID 删除（遗忘） */
    public void forget(int id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(“DELETE FROM memories WHERE id = ?”)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

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

本方案以 **Java 全面接管 AI 资产的配置、存储与检索** 为核心，保留了 OpenCode 的强大推理能力。长期记忆采用 **SQLite + FTS5（BM25 全文检索）** 替代向量化方案，零外部依赖、无需 GPU、无需下载模型文件，存储与检索合二为一，保持与现有架构一致的单文件、零运维风格。

按照上述路线图实施，你将获得一个**轻量、无依赖、可演化**的 AI 工作助手。