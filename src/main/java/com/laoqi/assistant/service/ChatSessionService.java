package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChatSessionService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig appConfig;
    private final ConfigService configService;

    public ChatSessionService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getChatSessionsFile() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) baseDir = "D:\\projects\\richie_learning_notes";
        String chatSessionsFile = config.getChatSessionsFile();
        if (chatSessionsFile == null || chatSessionsFile.isEmpty()) chatSessionsFile = "chat_sessions.json";
        return Paths.get(baseDir).resolve(chatSessionsFile);
    }

    public static class SessionsData {
        public String current;
        public List<ChatSession> sessions = new ArrayList<>();
    }

    public SessionsData load() {
        return FileUtil.readJson(getChatSessionsFile(), SessionsData.class, new SessionsData());
    }

    public void save(SessionsData data) {
        FileUtil.writeJson(getChatSessionsFile(), data);
    }

    public String saveMessage(String sessionId, String role, String content) {
        SessionsData data = load();
        String now = TimeUtil.nowStr();

        // Find existing session
        if (sessionId != null && !sessionId.isEmpty()) {
            for (ChatSession s : data.sessions) {
                if (s.getId().equals(sessionId)) {
                    s.getMessages().add(new ChatSession.ChatMessage(role, content, now));
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
        List<ChatSession.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatSession.ChatMessage(role, content, now));
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
