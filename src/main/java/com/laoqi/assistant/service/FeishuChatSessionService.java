package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.db.SessionDbService;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class FeishuChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatSessionService.class);

    private final SessionService sessionService;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;

    public FeishuChatSessionService(SessionService sessionService,
                                    SessionDbService sessionDbService,
                                    MessageDbService messageDbService) {
        this.sessionService = sessionService;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
    }

    // ========== 公开 API ==========

    public static class SessionsData {
        public List<FeishuSession> sessions = new ArrayList<>();
    }

    public static class FeishuSession {
        public String userKey;
        public String chatId;
        public String chatType;
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

    @Transactional
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

    public String buildHistoryContext(String userKey, String mode) {
        return sessionService.buildHistoryContext(userKey, mode, null);
    }

    public String buildHistoryContext(String userKey, String mode, String currentQuery) {
        return sessionService.buildHistoryContext(userKey, mode, currentQuery);
    }

    public void deleteSession(String userKey) {
        sessionService.deleteSession(userKey);
    }

    // ========== 内部转换 ==========

    private FeishuSession toModel(SessionEntity se) {
        if (se == null) return null;
        FeishuSession session = new FeishuSession();
        session.userKey = se.getId();
        session.chatId = se.getChatId() != null ? se.getChatId() : "";
        session.chatType = se.getChatType() != null ? se.getChatType() : "p2p";
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
