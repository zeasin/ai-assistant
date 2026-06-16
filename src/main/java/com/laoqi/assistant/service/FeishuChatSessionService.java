package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.db.SessionDbService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class FeishuChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatSessionService.class);

    private final SessionService sessionService;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;
    private final ConfigService configService;

    public FeishuChatSessionService(SessionService sessionService,
                                    SessionDbService sessionDbService,
                                    MessageDbService messageDbService,
                                    ConfigService configService) {
        this.sessionService = sessionService;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
        this.configService = configService;
    }

    // ========== 公开 API ==========

    public static class SessionsData {
        public List<FeishuSession> sessions = new ArrayList<>();
    }

    public static class FeishuSession {
        public String userKey;
        public String chatId;
        public String chatType;
        public String openCodeSessionId;
        public String openCodeCodeSessionId;
        public String created;
        public String updated;
        public List<ChatMessage> messages = new ArrayList<>();
    }

    public SessionsData load() {
        SessionsData data = new SessionsData();
        List<SessionEntity> entities = sessionDbService.listBySourceOrderByUpdate("feishu");
        for (SessionEntity se : entities) {
            data.sessions.add(toModel(se));
        }
        return data;
    }

    public void save(SessionsData data) {
        messageDbService.getBaseMapper().delete(
                new QueryWrapper<MessageEntity>().eq("source", "feishu"));
        sessionDbService.getBaseMapper().delete(
                new QueryWrapper<SessionEntity>().eq("source", "feishu"));
        for (FeishuSession session : data.sessions) {
            SessionEntity se = new SessionEntity();
            se.setId(session.userKey);
            se.setSource("feishu");
            se.setTitle("");
            se.setChatId(session.chatId);
            se.setChatType(session.chatType);
            se.setOpenCodeSessionId(session.openCodeSessionId);
            se.setOpenCodeCodeSessionId(session.openCodeCodeSessionId);
            se.setCreatedAt(session.created);
            se.setUpdatedAt(session.updated);
            sessionDbService.save(se);

            if (session.messages != null) {
                for (ChatMessage msg : session.messages) {
                    MessageEntity me = new MessageEntity();
                    me.setSessionId(session.userKey);
                    me.setSource("feishu");
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime());
                    messageDbService.save(me);
                }
            }
        }
    }

    public FeishuSession getOrCreate(String userKey, String chatId, String chatType) {
        SessionEntity se = sessionService.getOrCreateFeishuSession(userKey, chatId, chatType);
        return toModel(se);
    }

    public FeishuSession get(String userKey) {
        SessionEntity se = sessionService.getSession(userKey);
        if (se == null) return null;
        return toModel(se);
    }

    public void saveMessage(String userKey, String role, String content) {
        saveMessage(userKey, role, content, null);
    }

    public void saveMessage(String userKey, String role, String content, String mode) {
        sessionService.saveMessage(userKey, role, content, mode, "feishu");
    }

    public void setOpenCodeSessionId(String userKey, String sessionId) {
        SessionEntity update = new SessionEntity();
        update.setId(userKey);
        update.setOpenCodeSessionId(sessionId);
        update.setUpdatedAt(TimeUtil.nowStr());
        sessionDbService.updateById(update);
    }

    public void setOpenCodeCodeSessionId(String userKey, String sessionId) {
        SessionEntity update = new SessionEntity();
        update.setId(userKey);
        update.setOpenCodeCodeSessionId(sessionId);
        update.setUpdatedAt(TimeUtil.nowStr());
        sessionDbService.updateById(update);
    }

    public String buildHistoryContext(String userKey, String mode) {
        return sessionService.buildHistoryContext(userKey, mode, null);
    }

    public void deleteSession(String userKey) {
        sessionService.deleteSession(userKey);
    }

    // ========== 旧 JSON 迁移 ==========

    @PostConstruct
    public void migrateFromJson() {
        Path oldFile = getSessionsFile();
        if (!Files.exists(oldFile)) {
            log.debug("No old feishu_sessions.json found, skipping migration");
            return;
        }

        long count = sessionDbService.count(new QueryWrapper<SessionEntity>().eq("source", "feishu"));
        if (count > 0) {
            log.info("New tables already have {} feishu sessions, skipping JSON migration", count);
            return;
        }

        log.info("Found feishu_sessions.json, starting migration to new tables...");
        SessionsData oldData = FileUtil.readJson(oldFile, new TypeReference<SessionsData>() {}, new SessionsData());
        int sessionCount = 0;
        int msgCount = 0;

        for (FeishuSession session : oldData.sessions) {
            SessionEntity se = new SessionEntity();
            se.setId(session.userKey);
            se.setSource("feishu");
            se.setTitle("");
            se.setChatId(session.chatId);
            se.setChatType(session.chatType);
            se.setOpenCodeSessionId(session.openCodeSessionId);
            se.setOpenCodeCodeSessionId(session.openCodeCodeSessionId);
            se.setCreatedAt(session.created != null ? session.created : TimeUtil.nowStr());
            se.setUpdatedAt(session.updated != null ? session.updated : TimeUtil.nowStr());
            sessionDbService.save(se);
            sessionCount++;

            if (session.messages != null) {
                for (ChatMessage msg : session.messages) {
                    MessageEntity me = new MessageEntity();
                    me.setSessionId(session.userKey);
                    me.setSource("feishu");
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime() != null ? msg.getTime() : TimeUtil.nowStr());
                    messageDbService.save(me);
                    msgCount++;
                }
            }
        }

        try {
            Files.move(oldFile, oldFile.resolveSibling("feishu_sessions.json.bak"));
            log.info("Feishu JSON migration complete: {} sessions, {} messages. Old file renamed to .bak", sessionCount, msgCount);
        } catch (Exception e) {
            log.warn("Failed to rename old feishu_sessions.json: {}", e.getMessage());
        }
    }

    private Path getSessionsFile() {
        return Paths.get(configService.getBaseDir(), "chat", "feishu_sessions.json");
    }

    // ========== 内部转换 ==========

    private FeishuSession toModel(SessionEntity se) {
        if (se == null) return null;
        FeishuSession session = new FeishuSession();
        session.userKey = se.getId();
        session.chatId = se.getChatId() != null ? se.getChatId() : "";
        session.chatType = se.getChatType() != null ? se.getChatType() : "p2p";
        session.openCodeSessionId = se.getOpenCodeSessionId();
        session.openCodeCodeSessionId = se.getOpenCodeCodeSessionId();
        session.created = se.getCreatedAt();
        session.updated = se.getUpdatedAt();

        List<MessageEntity> msgEntities = messageDbService.listBySession(se.getId());
        List<ChatMessage> messages = new ArrayList<>();
        for (MessageEntity me : msgEntities) {
            messages.add(new ChatMessage(me.getRole(), me.getContent(), me.getCreatedAt(), me.getMode()));
        }
        session.messages = messages;
        return session;
    }
}
