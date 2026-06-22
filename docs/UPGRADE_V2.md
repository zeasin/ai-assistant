# 笔灵 AI v2.0 升级方案：从工具型AI到Agent型AI

## 一、升级背景

### 当前系统能力

| 能力 | 实现 | 局限性 |
|------|------|--------|
| 文件名搜索 | `NoteTools.searchFiles()` | 只搜文件名，不搜内容 |
| 对话语义检索 | `SessionService` + Ollama | 只检索历史对话，不检索笔记 |
| 系统提示词 | `NoteAssistantService.SYSTEM_PROMPT` 硬编码 | 用户无法自定义 |
| 日报提示词 | `AI/综合日报/分析提示词.md` | 只用于日报，无法复用 |
| AGENTS.md | 手动编辑 | 规则文件，不是提示词模板 |

### 核心问题

1. **搜索能力不足**：用户问"张三的跟进记录"，AI只能搜文件名含"张三"的文件，无法搜文件内容中提到"张三"的笔记
2. **提示词不可组合**：用户无法告诉AI"用日报模板+客户分析模板来处理这个问题"
3. **主动性缺失**：AI不会主动搜索相关笔记，需要用户明确说"帮我搜一下"

---

## 二、升级目标

| 当前状态 | 升级目标 |
|---------|---------|
| 只能搜文件名 | 笔记内容语义搜索 |
| 系统提示词硬编码 | 提示词模板库 + 场景化组合 |
| 被动响应（用户说搜才搜） | 主动检索 + 自动注入上下文 |
| 工具型AI（等待指令） | Agent型AI（主动思考+执行） |

---

## 三、模块设计

### 模块1：笔记内容索引与搜索

#### 3.1.1 数据模型

```sql
-- 笔记内容索引表
CREATE TABLE note_embeddings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kb_id       INTEGER NOT NULL,
    file_path   TEXT NOT NULL,           -- 相对路径，如 "工作/客户/ABC公司.md"
    chunk_index INTEGER NOT NULL DEFAULT 0,  -- 分块序号（从0开始）
    content     TEXT NOT NULL,           -- 原文片段（200-500字）
    embedding   TEXT NOT NULL,           -- 向量(Base64编码)
    content_hash TEXT NOT NULL,          -- 内容哈希，用于增量更新
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    UNIQUE(kb_id, file_path, chunk_index)
);

CREATE INDEX idx_note_embeddings_kb ON note_embeddings(kb_id);
CREATE INDEX idx_note_embeddings_path ON note_embeddings(kb_id, file_path);
```

#### 3.1.2 分块策略

```
原始文件: "工作/客户/ABC公司.md" (2000字)

分块结果:
├── chunk_0: 第1-400字 (重叠50字)
├── chunk_1: 第350-800字 (重叠50字)
├── chunk_2: 第750-1200字 (重叠50字)
└── chunk_3: 第1150-1600字 (重叠50字)
```

**分块规则：**
- 每块200-500字（可配置）
- 块间重叠50字，避免上下文断裂
- 优先按段落分割，保持语义完整
- 保留文件路径和块序号用于定位

#### 3.1.3 核心服务：NoteIndexService

```java
@Service
public class NoteIndexService {
    
    private final OllamaEmbeddingService embeddingService;
    private final KnowledgeBaseService kbService;
    
    /**
     * 构建索引 - 扫描笔记库，对新增/修改的文件建立索引
     */
    public IndexResult indexKB(Long kbId) {
        // 1. 获取笔记库路径
        // 2. 扫描所有 .md 和 .json 文件
        // 3. 对比 content_hash，只处理新增/修改的文件
        // 4. 分块 + 生成向量 + 写入数据库
        // 5. 返回统计信息
    }
    
    /**
     * 增量索引 - 只处理变更文件
     */
    public IndexResult indexIncremental(Long kbId) {
        // 对比文件修改时间和 content_hash
        // 只重新索引变更的文件
    }
    
    /**
     * 语义搜索 - 根据查询向量检索相关笔记
     */
    public List<NoteSearchResult> search(Long kbId, String query, int limit) {
        // 1. 将 query 转为向量
        // 2. 从 note_embeddings 表检索同 kb_id 的向量
        // 3. 计算余弦相似度
        // 4. 返回 Top-N 结果
    }
    
    /**
     * 混合搜索 - 语义(0.7) + 关键词(0.3)
     */
    public List<NoteSearchResult> hybridSearch(Long kbId, String query, int limit) {
        // 语义搜索 + 关键词 LIKE 匹配
        // 加权合并结果
    }
    
    /**
     * 清空索引
     */
    public void clearIndex(Long kbId);
    
    /**
     * 获取索引统计
     */
    public IndexStats getIndexStats(Long kbId);
}
```

#### 3.1.4 搜索结果模型

```java
public record NoteSearchResult(
    String filePath,        // 文件路径：工作/客户/ABC公司.md
    String content,         // 匹配的内容片段（200字）
    float score,            // 相似度分数（0-1）
    int chunkIndex,         // 分块序号
    int totalChunks,        // 总分块数
    long fileSize           // 文件大小
) {}
```

#### 3.1.5 新增@Tool方法

```java
@Component
public class NoteTools {
    
    // ... 现有工具 ...
    
    @Tool(description = "搜索笔记库内容，基于语义理解查找相关笔记片段。当用户询问某个主题、人物、事件时使用此工具")
    public String searchNotes(
            @ToolParam(description = "搜索关键词或自然语言查询，如'张三的跟进记录'、'本周工作重点'") String query,
            @ToolParam(description = "返回结果数量，默认5，最多20") int limit) {
        
        Long kbId = getCurrentKbId();
        if (kbId == null) return "未指定知识库";
        
        List<NoteSearchResult> results = noteIndexService.search(kbId, query, limit);
        
        if (results.isEmpty()) {
            return "未找到与「" + query + "」相关的笔记内容";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 条相关笔记：\n\n");
        
        for (int i = 0; i < results.size(); i++) {
            NoteSearchResult r = results.get(i);
            sb.append("📄 ").append(r.filePath());
            sb.append(" (相似度: ").append(String.format("%.2f", r.score())).append(")\n");
            sb.append("内容摘要：").append(r.content()).append("\n\n");
        }
        
        return sb.toString();
    }
    
    @Tool(description = "读取指定笔记文件的完整内容")
    public String readNote(
            @ToolParam(description = "文件路径，相对于笔记库根目录") String path) {
        // 与 readFile 相同实现，但语义更明确
        return readFile(path);
    }
}
```

---

### 模块2：提示词组合系统

#### 3.2.1 数据模型

```sql
-- 提示词模板表
CREATE TABLE prompt_templates (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kb_id       INTEGER,                -- NULL表示全局模板，非NULL表示KB专属
    name        TEXT NOT NULL,           -- 模板名称：日报生成
    category    TEXT NOT NULL,           -- 分类：system/workflow/analysis/custom
    description TEXT DEFAULT '',         -- 模板描述
    content     TEXT NOT NULL,           -- 模板内容（支持变量）
    variables   TEXT DEFAULT '{}',       -- 变量定义 JSON，如 {"date":"当前日期","weekday":"星期"}
    is_default  INTEGER DEFAULT 0,      -- 是否该分类的默认模板
    sort_order  INTEGER DEFAULT 0,      -- 排序
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX idx_prompt_templates_kb ON prompt_templates(kb_id);
CREATE INDEX idx_prompt_templates_category ON prompt_templates(category);
```

#### 3.2.2 模板分类设计

| 分类 | 用途 | 示例模板 | 变量 |
|------|------|---------|------|
| `system` | 系统角色定义 | "笔记库助手"、"数据分析专家" | - |
| `workflow` | 工作流模板 | "日报生成"、"客户跟进" | `{date}`, `{weekday}` |
| `analysis` | 分析模板 | "目录分析"、"数据洞察" | `{dir}`, `{data_type}` |
| `custom` | 用户自定义 | 任意场景 | 任意 |

#### 3.2.3 预置模板示例

**模板1：日报生成（workflow）**

```markdown
现在是{date} {weekday}。请根据我的工作笔记生成今天的综合日报。

## 工作流程

1. 先用 searchNotes 搜索以下关键词：
   - "今日工作"
   - "客户沟通"
   - "开发进展"
   - "文章发布"
2. 用 readFile 读取 AGENTS.md 了解数据格式
3. 综合搜索结果，生成结构化日报

## 输出格式

【今日重点】
- ...

【客户沟通】
- ...

【开发进展】
- ...

【文章发布】
- ...

【明日计划】
- ...

注意：如果某个板块没有相关信息，请写"暂无"。
```

**模板2：客户跟进分析（analysis）**

```markdown
请分析客户"{customer_name}"的跟进情况。

## 工作流程

1. 用 searchNotes 搜索"{customer_name}"相关笔记
2. 用 readFile 读取找到的相关文件
3. 综合分析客户状态、历史沟通、待办事项

## 输出要求

- 客户基本信息
- 历史沟通摘要
- 当前状态
- 待办事项
- 建议下一步行动
```

#### 3.2.4 核心服务：PromptTemplateService

```java
@Service
public class PromptTemplateService {
    
    /**
     * 列出模板
     */
    public List<PromptTemplate> listTemplates(Long kbId, String category);
    
    /**
     * 获取模板
     */
    public PromptTemplate getTemplate(Long id);
    
    /**
     * 保存模板
     */
    public void saveTemplate(PromptTemplate template);
    
    /**
     * 删除模板
     */
    public void deleteTemplate(Long id);
    
    /**
     * 组合提示词 - 核心能力
     * 
     * 根据用户选择的模板 + 变量 + 上下文，生成最终提示词
     */
    public String compose(ComposeRequest request) {
        // 1. 加载模板
        // 2. 替换变量 {date} → 2024-01-15
        // 3. 如果模板要求搜索，返回搜索指令
        // 4. 返回最终提示词
    }
    
    /**
     * 智能推荐 - 根据用户输入推荐合适的模板
     */
    public List<PromptTemplate> recommend(Long kbId, String userMessage);
}
```

#### 3.2.5 组合请求模型

```java
public record ComposeRequest(
    Long kbId,
    Long templateId,           // 模板ID
    String templateName,       // 或者按名称查找
    Map<String, String> variables,  // 变量替换
    List<String> contextFiles,      // 额外上下文文件
    String customInstructions       // 用户额外指令
) {}
```

#### 3.2.6 组合结果模型

```java
public record ComposeResult(
    String systemPrompt,       // 最终的system prompt
    List<String> searchQueries, // 建议搜索的关键词
    List<String> readFiles,    // 建议读取的文件
    Map<String, String> resolvedVariables  // 已解析的变量
) {}
```

---

### 模块3：主动搜索与上下文注入

#### 3.3.1 核心机制

**当前流程：**
```
用户提问 → 注入历史对话 → AI自行决定是否调用工具 → 回复
```

**升级后流程：**
```
用户提问 → 注入历史对话 → 系统主动搜索相关笔记 → 注入到上下文 → AI基于丰富上下文回复
```

#### 3.3.2 上下文构建器：ContextBuilder

```java
@Service
public class ContextBuilder {
    
    private final NoteIndexService noteIndexService;
    private final SessionService sessionService;
    private final PromptTemplateService promptService;
    
    /**
     * 构建完整上下文
     */
    public ChatContext build(String sessionId, String userMessage, Long kbId) {
        // 1. 注入历史对话（已有能力）
        String historyContext = sessionService.buildHistoryContext(sessionId, "knowledge", userMessage);
        
        // 2. 主动搜索相关笔记（新增能力）
        List<NoteSearchResult> relevantNotes = noteIndexService.search(kbId, userMessage, 5);
        
        // 3. 读取规则文件（已有能力）
        String agentsMd = readFile("AGENTS.md");
        
        // 4. 组合所有上下文
        return new ChatContext(
            historyContext,
            relevantNotes,
            agentsMd,
            buildNotesContext(relevantNotes)
        );
    }
    
    /**
     * 将搜索结果格式化为上下文
     */
    private String buildNotesContext(List<NoteSearchResult> notes) {
        if (notes.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("== 相关笔记内容 ==\n\n");
        
        for (NoteSearchResult note : notes) {
            sb.append("【").append(note.filePath()).append("】\n");
            sb.append(note.content()).append("\n\n");
        }
        
        sb.append("请基于以上相关笔记内容，结合你的知识，回复用户的问题。");
        return sb.toString();
    }
}
```

#### 3.3.3 上下文模型

```java
public record ChatContext(
    String historyContext,           // 历史对话上下文
    List<NoteSearchResult> relevantNotes,  // 相关笔记搜索结果
    String agentsMd,                 // AGENTS.md 内容
    String notesContext              // 格式化后的笔记上下文
) {
    /**
     * 合并为完整的上下文字符串
     */
    public String merge() {
        StringBuilder sb = new StringBuilder();
        
        if (agentsMd != null && !agentsMd.isEmpty()) {
            sb.append("== 规则文件 ==\n").append(agentsMd).append("\n\n");
        }
        
        if (historyContext != null && !historyContext.isEmpty()) {
            sb.append(historyContext).append("\n\n");
        }
        
        if (notesContext != null && !notesContext.isEmpty()) {
            sb.append(notesContext).append("\n\n");
        }
        
        return sb.toString();
    }
}
```

#### 3.3.4 升级NoteAssistantService

```java
@Service
public class NoteAssistantService {
    
    private final ContextBuilder contextBuilder;
    
    public String chat(String sessionId, String userMessage, String mode, Long kbId, String modelName) {
        // 1. 构建完整上下文（主动搜索 + 历史对话 + 规则文件）
        ChatContext context = contextBuilder.build(sessionId, userMessage, kbId);
        
        // 2. 合并上下文
        String fullMessage = context.merge() + "\n\n---\n\n用户最新消息:\n" + userMessage;
        
        // 3. 调用AI
        String reply = client.prompt()
                .system(SYSTEM_PROMPT_V2)  // 升级后的系统提示词
                .user(fullMessage)
                .call()
                .content();
        
        return reply;
    }
}
```

#### 3.3.5 升级后的系统提示词

```java
private static final String SYSTEM_PROMPT_V2 = """
    你是一个智能笔记库助手，具备主动思考和检索能力。

    == 核心能力 ==
    1. 搜索笔记内容 - 使用 searchNotes 查找相关笔记
    2. 读取文件 - 使用 readFile 获取详细内容
    3. 写入文件 - 使用 writeFile 保存结果
    4. 列出目录 - 使用 listDir 探索结构

    == 工作流程 ==
    1. 理解用户意图
    2. 系统会自动搜索相关笔记（已在上下文中提供）
    3. 阅读上下文中的相关笔记内容
    4. 读取规则文件（readFile("AGENTS.md")）
    5. 综合分析，生成回复
    6. 必要时写入新笔记

    == 重要原则 ==
    - 系统已自动搜索相关笔记，请优先使用这些内容
    - 如果搜索结果不够，再使用 searchNotes 主动搜索
    - 引用笔记时标注来源路径
    - 不要假设路径，先搜索再读取

    == 输出规范 ==
    - 使用中文回复
    - 引用笔记时标注来源：[来源: 文件路径]
    - 复杂任务分步骤执行
    """;
```

---

## 四、前端页面设计

### 4.1 笔记索引管理页 `/kb/{id}/index`

```
┌─────────────────────────────────────────────────────┐
│  笔记索引管理                                        │
├─────────────────────────────────────────────────────┤
│  索引状态                                            │
│  ┌─────────────────────────────────────────────────┐│
│  │ 状态: ✅ 已索引                                  ││
│  │ 文件数: 1,234 个                                 ││
│  │ 片段数: 15,678 个                                ││
│  │ 最后更新: 2024-01-15 10:30                       ││
│  │ [🔄 重新索引] [📊 查看统计] [🗑️ 清空索引]        ││
│  └─────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────┤
│  搜索测试                                            │
│  ┌─────────────────────────────────────┐ [搜索]     │
│  │ 输入搜索内容...                      │             │
│  └─────────────────────────────────────┘             │
│                                                     │
│  搜索结果: (3 条)                                    │
│  ┌─────────────────────────────────────────────────┐│
│  │ 📄 工作/客户/ABC公司.md (相似度: 0.92)           ││
│  │ > 上周与张三沟通了产品需求，对方对新功能很感...    ││
│  │ [查看完整文件] [重新索引此文件]                    ││
│  └─────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────┐│
│  │ 📄 工作/日报/2024-01-10.md (相似度: 0.87)       ││
│  │ > 今日重点：跟进ABC公司签约进展...                ││
│  │ [查看完整文件] [重新索引此文件]                    ││
│  └─────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────┘
```

### 4.2 提示词模板管理页 `/kb/{id}/prompts`

```
┌─────────────────────────────────────────────────────┐
│  提示词模板管理                                      │
├─────────────────────────────────────────────────────┤
│  [全部] [系统] [工作流] [分析] [自定义]               │
├─────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────┐│
│  │ 📋 日报生成                                      ││
│  │ 分类: workflow | 变量: {date}, {weekday}        ││
│  │ 描述: 根据工作笔记生成每日综合日报               ││
│  │ [编辑] [复制] [删除] [设为默认]                  ││
│  └─────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────┐│
│  │ 📋 客户跟进分析                                  ││
│  │ 分类: analysis | 变量: {customer_name}          ││
│  │ 描述: 分析指定客户的跟进情况和历史沟通           ││
│  │ [编辑] [复制] [删除] [设为默认]                  ││
│  └─────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────┐│
│  │ 📋 笔记库助手                                   ││
│  │ 分类: system | 无变量                           ││
│  │ 描述: 通用的笔记库助手系统提示词                 ││
│  │ [编辑] [复制] [删除] [设为默认]                  ││
│  └─────────────────────────────────────────────────┘│
│                                                     │
│  [+ 新建模板] [📥 导入模板] [📤 导出模板]            │
└─────────────────────────────────────────────────────┘
```

### 4.3 对话页增强

在对话输入框下方增加工具栏：

```
┌─────────────────────────────────────────────────────┐
│  💬 输入消息...                                      │
├─────────────────────────────────────────────────────┤
│  📎 附件  |  🎯 模板  |  🔍 搜索  |  ⚙️ 设置        │
│                                                     │
│  当前模板: [日报生成 ▾]  搜索范围: [当前KB ▾]         │
│  ☑️ 自动搜索相关笔记  ☑️ 注入历史对话                 │
└─────────────────────────────────────────────────────┘
```

---

## 五、API设计

### 5.1 笔记索引API

```
POST   /kb/{id}/api/index/build              # 构建索引（全量）
POST   /kb/{id}/api/index/incremental         # 增量索引
GET    /kb/{id}/api/index/status              # 索引状态
POST   /kb/{id}/api/index/search              # 语义搜索
POST   /kb/{id}/api/index/hybrid-search       # 混合搜索
DELETE /kb/{id}/api/index                     # 清空索引
GET    /kb/{id}/api/index/stats               # 索引统计
```

### 5.2 提示词模板API

```
GET    /kb/{id}/api/prompts                   # 列出模板
POST   /kb/{id}/api/prompts                   # 创建模板
GET    /kb/{id}/api/prompts/{templateId}      # 获取模板
PUT    /kb/{id}/api/prompts/{templateId}      # 更新模板
DELETE /kb/{id}/api/prompts/{templateId}      # 删除模板
POST   /kb/{id}/api/prompts/compose           # 组合提示词
GET    /kb/{id}/api/prompts/recommend         # 推荐模板
POST   /kb/{id}/api/prompts/import            # 导入模板
GET    /kb/{id}/api/prompts/export            # 导出模板
```

### 5.3 搜索API

```
POST   /kb/{id}/api/notes/search             # 笔记内容搜索
GET    /kb/{id}/api/notes/search/suggest      # 搜索建议
```

---

## 六、实现优先级

| 阶段 | 功能 | 工作量 | 价值 |
|------|------|--------|------|
| **P0** | 笔记内容索引（NoteIndexService） | 2天 | 核心能力 |
| **P0** | searchNotes @Tool | 0.5天 | AI可用 |
| **P0** | ContextBuilder（主动搜索+注入） | 1天 | Agent核心 |
| **P1** | 提示词模板CRUD | 1天 | 基础设施 |
| **P1** | 提示词组合API | 1天 | 核心能力 |
| **P1** | 升级SYSTEM_PROMPT_V2 | 0.5天 | Agent行为 |
| **P2** | 前端索引管理页 | 1天 | 可视化 |
| **P2** | 前端模板管理页 | 1天 | 可视化 |
| **P2** | 对话页增强 | 0.5天 | 用户体验 |
| **P3** | 智能推荐模板 | 1天 | Agent能力 |
| **P3** | 自动索引（文件监听） | 1天 | 自动化 |
| **P3** | 搜索建议API | 0.5天 | 体验优化 |

**预计总工作量：12天**

---

## 七、技术要点

### 7.1 笔记分块策略

```java
private List<String> chunkContent(String content, int chunkSize, int overlap) {
    List<String> chunks = new ArrayList<>();
    int start = 0;
    
    while (start < content.length()) {
        int end = Math.min(start + chunkSize, content.length());
        chunks.add(content.substring(start, end));
        start = end - overlap;  // 重叠部分
    }
    
    return chunks;
}
```

**配置参数：**
- `chunkSize`: 300字（默认）
- `overlap`: 50字（默认）
- 优先按段落（`\n\n`）分割

### 7.2 索引增量更新

```java
public void indexFile(Path file, Long kbId) {
    String content = Files.readString(file);
    String contentHash = md5(content);
    
    // 检查是否已索引且内容未变
    NoteIndexEntity existing = getByPath(kbId, relativePath);
    if (existing != null && existing.getContentHash().equals(contentHash)) {
        return;  // 跳过，内容未变
    }
    
    // 删除旧索引
    if (existing != null) {
        deleteByPath(kbId, relativePath);
    }
    
    // 重新分块并索引
    List<String> chunks = chunkContent(content, 300, 50);
    for (int i = 0; i < chunks.size(); i++) {
        float[] embedding = embeddingService.embed(chunks.get(i));
        save(new NoteEmbedding(kbId, relativePath, i, chunks.get(i), embedding, contentHash));
    }
}
```

### 7.3 混合搜索算法

```java
public List<NoteSearchResult> hybridSearch(Long kbId, String query, int limit) {
    // 1. 语义搜索（权重0.7）
    List<NoteSearchResult> semanticResults = search(kbId, query, limit * 2);
    
    // 2. 关键词搜索（权重0.3）
    List<NoteSearchResult> keywordResults = keywordSearch(kbId, query, limit * 2);
    
    // 3. 合并并加权排序
    Map<String, Double> scores = new HashMap<>();
    
    for (NoteSearchResult r : semanticResults) {
        scores.merge(r.filePath() + ":" + r.chunkIndex(), 
                     r.score() * 0.7, Double::sum);
    }
    
    for (NoteSearchResult r : keywordResults) {
        scores.merge(r.filePath() + ":" + r.chunkIndex(), 
                     r.score() * 0.3, Double::sum);
    }
    
    // 4. 返回Top-N
    return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(e -> getResultByKey(e.getKey()))
            .collect(Collectors.toList());
}
```

### 7.4 提示词变量替换

```java
public String resolveVariables(String template, Map<String, String> variables) {
    String result = template;
    
    // 内置变量
    result = result.replace("{date}", TimeUtil.todayStr());
    result = result.replace("{weekday}", TimeUtil.weekdayCn(TimeUtil.now()));
    result = result.replace("{time}", TimeUtil.nowStr());
    
    // 用户自定义变量
    for (Map.Entry<String, String> entry : variables.entrySet()) {
        result = result.replace("{" + entry.getKey() + "}", entry.getValue());
    }
    
    return result;
}
```

---

## 八、数据迁移

### 8.1 新增表

```sql
-- 执行顺序
1. note_embeddings     -- 笔记索引
2. prompt_templates    -- 提示词模板
```

### 8.2 预置数据

```sql
-- 预置提示词模板
INSERT INTO prompt_templates (kb_id, name, category, content, variables) VALUES
(NULL, '日报生成', 'workflow', '现在是{date} {weekday}。请根据我的工作笔记生成今天的综合日报...', '{"date":"当前日期","weekday":"星期"}'),
(NULL, '客户跟进分析', 'analysis', '请分析客户"{customer_name}"的跟进情况...', '{"customer_name":"客户名称"}'),
(NULL, '笔记库助手', 'system', '你是一个智能笔记库助手，具备主动思考和检索能力...', '{}');
```

---

## 九、测试用例

### 9.1 笔记索引测试

```
输入: 为"工作"知识库构建索引
预期: 
1. 扫描所有.md和.json文件
2. 分块并生成向量
3. 写入note_embeddings表
4. 返回统计信息：文件数、片段数、耗时
```

### 9.2 语义搜索测试

```
输入: searchNotes("张三的跟进记录", 5)
预期:
1. 将查询转为向量
2. 检索相似度>0.5的笔记片段
3. 返回格式化的搜索结果
4. 结果包含文件路径、内容摘要、相似度分数
```

### 9.3 主动搜索注入测试

```
输入: "帮我总结一下本周的工作"
预期:
1. 系统自动搜索"本周工作"相关笔记
2. 搜索结果注入到上下文
3. AI基于搜索结果生成总结
4. 回复中引用笔记来源
```

### 9.4 提示词组合测试

```
输入: 使用"日报生成"模板
预期:
1. 加载模板内容
2. 替换变量 {date} → "2024-01-15", {weekday} → "星期一"
3. 返回最终提示词
4. 包含搜索建议和读取文件建议
```

---

## 十、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 索引耗时长 | 首次索引可能需要几分钟 | 增量索引 + 后台任务 + 进度显示 |
| 向量存储膨胀 | 大量笔记占用存储 | 分块策略优化 + 定期清理 |
| 搜索准确度低 | 返回不相关结果 | 混合搜索 + 阈值过滤 + 结果排序 |
| 提示词冲突 | 多模板组合时逻辑混乱 | 模板优先级 + 冲突检测 |

---

## 十一、后续演进

### v2.1（可选）

- **自动索引**：文件变更时自动触发索引更新
- **多模态索引**：图片描述的向量索引
- **跨KB搜索**：在多个知识库中联合搜索
- **搜索历史**：记录用户搜索行为，优化推荐

### v2.2（可选）

- **提示词市场**：用户分享和下载模板
- **Agent工作流**：可视化编排复杂任务
- **知识图谱**：笔记间的关联关系

---

## 十二、总结

本升级方案将笔灵AI从"工具型AI"升级为"Agent型AI"：

| 维度 | 升级前 | 升级后 |
|------|--------|--------|
| 搜索能力 | 只搜文件名 | 语义搜索内容 |
| 上下文构建 | 用户说搜才搜 | 系统主动搜索+自动注入 |
| 提示词管理 | 硬编码 | 模板库+场景化组合 |
| AI行为 | 被动响应 | 主动思考+执行 |
| 用户体验 | 手动操作 | 智能推荐+自动完成 |

**核心价值**：更主动、更确定性、更智能的AI助手体验。
