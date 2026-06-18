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
    private static final int MAX_TURNS_PER_SESSION = 4;
    private static final int MAX_GLOBAL_TURNS = 5;
    private static final int DEFAULT_FALLBACK_TURNS = 3;
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
                    id                        TEXT PRIMARY KEY,
                    source                    TEXT NOT NULL DEFAULT 'web',
                    title                     TEXT NOT NULL DEFAULT '新对话',
                    chat_id                   TEXT NOT NULL DEFAULT '',
                    chat_type                 TEXT NOT NULL DEFAULT '',
                    open_code_session_id      TEXT,
                    open_code_code_session_id TEXT,
                    mode                      TEXT NOT NULL DEFAULT 'knowledge',
                    created_at                TEXT NOT NULL,
                    updated_at                TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    source      TEXT NOT NULL DEFAULT 'web',
                    role        TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    mode        TEXT NOT NULL DEFAULT 'knowledge',
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at)");
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
                CREATE TABLE IF NOT EXISTS modules (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    module_id   TEXT NOT NULL UNIQUE,
                    name        TEXT NOT NULL,
                    dir         TEXT NOT NULL,
                    icon        TEXT NOT NULL DEFAULT '📦',
                    prompt      TEXT NOT NULL DEFAULT '',
                    data_files  TEXT NOT NULL DEFAULT '[]',
                    sort_order  INTEGER NOT NULL DEFAULT 0
                )
                """);
            log.info("Table modules initialized");
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

        if (currentQuery == null || currentQuery.isBlank()
                || !ollamaEmbeddingService.isAvailable()) {
            String ctx = buildSimpleContext(filtered);
            log.info("[ctx] 无 query 或 Ollama 不可用，使用全部历史（{} 条消息）", filtered.size());
            return ctx;
        }

        float[] queryEmb = ollamaEmbeddingService.embed(currentQuery);
        if (queryEmb == null) {
            String ctx = buildSimpleContext(filtered);
            log.info("[ctx] 嵌入失败，回退全部历史（{} 条消息）", filtered.size());
            return ctx;
        }

        String globalContext = searchGlobalContext(queryEmb, sessionId, currentQuery);
        if (globalContext != null) {
            log.info("[ctx] 命中跨会话语义检索");
            return globalContext;
        }

        if (filtered.size() <= DEFAULT_FALLBACK_TURNS * 2) {
            String ctx = buildSimpleContext(filtered);
            log.info("[ctx] 消息少（{}），使用全部历史", filtered.size());
            return ctx;
        }
        String ctx = buildRecentContext(filtered);
        log.info("[ctx] 无语义命中，回退最近 {} 轮", DEFAULT_FALLBACK_TURNS);
        return ctx;
    }

    private String searchGlobalContext(float[] queryEmb, String currentSessionId, String query) {
        List<TurnEmbeddingEntity> allEmbeddings = turnEmbeddingDbService.list();
        if (allEmbeddings.isEmpty()) return null;

        List<ScoredTurn> scored = new ArrayList<>();
        for (TurnEmbeddingEntity te : allEmbeddings) {
            float[] vec = byteArrayToFloatArray(Base64.getDecoder().decode(te.getEmbedding()));
            float score = cosineSimilarity(queryEmb, vec);
            scored.add(new ScoredTurn(te.getSessionId(), te.getTurnOrder(), score));
        }

        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Float.compare(b.score, a.score));

        Map<String, Integer> perSessionCount = new java.util.HashMap<>();
        List<ScoredTurn> matches = new ArrayList<>();
        for (ScoredTurn t : scored) {
            if (t.score <= COSINE_THRESHOLD) break;
            int count = perSessionCount.getOrDefault(t.sessionId, 0);
            if (count >= MAX_TURNS_PER_SESSION) continue;
            perSessionCount.put(t.sessionId, count + 1);
            matches.add(t);
            if (matches.size() >= MAX_GLOBAL_TURNS) break;
        }

        log.info("语义检索: query=\"{}\", 全库 {} 条向量(排除当前会话), 命中 {} 轮", query, allEmbeddings.size(), matches.size());
        for (ScoredTurn t : matches) {
            log.info("  session={} turn={} score={}", t.sessionId, t.turnOrder, String.format("%.2f", t.score));
        }

        if (matches.isEmpty()) return null;

        return buildGlobalContext(matches, currentSessionId);
    }

    private String buildGlobalContext(List<ScoredTurn> matches, String currentSessionId) {
        Map<String, List<ScoredTurn>> bySession = matches.stream()
                .collect(Collectors.groupingBy(t -> t.sessionId));

        StringBuilder sb = new StringBuilder();
        sb.append("以下是相关历史对话（长期记忆）：\n\n");

        for (Map.Entry<String, List<ScoredTurn>> entry : bySession.entrySet()) {
            String sid = entry.getKey();
            List<MessageEntity> msgs = messageDbService.listBySession(sid);
            Set<Integer> turnOrders = entry.getValue().stream()
                    .map(t -> t.turnOrder).collect(Collectors.toSet());

            SessionEntity session = sessionDbService.getById(sid);
            String header;
            if (sid.equals(currentSessionId)) {
                header = "当前对话";
            } else if (session != null && session.getTitle() != null
                    && !session.getTitle().isEmpty() && !"新对话".equals(session.getTitle())) {
                header = "历史对话 - " + session.getTitle();
            } else {
                header = "其他历史对话";
            }
            sb.append("【").append(header).append("】\n\n");

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
        }

        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    private String buildSimpleContext(List<MessageEntity> filtered) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话历史，供参考：\n\n");
        for (MessageEntity msg : filtered) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(label).append(": ").append(msg.getContent()).append("\n\n");
        }
        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    private String buildRecentContext(List<MessageEntity> filtered) {
        int msgCount = DEFAULT_FALLBACK_TURNS * 2;
        List<MessageEntity> recent = filtered.size() > msgCount
                ? filtered.subList(filtered.size() - msgCount, filtered.size())
                : filtered;

        StringBuilder sb = new StringBuilder();
        sb.append("以下是最近的对话历史，供参考：\n\n");
        for (MessageEntity msg : recent) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(label).append(": ").append(msg.getContent()).append("\n\n");
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
        buf.order(ByteOrder.nativeOrder());
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    public static float[] byteArrayToFloatArray(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.nativeOrder());
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = buf.getFloat();
        }
        return result;
    }
}
