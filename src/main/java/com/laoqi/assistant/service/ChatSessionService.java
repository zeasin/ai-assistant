package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.ChatMessageEntity;
import com.laoqi.assistant.entity.ChatSessionEntity;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.db.ChatMessageDbService;
import com.laoqi.assistant.service.db.ChatSessionDbService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final ChatSessionDbService chatSessionDbService;
    private final ChatMessageDbService chatMessageDbService;
    private final DataSource dataSource;

    public ChatSessionService(AppConfig appConfig, ConfigService configService,
                              ChatSessionDbService chatSessionDbService, ChatMessageDbService chatMessageDbService,
                              DataSource dataSource) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.chatSessionDbService = chatSessionDbService;
        this.chatMessageDbService = chatMessageDbService;
        this.dataSource = dataSource;
    }

    // ========== DDL ==========

    @PostConstruct
    public void init() {
        createTables();
        migrateFromJson();
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    id          TEXT PRIMARY KEY,
                    title       TEXT NOT NULL DEFAULT '新对话',
                    mode        TEXT NOT NULL DEFAULT 'knowledge',
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    role        TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    mode        TEXT NOT NULL DEFAULT 'knowledge',
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id, created_at)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS feishu_sessions (
                    user_key                 TEXT PRIMARY KEY,
                    chat_id                  TEXT NOT NULL DEFAULT '',
                    chat_type                TEXT NOT NULL DEFAULT 'p2p',
                    open_code_session_id     TEXT,
                    open_code_code_session_id TEXT,
                    created                  TEXT NOT NULL,
                    updated                  TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS feishu_messages (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_key    TEXT NOT NULL,
                    role        TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    mode        TEXT,
                    created_at  TEXT NOT NULL,
                    FOREIGN KEY (user_key) REFERENCES feishu_sessions(user_key) ON DELETE CASCADE
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_feishu_messages_user ON feishu_messages(user_key, created_at)");

            // ==== Memory 长期记忆 ====
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    content     TEXT NOT NULL,
                    source      TEXT NOT NULL DEFAULT 'user',
                    tags        TEXT DEFAULT '[]',
                    created_at  TEXT NOT NULL
                )
                """);

            // FTS5 full-text search (optional — may not be available in all SQLite builds)
            try {
                stmt.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                        content,
                        tokenize='unicode61',
                        content='memories',
                        content_rowid='id'
                    )
                    """);
                stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
                        INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content);
                    END
                    """);
                stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                    END
                    """);
                stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                        INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content);
                    END
                    """);
                log.info("FTS5 full-text search index created for memories");
            } catch (SQLException e) {
                log.warn("FTS5 not available (full-text search disabled): {}", e.getMessage());
            }

            log.info("Database tables initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create tables", e);
        }
    }

    // ========== 迁移旧数据 ==========

    private void migrateFromJson() {
        Path oldFile = getChatSessionsFile();
        if (!Files.exists(oldFile)) {
            log.debug("No old chat_sessions.json found, skipping migration");
            return;
        }

        long count = chatSessionDbService.count();
        if (count > 0) {
            log.info("SQLite already has {} sessions, skipping JSON migration", count);
            return;
        }

        log.info("Found chat_sessions.json, starting migration to SQLite...");
        SessionsData oldData = FileUtil.readJson(oldFile, SessionsData.class, new SessionsData());
        int sessionCount = 0;
        int msgCount = 0;

        for (ChatSession session : oldData.sessions) {
            ChatSessionEntity se = new ChatSessionEntity();
            se.setId(session.getId());
            se.setTitle(session.getTitle() != null ? session.getTitle() : "新对话");
            se.setMode(session.getMode() != null ? session.getMode() : "knowledge");
            se.setCreatedAt(session.getCreated() != null ? session.getCreated() : TimeUtil.nowStr());
            se.setUpdatedAt(session.getUpdated() != null ? session.getUpdated() : TimeUtil.nowStr());
            chatSessionDbService.save(se);
            sessionCount++;

            if (session.getMessages() != null) {
                for (ChatMessage msg : session.getMessages()) {
                    ChatMessageEntity me = new ChatMessageEntity();
                    me.setSessionId(session.getId());
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode() != null ? msg.getMode() : session.getMode());
                    me.setCreatedAt(msg.getTime() != null ? msg.getTime() : TimeUtil.nowStr());
                    chatMessageDbService.save(me);
                    msgCount++;
                }
            }
        }

        try {
            Files.move(oldFile, oldFile.resolveSibling("chat_sessions.json.bak"));
            log.info("Migration complete: {} sessions, {} messages. Old file renamed to .bak", sessionCount, msgCount);
        } catch (Exception e) {
            log.warn("Failed to rename old file: {}", e.getMessage());
        }
    }

    // ========== 公开 API ==========

    public static class SessionsData {
        public String current;
        public List<ChatSession> sessions = new ArrayList<>();
    }

    public SessionsData load() {
        SessionsData data = new SessionsData();
        List<ChatSessionEntity> entities = chatSessionDbService.listAllOrderByUpdate();
        for (ChatSessionEntity se : entities) {
            data.sessions.add(toModel(se));
        }
        if (!data.sessions.isEmpty()) {
            data.current = data.sessions.get(0).getId();
        }
        return data;
    }

    public void save(SessionsData data) {
        chatMessageDbService.remove(null);
        chatSessionDbService.remove(null);
        for (ChatSession session : data.sessions) {
            ChatSessionEntity se = new ChatSessionEntity();
            se.setId(session.getId());
            se.setTitle(session.getTitle());
            se.setMode(session.getMode());
            se.setCreatedAt(session.getCreated());
            se.setUpdatedAt(session.getUpdated());
            chatSessionDbService.save(se);

            if (session.getMessages() != null) {
                for (ChatMessage msg : session.getMessages()) {
                    ChatMessageEntity me = new ChatMessageEntity();
                    me.setSessionId(session.getId());
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime());
                    chatMessageDbService.save(me);
                }
            }
        }
    }

    public boolean saveMessage(String sessionId, String role, String content, String mode) {
        String now = TimeUtil.nowStr();

        ChatSessionEntity session = chatSessionDbService.getById(sessionId);
        if (session == null) return false;

        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMode(mode != null ? mode : "knowledge");
        msg.setCreatedAt(now);
        chatMessageDbService.save(msg);

        List<ChatMessageEntity> allMsgs = chatMessageDbService.listBySession(sessionId);
        String title = deriveTitle(allMsgs);

        ChatSessionEntity update = new ChatSessionEntity();
        update.setId(sessionId);
        update.setTitle(title);
        update.setUpdatedAt(now);
        if (mode != null && !mode.isEmpty()) {
            update.setMode(mode);
        }
        chatSessionDbService.updateById(update);

        return true;
    }

    public void deleteSession(String sessionId) {
        chatMessageDbService.remove(new QueryWrapper<ChatMessageEntity>().eq("session_id", sessionId));
        chatSessionDbService.removeById(sessionId);
    }

    public ChatSession getSession(String sessionId) {
        ChatSessionEntity se = chatSessionDbService.getById(sessionId);
        if (se == null) return null;
        return toModel(se);
    }

    public String buildHistoryContext(String sessionId, String mode) {
        List<ChatMessageEntity> entities = chatMessageDbService.listBySession(sessionId);
        if (entities.isEmpty()) return null;

        List<ChatMessageEntity> filtered;
        if (mode != null && !mode.isEmpty()) {
            filtered = entities.stream()
                    .filter(m -> mode.equals(m.getMode()))
                    .collect(Collectors.toList());
        } else {
            filtered = new ArrayList<>(entities);
        }
        if (filtered.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话历史，供参考：\n\n");
        for (ChatMessageEntity msg : filtered) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(label).append(": ").append(msg.getContent()).append("\n\n");
        }
        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    // ========== 转换方法 ==========

    private ChatSession toModel(ChatSessionEntity se) {
        ChatSession session = new ChatSession();
        session.setId(se.getId());
        session.setTitle(se.getTitle());
        session.setMode(se.getMode());
        session.setCreated(se.getCreatedAt());
        session.setUpdated(se.getUpdatedAt());

        List<ChatMessageEntity> msgEntities = chatMessageDbService.listBySession(se.getId());
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageEntity me : msgEntities) {
            messages.add(new ChatMessage(me.getRole(), me.getContent(), me.getCreatedAt(), me.getMode()));
        }
        session.setMessages(messages);
        return session;
    }

    private String deriveTitle(List<ChatMessageEntity> messages) {
        for (ChatMessageEntity m : messages) {
            if ("user".equals(m.getRole())) {
                String t = m.getContent();
                if (t.length() > 30) t = t.substring(0, 30) + "...";
                return t;
            }
        }
        return "新对话";
    }

    private Path getChatSessionsFile() {
        Config config = configService.load();
        String chatDir = config.getChatSessionsDir();
        if (chatDir == null || chatDir.isEmpty()) chatDir = "chat";
        String chatSessionsFile = config.getChatSessionsFile();
        if (chatSessionsFile == null || chatSessionsFile.isEmpty()) chatSessionsFile = "chat_sessions.json";
        return Paths.get(configService.getBaseDir()).resolve(chatDir).resolve(chatSessionsFile);
    }
}