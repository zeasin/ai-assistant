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
        String chatDir = config.getChatSessionsDir();
        if (chatDir == null || chatDir.isEmpty()) chatDir = "chat";
        String chatSessionsFile = config.getChatSessionsFile();
        if (chatSessionsFile == null || chatSessionsFile.isEmpty()) chatSessionsFile = "chat_sessions.json";
        return Paths.get(configService.getBaseDir()).resolve(chatDir).resolve(chatSessionsFile);
    }

    public static class SessionsData {
        public String current;
        public List<ChatSession> sessions = new ArrayList<>();
    }

    public SessionsData load() {
        SessionsData data = FileUtil.readJson(getChatSessionsFile(), SessionsData.class, new SessionsData());
        // 给旧的会话和消息补充默认 mode 字段（向下兼容）
        for (ChatSession session : data.sessions) {
            if (session.getMode() == null) {
                session.setMode("knowledge");
            }
            if (session.getMessages() != null) {
                for (ChatSession.ChatMessage msg : session.getMessages()) {
                    if (msg.getMode() == null) {
                        // 如果是AI回复，使用会话的mode；用户消息可以为空
                        if (!"user".equals(msg.getRole())) {
                            msg.setMode(session.getMode());
                        }
                    }
                }
            }
        }
        return data;
    }

    public void save(SessionsData data) {
        FileUtil.writeJson(getChatSessionsFile(), data);
    }

    public boolean saveMessage(String sessionId, String role, String content, String mode) {
        SessionsData data = load();
        String now = TimeUtil.nowStr();

        if (sessionId != null && !sessionId.isEmpty()) {
            for (ChatSession s : data.sessions) {
                if (s.getId().equals(sessionId)) {
                    s.getMessages().add(new ChatSession.ChatMessage(role, content, now, mode));
                    s.setTitle(ChatSession.deriveTitle(s.getMessages()));
                    s.setUpdated(now);
                    if (mode != null && !mode.isEmpty()) {
                        s.setMode(mode);
                    }
                    data.current = sessionId;
                    save(data);
                    return true;
                }
            }
        }

        return false;
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
