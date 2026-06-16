package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.db.SessionDbService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final SessionService sessionService;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;

    public ChatSessionService(AppConfig appConfig, ConfigService configService,
                              SessionService sessionService,
                              SessionDbService sessionDbService,
                              MessageDbService messageDbService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.sessionService = sessionService;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
    }

    // ========== 旧 JSON 迁移（兼容旧数据） ==========

    @PostConstruct
    public void init() {
        migrateFromJson();
    }

    private void migrateFromJson() {
        Path oldFile = getChatSessionsFile();
        if (!Files.exists(oldFile)) {
            log.debug("No old chat_sessions.json found, skipping migration");
            return;
        }

        long count = sessionDbService.count(new QueryWrapper<SessionEntity>().eq("source", "web"));
        if (count > 0) {
            log.info("New tables already have {} web sessions, skipping JSON migration", count);
            return;
        }

        log.info("Found chat_sessions.json, starting migration to new tables...");
        SessionsData oldData = FileUtil.readJson(oldFile, SessionsData.class, new SessionsData());
        int sessionCount = 0;
        int msgCount = 0;

        for (ChatSession session : oldData.sessions) {
            SessionEntity se = new SessionEntity();
            se.setId(session.getId());
            se.setSource("web");
            se.setTitle(session.getTitle() != null ? session.getTitle() : "新对话");
            se.setMode(session.getMode() != null ? session.getMode() : "knowledge");
            se.setCreatedAt(session.getCreated() != null ? session.getCreated() : TimeUtil.nowStr());
            se.setUpdatedAt(session.getUpdated() != null ? session.getUpdated() : TimeUtil.nowStr());
            sessionDbService.save(se);
            sessionCount++;

            if (session.getMessages() != null) {
                for (ChatMessage msg : session.getMessages()) {
                    MessageEntity me = new MessageEntity();
                    me.setSessionId(session.getId());
                    me.setSource("web");
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode() != null ? msg.getMode() : session.getMode());
                    me.setCreatedAt(msg.getTime() != null ? msg.getTime() : TimeUtil.nowStr());
                    messageDbService.save(me);
                    msgCount++;
                }
            }
        }

        try {
            Files.move(oldFile, oldFile.resolveSibling("chat_sessions.json.bak"));
            log.info("JSON migration complete: {} sessions, {} messages. Old file renamed to .bak", sessionCount, msgCount);
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
        List<SessionEntity> entities = sessionDbService.listAllOrderByUpdate();
        for (SessionEntity se : entities) {
            if ("web".equals(se.getSource())) {
                data.sessions.add(toModel(se));
            }
        }
        if (!data.sessions.isEmpty()) {
            data.current = data.sessions.get(0).getId();
        }
        return data;
    }

    public void save(SessionsData data) {
        messageDbService.getBaseMapper().delete(
                new QueryWrapper<MessageEntity>().eq("source", "web"));
        sessionDbService.getBaseMapper().delete(
                new QueryWrapper<SessionEntity>().eq("source", "web"));
        for (ChatSession session : data.sessions) {
            SessionEntity se = new SessionEntity();
            se.setId(session.getId());
            se.setSource("web");
            se.setTitle(session.getTitle());
            se.setMode(session.getMode());
            se.setCreatedAt(session.getCreated());
            se.setUpdatedAt(session.getUpdated());
            sessionDbService.save(se);

            if (session.getMessages() != null) {
                for (ChatMessage msg : session.getMessages()) {
                    MessageEntity me = new MessageEntity();
                    me.setSessionId(session.getId());
                    me.setSource("web");
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime());
                    messageDbService.save(me);
                }
            }
        }
    }

    public boolean saveMessage(String sessionId, String role, String content, String mode) {
        SessionEntity session = sessionService.getSession(sessionId);
        if (session == null) return false;
        sessionService.saveMessage(sessionId, role, content, mode, "web");
        return true;
    }

    public void deleteSession(String sessionId) {
        sessionService.deleteSession(sessionId);
    }

    public ChatSession getSession(String sessionId) {
        SessionEntity se = sessionService.getSession(sessionId);
        if (se == null) return null;
        return toModel(se);
    }

    public String buildHistoryContext(String sessionId, String mode) {
        return sessionService.buildHistoryContext(sessionId, mode);
    }

    public String buildHistoryContext(String sessionId, String mode, String currentQuery) {
        return sessionService.buildHistoryContext(sessionId, mode, currentQuery);
    }

    // ========== 转换方法 ==========

    private ChatSession toModel(SessionEntity se) {
        ChatSession session = new ChatSession();
        session.setId(se.getId());
        session.setTitle(se.getTitle() != null ? se.getTitle() : "新对话");
        session.setMode(se.getMode() != null ? se.getMode() : "knowledge");
        session.setCreated(se.getCreatedAt());
        session.setUpdated(se.getUpdatedAt());

        List<MessageEntity> msgEntities = messageDbService.listBySession(se.getId());
        List<ChatMessage> messages = new ArrayList<>();
        for (MessageEntity me : msgEntities) {
            messages.add(new ChatMessage(me.getRole(), me.getContent(), me.getCreatedAt(), me.getMode()));
        }
        session.setMessages(messages);
        return session;
    }

    private Path getChatSessionsFile() {
        Config config = configService.load();
        String chatDir = "chat";
        return Paths.get(configService.getBaseDir()).resolve(chatDir).resolve("chat_sessions.json");
    }
}
