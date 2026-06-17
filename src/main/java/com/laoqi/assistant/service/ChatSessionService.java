package com.laoqi.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.model.ChatSession;
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
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final SessionService sessionService;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;

    public ChatSessionService(SessionService sessionService,
                              SessionDbService sessionDbService,
                              MessageDbService messageDbService) {
        this.sessionService = sessionService;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
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

    public ChatSession createSession(String title, String mode) {
        String id = java.util.UUID.randomUUID().toString().substring(0, 12);
        SessionEntity se = new SessionEntity();
        String now = TimeUtil.nowStr();
        se.setId(id);
        se.setSource("web");
        se.setTitle(title);
        se.setMode(mode);
        se.setCreatedAt(now);
        se.setUpdatedAt(now);
        sessionDbService.save(se);
        return toModel(se);
    }

    @Transactional
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

    public void clearSession(String mode) {
        List<SessionEntity> sessions = sessionDbService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SessionEntity>()
                        .eq("source", "web")
                        .eq("mode", mode));
        for (SessionEntity se : sessions) {
            sessionService.deleteSession(se.getId());
        }
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
}
