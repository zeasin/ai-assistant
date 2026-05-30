package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatSessionService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig appConfig;

    public ChatSessionService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public static class SessionsData {
        public String current;
        public List<ChatSession> sessions = new ArrayList<>();
    }

    public SessionsData load() {
        return FileUtil.readJson(appConfig.getChatSessionsFile(), SessionsData.class, new SessionsData());
    }

    public void save(SessionsData data) {
        FileUtil.writeJson(appConfig.getChatSessionsFile(), data);
    }

    public String saveMessage(String sessionId, String role, String content) {
        SessionsData data = load();
        String now = TimeUtil.nowStr();

        // Find existing session
        if (sessionId != null && !sessionId.isEmpty()) {
            for (ChatSession s : data.sessions) {
                if (s.getId().equals(sessionId)) {
                    s.getMessages().add(new ChatMessage(role, content, now));
                    s.setTitle(ChatSession.deriveTitle(s.getMessages()));
                    s.setUpdated(now);
                    data.current = sessionId;
                    save(data);
                    return sessionId;
                }
            }
        }

        // Create new session
        String sid = TimeUtil.sessionId();
        ChatSession session = new ChatSession();
        session.setId(sid);
        session.setCreated(now);
        session.setUpdated(now);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage(role, content, now));
        session.setMessages(msgs);
        session.setTitle(ChatSession.deriveTitle(msgs));
        data.sessions.add(0, session);
        data.current = sid;
        save(data);
        return sid;
    }

    public void deleteSession(String sessionId) {
        SessionsData data = load();
        data.sessions.removeIf(s -> s.getId().equals(sessionId));
        if (sessionId.equals(data.current)) {
            data.current = data.sessions.isEmpty() ? null : data.sessions.get(0).getId();
        }
        save(data);
    }

    public ChatSession getSession(String sessionId) {
        SessionsData data = load();
        return data.sessions.stream()
                .filter(s -> s.getId().equals(sessionId))
                .findFirst().orElse(null);
    }
}