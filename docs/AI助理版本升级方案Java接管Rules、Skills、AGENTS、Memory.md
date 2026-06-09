# AI助理版本升级方案：Java接管Rules、Skills、AGENTS.md与Memory

## 一、总体架构

- **前端入口**：飞书（接收用户对话、发送回复）
- **后端核心**：Spring Boot（Java 17+）
- **AI大脑**：OpenCode（负责推理、任务执行）
- **数据层**：PostgreSQL + PGVector + 文件系统（Skills目录）
- **向量化引擎**：ONNX Runtime + BGE-small-zh-v1.5（进程内运行）

**核心原则**：Java管理所有可配置、可持久化的AI资产（Rules、AGENTS.md、Skills、Memory），OpenCode只负责推理与工具调用。

---

## 二、模块详细设计

### 2.1 Rules（规则）—— 数据库全量管理

**目标**：定义AI始终遵守的行为边界（安全、格式、身份等）。

| 项目 | 内容 |
|------|------|
| 存储介质 | PostgreSQL 表 `ai_rules` |
| 关键字段 | `id`, `name`, `content`(TEXT), `enabled`(BOOLEAN), `priority`(INT), `triggers`(TEXT), `scope`(VARCHAR) |
| 加载方式 | 每次对话启动时，Java查询所有 `enabled=true` 的规则，按 `priority` 排序，拼接成字符串。 |
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

### 2.4 Memory（长期记忆）—— PGVector + ONNX 向量化

**目标**：跨会话记住用户偏好、事实、历史信息，并实现语义检索。

**技术选型**：
- 向量数据库：PostgreSQL + PGVector 扩展
- 向量化引擎：ONNX Runtime + **BGE-small-zh-v1.5**（进程内运行，无网络依赖）
- 框架：LangChain4j 或 Spring AI（推荐 LangChain4j 简化集成）

#### 2.4.1 数据库表设计

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE long_term_memory (
    id          BIGSERIAL PRIMARY KEY,
    content     TEXT NOT NULL,                 -- 原始记忆文本
    embedding   VECTOR(512),                   -- BGE-small-zh 输出 512 维
    metadata    JSONB,                         -- 用户ID、时间戳、类型等
    created_at  TIMESTAMP DEFAULT NOW()
);

-- 创建 HNSW 索引加速相似度搜索
CREATE INDEX ON long_term_memory USING HNSW (embedding vector_cosine_ops);
```

#### 2.4.2 向量化服务（Java 进程内）

```java
// 使用 LangChain4j 的 BGE 模型封装
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-embeddings-bge-small-zh-v15</artifactId>
    <version>1.15.1-beta25</version>
</dependency>

@Service
public class MemoryVectorService {
    private final EmbeddingModel embeddingModel = new BgeSmallZhV15EmbeddingModel();
    
    public List<Float> embed(String text) {
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vectorAsList();
    }
}
```

#### 2.4.3 记忆存取流程

**写入（用户说“记住X”）**：
1. 用户：“记住客户张三的电话是 138xxxx”
2. Java 调用 OpenCode 生成规范化的记忆文本（可选）
3. 调用 `MemoryVectorService.embed()` 得到向量
4. 插入 `long_term_memory` 表（content + embedding + metadata）

**检索（用户提问时）**：
1. 收到用户问题（如“张三电话多少”）
2. 将问题向量化
3. 执行 SQL：
   ```sql
   SELECT content, 1 - (embedding <=> :query_vector) AS similarity
   FROM long_term_memory
   WHERE metadata->>'user_id' = :userId
   ORDER BY embedding <=> :query_vector
   LIMIT 5;
   ```
4. 将检索到的记忆文本拼接到 System Prompt 中

#### 2.4.4 第一期上线策略

- 不要求完美语义匹配，允许低相似度结果（阈值可调）
- 提供“遗忘”接口：按 ID 删除记忆
- 记忆数量 < 5000 条时，PGVector 配合 HNSW 索引性能足够

---

## 三、对话处理流程（飞书 → Java → OpenCode）

```
用户飞书发送消息
    │
    ▼
Spring Boot 接收（验证、解密）
    │
    ├─► 从数据库加载 Rules（全量）
    ├─► 读取 AGENTS.md 文件内容
    ├─► 用用户问题检索 Memory（Top 5）
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
| **Phase 3** | ① PGVector 环境搭建<br>② ONNX + BGE 模型集成<br>③ 记忆存取与检索 | 5 人天 | 长期记忆功能可用，用户可“记住/遗忘” |
| **Phase 4** | ① 性能优化（缓存、异步）<br>② 日志与监控<br>③ 多租户隔离 | 3 人天 | 生产级稳定版本 |

**总计**：约 16 人天完成 MVP，可支撑个人使用及小团队内测。

---

## 五、运维与扩展要点

- **模型文件存储**：BGE-small-zh 模型文件（约 200MB）放在 `src/main/resources/models/`，启动时加载。
- **Skills 目录同步**：建议将 `.opencode/skills/` 纳入 Git 仓库，支持版本管理。
- **Memory 的冷热分离**：超过 3 个月未命中的记忆可定时迁移到归档表，保持检索性能。
- **安全**：所有 Rules 和 Memory 按 `tenant_id` 或 `user_id` 隔离，防止跨用户数据泄露。

---

## 六、总结

本方案以 **Java 全面接管 AI 资产的配置、存储与检索** 为核心，保留了 OpenCode 的强大推理能力，并选择了 **PGVector + ONNX Runtime（BGE-small-zh）** 作为长期记忆的向量化方案，实现了一体化、低运维、高度可控的企业级 AI 助理架构。

按照上述路线图实施，你将获得一个**可商用、可演化、语义智能**的 AI 工作助手。