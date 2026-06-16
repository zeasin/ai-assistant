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
import dev.langchain4j.data.embedding.Embedding;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final float COSINE_THRESHOLD = 0.3f;
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
        migrateFromOldTables();
        dropOldTables();
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
                    embedding   BLOB NOT NULL,
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_turn_embeddings_session ON turn_embeddings(session_id)");
            log.info("New tables (sessions, messages, turn_embeddings) initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create new tables", e);
        }
    }

    private void migrateFromOldTables() {
        long newCount = sessionDbService.count();
        if (newCount > 0) {
            log.debug("New tables already have {} sessions, skipping migration", newCount);
            return;
        }

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            boolean hasChatSessions = false;
            boolean hasFeishuSessions = false;
            try {
                stmt.execute("SELECT COUNT(*) FROM chat_sessions");
                hasChatSessions = true;
            } catch (SQLException e) {
                log.debug("Old chat_sessions table does not exist, skipping web migration");
            }
            try {
                stmt.execute("SELECT COUNT(*) FROM feishu_sessions");
                hasFeishuSessions = true;
            } catch (SQLException e) {
                log.debug("Old feishu_sessions table does not exist, skipping feishu migration");
            }

            if (!hasChatSessions && !hasFeishuSessions) {
                return;
            }

            log.info("Migrating data from old tables to new tables...");

            if (hasChatSessions) {
                stmt.execute("""
                    INSERT INTO sessions (id, source, title, mode, created_at, updated_at)
                    SELECT id, 'web', title, mode, created_at, updated_at FROM chat_sessions
                    """);
                stmt.execute("""
                    INSERT INTO messages (session_id, source, role, content, mode, created_at)
                    SELECT session_id, 'web', role, content, mode, created_at FROM chat_messages
                    """);
                log.info("Migrated web sessions and messages");
            }

            if (hasFeishuSessions) {
                stmt.execute("""
                    INSERT INTO sessions (id, source, title, chat_id, chat_type,
                                          open_code_session_id, open_code_code_session_id,
                                          mode, created_at, updated_at)
                    SELECT user_key, 'feishu', '', chat_id, chat_type,
                           open_code_session_id, open_code_code_session_id,
                           'knowledge', created, updated FROM feishu_sessions
                    """);
                stmt.execute("""
                    INSERT INTO messages (session_id, source, role, content, mode, created_at)
                    SELECT user_key, 'feishu', role, content, COALESCE(mode, 'knowledge'), created_at FROM feishu_messages
                    """);
                log.info("Migrated feishu sessions and messages");
            }

            log.info("Migration from old tables complete");
        } catch (SQLException e) {
            log.warn("Migration from old tables failed: {}", e.getMessage());
        }
    }

    private void dropOldTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS feishu_messages");
            stmt.execute("DROP TABLE IF EXISTS feishu_sessions");
            stmt.execute("DROP TABLE IF EXISTS chat_messages");
            stmt.execute("DROP TABLE IF EXISTS chat_sessions");
            log.info("Old tables (chat_*, feishu_*) dropped");
        } catch (SQLException e) {
            log.warn("Failed to drop old tables: {}", e.getMessage());
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

            Embedding embedding = ollamaEmbeddingService.embed(turnText).orElse(null);
            if (embedding == null) return;

            byte[] blob = floatArrayToByteArray(embedding.vector());

            TurnEmbeddingEntity te = new TurnEmbeddingEntity();
            te.setSessionId(sessionId);
            te.setTurnOrder(nextTurn);
            te.setEmbedding(blob);
            te.setCreatedAt(TimeUtil.nowStr());
            turnEmbeddingDbService.save(te);
        } catch (Exception e) {
            log.warn("Failed to generate turn embedding for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ========== History Context (semantic retrieval) ==========

    public static class HistoryTurn {
        public int turnOrder;
        public float score;
        public List<MessageEntity> messages = new ArrayList<>();
    }

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
                || !ollamaEmbeddingService.isAvailable()
                || filtered.size() <= DEFAULT_FALLBACK_TURNS * 2) {
            return buildSimpleContext(filtered);
        }

        List<TurnEmbeddingEntity> embeddings = turnEmbeddingDbService.listBySession(sessionId);
        if (embeddings.isEmpty()) {
            return buildSimpleContext(filtered);
        }

        Embedding queryEmb = ollamaEmbeddingService.embed(currentQuery).orElse(null);
        if (queryEmb == null) {
            return buildSimpleContext(filtered);
        }

        List<HistoryTurn> scoredTurns = new ArrayList<>();
        for (TurnEmbeddingEntity te : embeddings) {
            float[] vec = byteArrayToFloatArray(te.getEmbedding());
            float score = cosineSimilarity(queryEmb.vector(), vec);
            scoredTurns.add(new HistoryTurn() {{
                turnOrder = te.getTurnOrder();
                score = score;
            }});
        }

        List<Integer> selectedOrders = scoredTurns.stream()
                .filter(t -> t.score > COSINE_THRESHOLD)
                .map(t -> t.turnOrder)
                .sorted()
                .collect(Collectors.toList());

        if (selectedOrders.isEmpty()) {
            return buildRecentContext(filtered);
        }

        return buildTurnContext(filtered, selectedOrders);
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

    private String buildTurnContext(List<MessageEntity> allMsgs, List<Integer> selectedOrders) {
        List<String> turnRanges = new ArrayList<>();
        int msgIdx = 0;
        int turnIdx = 0;
        while (msgIdx < allMsgs.size() && turnIdx < selectedOrders.size()) {
            int targetTurn = selectedOrders.get(turnIdx);
            for (int t = 0; t <= targetTurn && msgIdx + 1 < allMsgs.size(); t++) {
                if (msgIdx + 1 < allMsgs.size() && t == targetTurn) {
                    turnRanges.add(allMsgs.get(msgIdx).getContent());
                    if (msgIdx + 1 < allMsgs.size()) {
                        turnRanges.add(allMsgs.get(msgIdx + 1).getContent());
                    }
                }
                msgIdx += 2;
            }
            turnIdx++;
        }

        if (turnRanges.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是相关的对话历史，供参考：\n\n");
        boolean isUser = true;
        for (String content : turnRanges) {
            String label = isUser ? "用户" : "AI";
            sb.append(label).append(": ").append(content).append("\n\n");
            isUser = !isUser;
        }
        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    // ========== Embedding utilities ==========

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
