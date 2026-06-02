package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class FeishuChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatSessionService.class);

    private final AppConfig appConfig;
    private final ConfigService configService;

    public FeishuChatSessionService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getSessionsFile() {
        String baseDir = configService.getBaseDir();
        return Paths.get(baseDir, "chat", "feishu_sessions.json");
    }

    public SessionsData load() {
        return FileUtil.readJson(getSessionsFile(), new TypeReference<SessionsData>() {}, new SessionsData());
    }

    public void save(SessionsData data) {
        FileUtil.writeJson(getSessionsFile(), data);
    }

    /**
     * Get or create a session for a user key.
     * userKey format: "p2p:{openId}" for single chats, "group:{chatId}:{openId}" for group chats
     */
    public FeishuSession getOrCreate(String userKey, String chatId, String chatType) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                return s;
            }
        }
        FeishuSession session = new FeishuSession();
        session.userKey = userKey;
        session.chatId = chatId;
        session.chatType = chatType;
        session.created = TimeUtil.nowStr();
        session.updated = TimeUtil.nowStr();
        data.sessions.add(0, session);
        save(data);
        return session;
    }

    public FeishuSession get(String userKey) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                return s;
            }
        }
        return null;
    }

    public void saveMessage(String userKey, String role, String content) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                s.messages.add(new ChatMessage(role, content, TimeUtil.nowStr()));
                s.updated = TimeUtil.nowStr();
                save(data);
                return;
            }
        }
    }

    public void saveMessage(String userKey, String role, String content, String mode) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                s.messages.add(new ChatMessage(role, content, TimeUtil.nowStr(), mode));
                s.updated = TimeUtil.nowStr();
                save(data);
                return;
            }
        }
    }

    public void setOpenCodeSessionId(String userKey, String sessionId) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                s.openCodeSessionId = sessionId;
                save(data);
                return;
            }
        }
    }

    public void setOpenCodeCodeSessionId(String userKey, String sessionId) {
        SessionsData data = load();
        for (FeishuSession s : data.sessions) {
            if (s.userKey.equals(userKey)) {
                s.openCodeCodeSessionId = sessionId;
                save(data);
                return;
            }
        }
    }

    /**
     * Build a context prompt from message history filtered by mode,
     * excluding the last message (the current one being processed).
     */
    public String buildHistoryContext(String userKey, String mode) {
        FeishuSession session = get(userKey);
        if (session == null) {
            return null;
        }

        // Filter messages by mode and collect all except the current one (last in filtered list)
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage msg : session.messages) {
            if (mode.equals(msg.getMode())) {
                filtered.add(msg);
            }
        }
        if (filtered.size() <= 1) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话历史，供参考：\n\n");

        List<ChatMessage> history = filtered.subList(0, filtered.size() - 1);
        for (ChatMessage msg : history) {
            String label = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(label).append(": ").append(msg.getContent()).append("\n\n");
        }

        sb.append("---\n\n请基于以上历史对话，继续回复用户的最新消息。");
        return sb.toString();
    }

    public void deleteSession(String userKey) {
        SessionsData data = load();
        data.sessions.removeIf(s -> s.userKey.equals(userKey));
        save(data);
    }

    public static class SessionsData {
        public List<FeishuSession> sessions = new ArrayList<>();
    }

    public static class FeishuSession {
        public String userKey;
        public String chatId;
        public String chatType;    // "p2p" or "group"
        public String openCodeSessionId;
        public String openCodeCodeSessionId;
        public String created;
        public String updated;
        public List<ChatMessage> messages = new ArrayList<>();
    }
}
