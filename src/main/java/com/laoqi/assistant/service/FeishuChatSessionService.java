package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.entity.FeishuMessageEntity;
import com.laoqi.assistant.entity.FeishuSessionEntity;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.db.FeishuMessageDbService;
import com.laoqi.assistant.service.db.FeishuSessionDbService;
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

    private final FeishuSessionDbService feishuSessionDbService;
    private final FeishuMessageDbService feishuMessageDbService;
    private final ConfigService configService;

    public FeishuChatSessionService(FeishuSessionDbService feishuSessionDbService,
                                    FeishuMessageDbService feishuMessageDbService,
                                    ConfigService configService) {
        this.feishuSessionDbService = feishuSessionDbService;
        this.feishuMessageDbService = feishuMessageDbService;
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
        List<FeishuSessionEntity> entities = feishuSessionDbService.list(
                new QueryWrapper<FeishuSessionEntity>().orderByDesc("updated"));
        for (FeishuSessionEntity se : entities) {
            data.sessions.add(toModel(se));
        }
        return data;
    }

    public void save(SessionsData data) {
        feishuMessageDbService.remove(null);
        feishuSessionDbService.remove(null);
        for (FeishuSession session : data.sessions) {
            FeishuSessionEntity se = new FeishuSessionEntity();
            se.setUserKey(session.userKey);
            se.setChatId(session.chatId);
            se.setChatType(session.chatType);
            se.setOpenCodeSessionId(session.openCodeSessionId);
            se.setOpenCodeCodeSessionId(session.openCodeCodeSessionId);
            se.setCreated(session.created);
            se.setUpdated(session.updated);
            feishuSessionDbService.save(se);

            if (session.messages != null) {
                for (ChatMessage msg : session.messages) {
                    FeishuMessageEntity me = new FeishuMessageEntity();
                    me.setUserKey(session.userKey);
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime());
                    feishuMessageDbService.save(me);
                }
            }
        }
    }

    public FeishuSession getOrCreate(String userKey, String chatId, String chatType) {
        FeishuSessionEntity se = feishuSessionDbService.getById(userKey);
        if (se != null) {
            return toModel(se);
        }

        String now = TimeUtil.nowStr();

        FeishuSessionEntity entity = new FeishuSessionEntity();
        entity.setUserKey(userKey);
        entity.setChatId(chatId);
        entity.setChatType(chatType);
        entity.setCreated(now);
        entity.setUpdated(now);
        feishuSessionDbService.save(entity);

        FeishuSession session = new FeishuSession();
        session.userKey = userKey;
        session.chatId = chatId;
        session.chatType = chatType;
        session.created = now;
        session.updated = now;
        session.messages = new ArrayList<>();
        return session;
    }

    public FeishuSession get(String userKey) {
        FeishuSessionEntity se = feishuSessionDbService.getById(userKey);
        if (se == null) return null;
        return toModel(se);
    }

    public void saveMessage(String userKey, String role, String content) {
        saveMessage(userKey, role, content, null);
    }

    public void saveMessage(String userKey, String role, String content, String mode) {
        String now = TimeUtil.nowStr();

        FeishuMessageEntity msg = new FeishuMessageEntity();
        msg.setUserKey(userKey);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMode(mode);
        msg.setCreatedAt(now);
        feishuMessageDbService.save(msg);

        FeishuSessionEntity update = new FeishuSessionEntity();
        update.setUserKey(userKey);
        update.setUpdated(now);
        feishuSessionDbService.updateById(update);
    }

    public void setOpenCodeSessionId(String userKey, String sessionId) {
        FeishuSessionEntity update = new FeishuSessionEntity();
        update.setUserKey(userKey);
        update.setOpenCodeSessionId(sessionId);
        update.setUpdated(TimeUtil.nowStr());
        feishuSessionDbService.updateById(update);
    }

    public void setOpenCodeCodeSessionId(String userKey, String sessionId) {
        FeishuSessionEntity update = new FeishuSessionEntity();
        update.setUserKey(userKey);
        update.setOpenCodeCodeSessionId(sessionId);
        update.setUpdated(TimeUtil.nowStr());
        feishuSessionDbService.updateById(update);
    }

    public String buildHistoryContext(String userKey, String mode) {
        List<FeishuMessageEntity> allMsgs = feishuMessageDbService.listByUserKey(userKey);

        List<FeishuMessageEntity> filtered = new ArrayList<>();
        for (FeishuMessageEntity msg : allMsgs) {
            if (mode != null && mode.equals(msg.getMode())) {
                filtered.add(msg);
            }
        }
        if (filtered.size() <= 1) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话历史，供参考：\n\n");

        List<FeishuMessageEntity> history = filtered.subList(0, filtered.size() - 1);
        for (FeishuMessageEntity msg : history) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(label).append(": ").append(msg.getContent()).append("\n\n");
        }
        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    public void deleteSession(String userKey) {
        feishuMessageDbService.remove(new QueryWrapper<FeishuMessageEntity>().eq("user_key", userKey));
        feishuSessionDbService.removeById(userKey);
    }

    // ========== 迁移旧数据 ==========

    @PostConstruct
    public void migrateFromJson() {
        Path oldFile = getSessionsFile();
        if (!Files.exists(oldFile)) {
            log.debug("No old feishu_sessions.json found, skipping migration");
            return;
        }

        long count = feishuSessionDbService.count();
        if (count > 0) {
            log.info("SQLite already has {} feishu sessions, skipping JSON migration", count);
            return;
        }

        log.info("Found feishu_sessions.json, starting migration to SQLite...");
        SessionsData oldData = FileUtil.readJson(oldFile, new TypeReference<SessionsData>() {}, new SessionsData());
        int sessionCount = 0;
        int msgCount = 0;

        for (FeishuSession session : oldData.sessions) {
            FeishuSessionEntity se = new FeishuSessionEntity();
            se.setUserKey(session.userKey);
            se.setChatId(session.chatId);
            se.setChatType(session.chatType);
            se.setOpenCodeSessionId(session.openCodeSessionId);
            se.setOpenCodeCodeSessionId(session.openCodeCodeSessionId);
            se.setCreated(session.created != null ? session.created : TimeUtil.nowStr());
            se.setUpdated(session.updated != null ? session.updated : TimeUtil.nowStr());
            feishuSessionDbService.save(se);
            sessionCount++;

            if (session.messages != null) {
                for (ChatMessage msg : session.messages) {
                    FeishuMessageEntity me = new FeishuMessageEntity();
                    me.setUserKey(session.userKey);
                    me.setRole(msg.getRole());
                    me.setContent(msg.getContent());
                    me.setMode(msg.getMode());
                    me.setCreatedAt(msg.getTime() != null ? msg.getTime() : TimeUtil.nowStr());
                    feishuMessageDbService.save(me);
                    msgCount++;
                }
            }
        }

        try {
            Files.move(oldFile, oldFile.resolveSibling("feishu_sessions.json.bak"));
            log.info("Feishu migration complete: {} sessions, {} messages. Old file renamed to .bak", sessionCount, msgCount);
        } catch (Exception e) {
            log.warn("Failed to rename old feishu_sessions.json: {}", e.getMessage());
        }
    }

    private Path getSessionsFile() {
        return Paths.get(configService.getBaseDir(), "chat", "feishu_sessions.json");
    }

    // ========== 内部转换 ==========

    private FeishuSession toModel(FeishuSessionEntity se) {
        FeishuSession session = new FeishuSession();
        session.userKey = se.getUserKey();
        session.chatId = se.getChatId();
        session.chatType = se.getChatType();
        session.openCodeSessionId = se.getOpenCodeSessionId();
        session.openCodeCodeSessionId = se.getOpenCodeCodeSessionId();
        session.created = se.getCreated();
        session.updated = se.getUpdated();

        List<FeishuMessageEntity> msgEntities = feishuMessageDbService.listByUserKey(se.getUserKey());
        List<ChatMessage> messages = new ArrayList<>();
        for (FeishuMessageEntity me : msgEntities) {
            messages.add(new ChatMessage(me.getRole(), me.getContent(), me.getCreatedAt(), me.getMode()));
        }
        session.messages = messages;
        return session;
    }
}