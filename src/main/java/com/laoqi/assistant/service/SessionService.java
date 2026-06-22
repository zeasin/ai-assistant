package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.entity.TurnEmbeddingEntity;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.db.SessionDbService;
import com.laoqi.assistant.service.db.TurnEmbeddingDbService;
import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final float COSINE_THRESHOLD = 0.5f;
    private static final int MAX_GLOBAL_TURNS = 5;
    private static final int DEFAULT_FALLBACK_TURNS = 3;
    private static final int MAX_HISTORY_CHARS = 5000;
    private static final int MAX_SINGLE_MSG_CHARS = 1000;
    private static final int EMBEDDING_DIM = 768;

    private final DataSource dataSource;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;
    private final TurnEmbeddingDbService turnEmbeddingDbService;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public SessionService(DataSource dataSource,
                          SessionDbService sessionDbService,
                          MessageDbService messageDbService,
                          TurnEmbeddingDbService turnEmbeddingDbService,
                          OllamaEmbeddingService ollamaEmbeddingService) {
        this.dataSource = dataSource;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
        this.turnEmbeddingDbService = turnEmbeddingDbService;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
    }

    // ========== DDL ==========

    @PostConstruct
    public void init() {
        createTables();
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id         TEXT PRIMARY KEY,
                    source     TEXT NOT NULL DEFAULT 'web',
                    title      TEXT NOT NULL DEFAULT '新对话',
                    chat_id    TEXT NOT NULL DEFAULT '',
                    chat_type  TEXT NOT NULL DEFAULT '',
                    mode       TEXT NOT NULL DEFAULT 'knowledge',
                    kb_id      INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
            // 迁移：为 sessions 补充 kb_id 列（兼容旧数据库）
            try { stmt.execute("ALTER TABLE sessions ADD COLUMN kb_id INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) {}
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_kb ON sessions(kb_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    source      TEXT NOT NULL DEFAULT 'web',
                    role        TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    mode        TEXT NOT NULL DEFAULT 'knowledge',
                    kb_id       INTEGER,
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at)");
            // 迁移：为 messages 补充 kb_id 列（兼容旧数据库）
            try { stmt.execute("ALTER TABLE messages ADD COLUMN kb_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.execute("UPDATE messages SET kb_id = (SELECT kb_id FROM sessions WHERE sessions.id = messages.session_id) WHERE kb_id IS NULL"); } catch (Exception ignored) {}
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_kb ON messages(kb_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS turn_embeddings (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    turn_order  INTEGER NOT NULL,
                    embedding   TEXT NOT NULL,
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_turn_embeddings_session ON turn_embeddings(session_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS llm_profiles (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    name            TEXT NOT NULL UNIQUE,
                    api_key         TEXT NOT NULL DEFAULT '',
                    base_url        TEXT NOT NULL DEFAULT 'https://api.deepseek.com',
                    model           TEXT NOT NULL DEFAULT 'deepseek-chat',
                    timeout         INTEGER NOT NULL DEFAULT 600,
                    is_default      INTEGER NOT NULL DEFAULT 0,
                    vision_support  INTEGER NOT NULL DEFAULT 0,
                    model_type      TEXT NOT NULL DEFAULT 'text'
                )
                """);
            // 迁移：为旧数据填充 model_type
            try {
                stmt.execute("ALTER TABLE llm_profiles ADD COLUMN model_type TEXT NOT NULL DEFAULT 'text'");
            } catch (Exception ignored) {
                // column already exists
            }
            stmt.execute("UPDATE llm_profiles SET model_type = 'multimodal' WHERE vision_support = 1 AND model_type = 'text'");
            stmt.execute("UPDATE llm_profiles SET model_type = 'text' WHERE vision_support = 0 AND model_type = 'text'");
            log.info("New tables (sessions, messages, turn_embeddings, llm_profiles) initialized");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS coding_records (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    time         TEXT NOT NULL,
                    start_time   TEXT NOT NULL DEFAULT '',
                    end_time     TEXT NOT NULL DEFAULT '',
                    duration     INTEGER NOT NULL DEFAULT 0,
                    ai_engine    TEXT NOT NULL DEFAULT 'pi',
                    message      TEXT NOT NULL,
                    response     TEXT NOT NULL DEFAULT '',
                    elapsed      TEXT NOT NULL DEFAULT '',
                    success      INTEGER NOT NULL DEFAULT 0,
                    source       TEXT NOT NULL DEFAULT 'feishu',
                    project_dir  TEXT NOT NULL DEFAULT ''
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_coding_records_time ON coding_records(id DESC)");
            log.info("Table coding_records initialized");
            // 迁移：补充新字段（兼容旧数据库）
            try { stmt.execute("ALTER TABLE coding_records ADD COLUMN start_time TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE coding_records ADD COLUMN end_time TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE coding_records ADD COLUMN duration INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE coding_records ADD COLUMN ai_engine TEXT NOT NULL DEFAULT 'pi'"); } catch (Exception ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT NOT NULL,
                    notes_dir    TEXT NOT NULL,
                    labels       TEXT NOT NULL DEFAULT '{}',
                    sort_order   INTEGER NOT NULL DEFAULT 0,
                    created_at   TEXT NOT NULL,
                    dir_settings TEXT NOT NULL DEFAULT '',
                    ignore_dirs  TEXT NOT NULL DEFAULT '',
                    ignore_files TEXT NOT NULL DEFAULT ''
                )
                """);
            try { stmt.execute("ALTER TABLE knowledge_bases ADD COLUMN dir_settings TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE knowledge_bases ADD COLUMN ignore_dirs TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE knowledge_bases ADD COLUMN ignore_files TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            log.info("Table knowledge_bases initialized");

            // 迁移：从 config.json 迁移第一条知识库

            // 识图分析记录表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS image_analyses (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    image_name   TEXT NOT NULL,
                    image_path   TEXT DEFAULT '',
                    image_type   TEXT NOT NULL,
                    prompt       TEXT NOT NULL,
                    result       TEXT DEFAULT '',
                    model        TEXT DEFAULT '',
                    source       TEXT NOT NULL DEFAULT 'upload',
                    kb_id        INTEGER DEFAULT NULL,
                    status       TEXT NOT NULL DEFAULT 'pending',
                    created_at   TEXT NOT NULL,
                    completed_at TEXT DEFAULT ''
                )
                """);
            log.info("Table image_analyses initialized");

            // 任务表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id          TEXT PRIMARY KEY,
                    title       TEXT NOT NULL,
                    description TEXT DEFAULT '',
                    status      TEXT NOT NULL DEFAULT 'pending',
                    priority    TEXT NOT NULL DEFAULT 'mid',
                    due_date    TEXT DEFAULT '',
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL,
                    kb_id       INTEGER DEFAULT NULL
                )
                """);
            log.info("Table tasks initialized");

            // 提醒表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reminders (
                    id             TEXT PRIMARY KEY,
                    name           TEXT NOT NULL,
                    message        TEXT DEFAULT '',
                    type           TEXT NOT NULL,
                    time           TEXT DEFAULT '09:00',
                    date           TEXT DEFAULT '',
                    day_of_week    INTEGER DEFAULT 0,
                    day_of_month   INTEGER DEFAULT 1,
                    month_day      TEXT DEFAULT '',
                    enabled        INTEGER DEFAULT 1,
                    created_at     TEXT NOT NULL,
                    last_triggered TEXT DEFAULT '',
                    kb_id          INTEGER DEFAULT NULL
                )
                """);
            log.info("Table reminders initialized");

            // 试卷识别表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS exam_papers (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL DEFAULT '',
                    image_path  TEXT NOT NULL DEFAULT '',
                    image_type  TEXT NOT NULL DEFAULT 'image/jpeg',
                    kb_id       INTEGER,
                    model       TEXT NOT NULL DEFAULT '',
                    status      TEXT NOT NULL DEFAULT 'pending',
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS exam_questions (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    paper_id        INTEGER NOT NULL,
                    seq_num         INTEGER NOT NULL DEFAULT 0,
                    question_type   TEXT NOT NULL DEFAULT '未知',
                    content         TEXT NOT NULL DEFAULT '',
                    options         TEXT NOT NULL DEFAULT '',
                    answer          TEXT NOT NULL DEFAULT '',
                    explanation     TEXT NOT NULL DEFAULT '',
                    knowledge_tags  TEXT NOT NULL DEFAULT '',
                    difficulty      INTEGER NOT NULL DEFAULT 0,
                    created_at      TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exam_questions_paper ON exam_questions(paper_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS exam_practices (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    question_id INTEGER NOT NULL,
                    user_answer TEXT NOT NULL DEFAULT '',
                    is_correct  INTEGER NOT NULL DEFAULT 0,
                    used_time   INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exam_practices_question ON exam_practices(question_id)");
            log.info("Table exam_papers, exam_questions, exam_practices initialized");

            // 识题记录表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS solve_sessions (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    title       TEXT NOT NULL DEFAULT '新识题',
                    image_name  TEXT NOT NULL DEFAULT '',
                    image_path  TEXT NOT NULL DEFAULT '',
                    image_type  TEXT NOT NULL DEFAULT 'image/jpeg',
                    image_data  BLOB,
                    model       TEXT NOT NULL DEFAULT '',
                    prompt      TEXT NOT NULL DEFAULT '',
                    answer      TEXT NOT NULL DEFAULT '',
                    status      TEXT NOT NULL DEFAULT 'pending',
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS solve_follow_ups (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  INTEGER NOT NULL,
                    question    TEXT NOT NULL DEFAULT '',
                    answer      TEXT NOT NULL DEFAULT '',
                    sort_order  INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_solve_follow_ups_session ON solve_follow_ups(session_id)");
            log.info("Table solve_sessions, solve_follow_ups initialized");
            try { stmt.execute("ALTER TABLE solve_sessions ADD COLUMN image_type TEXT NOT NULL DEFAULT 'image/jpeg'"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE solve_sessions ADD COLUMN image_data BLOB"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE solve_sessions ADD COLUMN prompt TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}

            // 笔记内容索引表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS note_embeddings (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    kb_id        INTEGER NOT NULL,
                    file_path    TEXT NOT NULL,
                    chunk_index  INTEGER NOT NULL DEFAULT 0,
                    path_context TEXT NOT NULL DEFAULT '',
                    content      TEXT NOT NULL,
                    title      TEXT NOT NULL,
                    embedding    TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL,
                    UNIQUE(kb_id, file_path, chunk_index)
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_note_embeddings_kb ON note_embeddings(kb_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_note_embeddings_path ON note_embeddings(kb_id, file_path)");
            try { stmt.execute("ALTER TABLE note_embeddings ADD COLUMN path_context TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE note_embeddings ADD COLUMN title TEXT"); } catch (Exception ignored) {}
            log.info("Table note_embeddings initialized");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create new tables", e);
        }
    }



    // ========== Session CRUD ==========

    public SessionEntity getSession(String id) {
        return sessionDbService.getById(id);
    }

    public SessionEntity getOrCreateWebSession(String sessionId) {
        SessionEntity se = sessionDbService.getById(sessionId);
        if (se != null) return se;

        String now = TimeUtil.nowStr();
        se = new SessionEntity();
        se.setId(sessionId);
        se.setSource("web");
        se.setTitle("新对话");
        se.setMode("knowledge");
        se.setCreatedAt(now);
        se.setUpdatedAt(now);
        sessionDbService.save(se);
        return se;
    }

    public SessionEntity getOrCreateFeishuSession(String userKey, String chatId, String chatType) {
        SessionEntity se = sessionDbService.getById(userKey);
        if (se != null) return se;

        String now = TimeUtil.nowStr();
        se = new SessionEntity();
        se.setId(userKey);
        se.setSource("feishu");
        se.setTitle("");
        se.setChatId(chatId);
        se.setChatType(chatType);
        se.setMode("knowledge");
        se.setCreatedAt(now);
        se.setUpdatedAt(now);
        sessionDbService.save(se);
        return se;
    }

    public void clearAllEmbeddings() {
        turnEmbeddingDbService.getBaseMapper().delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TurnEmbeddingEntity>()
                        .isNotNull("id"));
        log.info("已清空所有向量数据（向量模型变更）");
    }

    public void deleteSession(String sessionId) {
        turnEmbeddingDbService.getBaseMapper().delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TurnEmbeddingEntity>()
                        .eq("session_id", sessionId));
        messageDbService.getBaseMapper().delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MessageEntity>()
                        .eq("session_id", sessionId));
        sessionDbService.removeById(sessionId);
    }

    // ========== Messages ==========

    public void saveMessage(String sessionId, String role, String content, String mode, String source) {
        String now = TimeUtil.nowStr();

        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setSource(source);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMode(mode != null ? mode : "knowledge");
        msg.setCreatedAt(now);
        // 填充 kb_id：从 session 中获取
        SessionEntity se = sessionDbService.getById(sessionId);
        if (se != null && se.getKbId() != null) {
            msg.setKbId(se.getKbId());
        }
        messageDbService.save(msg);

        markSessionUpdated(sessionId, role, mode);

        if ("assistant".equals(role)) {
            generateTurnEmbedding(sessionId);
        }
    }

    private void markSessionUpdated(String sessionId, String role, String mode) {
        SessionEntity se = sessionDbService.getById(sessionId);
        if (se == null) return;

        SessionEntity update = new SessionEntity();
        update.setId(sessionId);
        update.setUpdatedAt(TimeUtil.nowStr());
        if ("user".equals(role) && se.getTitle() != null && se.getTitle().equals("新对话")) {
            String title = contentPreview(sessionId);
            if (title != null) update.setTitle(title);
        }
        if (mode != null && !mode.isEmpty()) {
            update.setMode(mode);
        }
        sessionDbService.updateById(update);
    }

    private String contentPreview(String sessionId) {
        List<MessageEntity> msgs = messageDbService.listBySession(sessionId);
        for (MessageEntity m : msgs) {
            if ("user".equals(m.getRole())) {
                String t = m.getContent();
                return t.length() > 30 ? t.substring(0, 30) + "..." : t;
            }
        }
        return null;
    }

    private void generateTurnEmbedding(String sessionId) {
        if (!ollamaEmbeddingService.isAvailable()) return;

        try {
            MessageEntity lastAssistant = messageDbService.listRecentBySession(sessionId, 1).stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .findFirst().orElse(null);
            if (lastAssistant == null) return;

            List<MessageEntity> recentTwo = messageDbService.listRecentBySession(sessionId, 2);
            MessageEntity lastUser = recentTwo.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .findFirst().orElse(null);
            if (lastUser == null) return;

            String turnText = lastUser.getContent() + "\n" + lastAssistant.getContent();
            int nextTurn = turnEmbeddingDbService.maxTurnOrder(sessionId) + 1;

            float[] vector = ollamaEmbeddingService.embed(turnText);
            if (vector == null) return;

            byte[] blob = floatArrayToByteArray(vector);

            TurnEmbeddingEntity te = new TurnEmbeddingEntity();
            te.setSessionId(sessionId);
            te.setTurnOrder(nextTurn);
            te.setEmbedding(Base64.getEncoder().encodeToString(blob));
            te.setCreatedAt(TimeUtil.nowStr());
            turnEmbeddingDbService.save(te);
        } catch (Exception e) {
            log.warn("Failed to generate turn embedding for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ========== History Context (semantic retrieval / long-term memory) ==========

    public String buildHistoryContext(String sessionId, String mode) {
        return buildHistoryContext(sessionId, mode, null);
    }

    public String buildHistoryContext(String sessionId, String mode, String currentQuery) {
        List<MessageEntity> allMsgs = messageDbService.listBySession(sessionId);
        if (allMsgs.isEmpty()) return null;

        List<MessageEntity> filtered;
        if (mode != null && !mode.isEmpty()) {
            filtered = allMsgs.stream()
                    .filter(m -> mode.equals(m.getMode()))
                    .collect(Collectors.toList());
        } else {
            filtered = new ArrayList<>(allMsgs);
        }
        if (filtered.isEmpty()) return null;

        // 1. 始终包含最近 N 轮
        String recentCtx = buildRecentContext(filtered);

        // 2. 如果 Ollama 可用，叠加语义搜索到的历史轮次
        String semanticCtx = null;
        int totalTurns = filtered.size() / 2;
        if (currentQuery != null && !currentQuery.isBlank()
                && ollamaEmbeddingService.isAvailable() && totalTurns > DEFAULT_FALLBACK_TURNS) {
            float[] queryEmb = ollamaEmbeddingService.embed(currentQuery);
            if (queryEmb != null) {
                semanticCtx = searchGlobalContext(queryEmb, sessionId, totalTurns);
            }
        }

        // 3. 合并：近期聊天 + 语义搜索结果
        if (semanticCtx != null) {
            log.info("[ctx] 近期 {} 轮 + 语义命中历史，合并注入", DEFAULT_FALLBACK_TURNS);
            return recentCtx.replace("继续回复用户的最新消息。",
                    "同时参考以下语义相关的历史对话：\n\n" + semanticCtx + "\n继续回复用户的最新消息。");
        }

        log.info("[ctx] 无语义命中，使用近期 {} 轮", DEFAULT_FALLBACK_TURNS);
        return recentCtx;
    }

    private String searchGlobalContext(float[] queryEmb, String sessionId, int totalTurns) {
        List<TurnEmbeddingEntity> embeddings = turnEmbeddingDbService.listBySession(sessionId);
        if (embeddings.isEmpty()) return null;

        List<ScoredTurn> scored = new ArrayList<>();
        for (TurnEmbeddingEntity te : embeddings) {
            float[] vec = byteArrayToFloatArray(Base64.getDecoder().decode(te.getEmbedding()));
            float score = cosineSimilarity(queryEmb, vec);
            scored.add(new ScoredTurn(te.getSessionId(), te.getTurnOrder(), score));
        }

        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Float.compare(b.score, a.score));

        // 排除最近 N 轮（已包含在近期上下文中），避免重复
        int recentTurnStart = totalTurns - DEFAULT_FALLBACK_TURNS;
        List<ScoredTurn> matches = new ArrayList<>();
        for (ScoredTurn t : scored) {
            if (t.score <= COSINE_THRESHOLD) break;
            if (t.turnOrder >= recentTurnStart) continue;
            if (matches.size() >= MAX_GLOBAL_TURNS) break;
            matches.add(t);
        }

        if (matches.isEmpty()) return null;

        log.info("语义检索: query=\"{}\", 已排除最近{}轮, 命中{}轮", 
                sessionId, DEFAULT_FALLBACK_TURNS, matches.size());

        return buildSemanticContext(matches, sessionId);
    }

    private String buildSemanticContext(List<ScoredTurn> matches, String sessionId) {
        List<MessageEntity> msgs = messageDbService.listBySession(sessionId);
        if (msgs.isEmpty()) return null;

        Set<Integer> turnOrders = matches.stream()
                .map(t -> t.turnOrder).collect(Collectors.toSet());

        StringBuilder sb = new StringBuilder();
        int msgIdx = 0;
        int turnIdx = 0;
        while (msgIdx + 1 < msgs.size()) {
            if (turnOrders.contains(turnIdx)) {
                sb.append("用户: ").append(msgs.get(msgIdx).getContent()).append("\n\n");
                sb.append("AI: ").append(msgs.get(msgIdx + 1).getContent()).append("\n\n");
            }
            msgIdx += 2;
            turnIdx++;
        }

        return sb.toString();
    }

    private String buildRecentContext(List<MessageEntity> filtered) {
        int msgCount = DEFAULT_FALLBACK_TURNS * 2;
        List<MessageEntity> recent = filtered.size() > msgCount
                ? filtered.subList(filtered.size() - msgCount, filtered.size())
                : filtered;

        // 如果最后一条是用户消息（通常就是当前用户刚发送的消息），从历史中排除
        // 因为 ContextBuilder.merge() 会把它作为"用户最新消息"另行注入，避免重复
        if (!recent.isEmpty() && "user".equals(recent.get(recent.size() - 1).getRole())) {
            recent = new ArrayList<>(recent.subList(0, recent.size() - 1));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是最近的对话历史（AI 回复仅保留摘要以节省上下文），供参考：\n\n");
        for (MessageEntity msg : recent) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            String content = msg.getContent();
            // 用户消息：完整保留（通常较短且反映用户意图）
            // AI 回复：仅保留开头摘要（长篇细节可通过重新搜索笔记重新获取）
            if ("AI".equals(label)) {
                int summaryLen = Math.min(content.length(), 200);
                if (content.length() > summaryLen) {
                    content = content.substring(0, summaryLen) + "...";
                }
            } else if (content.length() > MAX_SINGLE_MSG_CHARS) {
                content = content.substring(0, MAX_SINGLE_MSG_CHARS) + "\n...（已截断）";
            }
            sb.append(label).append(": ").append(content).append("\n\n");
            // 总体长度超限则停止追加，避免上下文爆炸
            if (sb.length() > MAX_HISTORY_CHARS) {
                sb.append("（历史对话过长，已截断后续内容）\n\n");
                break;
            }
        }
        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    // ========== Embedding utilities ==========

    public static class ScoredTurn {
        public String sessionId;
        public int turnOrder;
        public float score;

        public ScoredTurn(String sessionId, int turnOrder, float score) {
            this.sessionId = sessionId;
            this.turnOrder = turnOrder;
            this.score = score;
        }
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : (float) (dot / denom);
    }

    public static byte[] floatArrayToByteArray(float[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    public static float[] byteArrayToFloatArray(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = buf.getFloat();
        }
        return result;
    }
}
