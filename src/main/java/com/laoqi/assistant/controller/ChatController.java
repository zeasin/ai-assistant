package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.ChatSessionService;
import com.laoqi.assistant.service.ChatSessionService.SessionsData;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ChatSessionService sessionService;
    private final OpenCodeService openCodeService;
    private final LogService logService;
    private final AppConfig appConfig;

    public ChatController(ChatSessionService sessionService, OpenCodeService openCodeService,
                           LogService logService, AppConfig appConfig) {
        this.sessionService = sessionService;
        this.openCodeService = openCodeService;
        this.logService = logService;
        this.appConfig = appConfig;
    }

    @GetMapping
    public String chatPage(@RequestParam(required = false, defaultValue = "") String id,
                            Model model) {
        SessionsData data = sessionService.load();
        ChatSession current = null;
        String currentId = "";

        if (!id.isEmpty() && !"new".equals(id)) {
            currentId = id;
            for (ChatSession s : data.sessions) {
                if (s.getId().equals(currentId)) {
                    current = s;
                    break;
                }
            }
        } else if (!"new".equals(id)) {
            currentId = data.current != null ? data.current : "";
            if (!currentId.isEmpty()) {
                for (ChatSession s : data.sessions) {
                    if (s.getId().equals(currentId)) {
                        current = s;
                        break;
                    }
                }
            }
        }

        model.addAttribute("sessions", data.sessions);
        model.addAttribute("current", current);
        model.addAttribute("current_id", currentId);
        return "chat";
    }

    @PostMapping
    public SseEmitter chat(@RequestParam String message,
                            @RequestParam(name = "session_id", required = false, defaultValue = "") String sessionId) {
        log.info("收到对话请求: message={}, sessionId={}", message, sessionId);
        SseEmitter emitter = new SseEmitter(300_000L);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Check if opencode serve is healthy (quick check)
                if (!openCodeService.isHealthy()) {
                    sendError(emitter, "笔记库AI服务未启动，请确保 opencode serve 已在端口 " + appConfig.getNotesPort() + " 运行");
                    return;
                }

                // Get or create opencode session
                String opencodeSessionId = ensureOpenCodeSession(sessionId);

                // Send message and collect reply via SSE events
                sendStatus(emitter, "⏳ 正在思考...");
                log.info("[chat] 开始发送消息到 opencode, sessionId={}", opencodeSessionId);
                String reply = openCodeService.sendMessage(opencodeSessionId, message);
                log.info("[chat] 收到 opencode 回复, 长度={}", reply != null ? reply.length() : 0);

                // Send the reply to frontend
                if (reply != null && !reply.isEmpty()) {
                    sendText(emitter, reply);
                } else {
                    sendText(emitter, "(AI 未返回回复)");
                }

                sendDone(emitter);

            } catch (Exception e) {
                log.error("对话请求处理失败", e);
                try {
                    sendError(emitter, "AI 服务调用失败: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("发送错误信息失败", ex);
                }
            }
        });

        return emitter;
    }

    @PostMapping("/session/save")
    @ResponseBody
    public Map<String, Object> saveSession(@RequestParam(required = false, defaultValue = "") String sessionId,
                                            @RequestParam String role,
                                            @RequestParam String content) {
        String sid = sessionService.saveMessage(
                sessionId != null && !sessionId.isEmpty() ? sessionId : null, role, content);
        return Map.of("ok", true, "session_id", sid);
    }

    @PostMapping("/session/delete")
    @ResponseBody
    public Map<String, Object> deleteSession(@RequestParam String sessionId) {
        sessionService.deleteSession(sessionId);
        return Map.of("ok", true);
    }

    private String ensureOpenCodeSession(String chatSessionId) throws Exception {
        // Try to find an idle session to reuse
        String idleSessionId = openCodeService.findIdleSession();
        if (idleSessionId != null) {
            log.info("[chat] 复用已有 session: {}", idleSessionId);
            return idleSessionId;
        }

        // No idle session, create a new one
        log.info("[chat] 没有空闲 session，创建新 session");
        return openCodeService.createSession("chat-" + chatSessionId);
    }

    private void sendStatus(SseEmitter emitter, String text) throws Exception {
        Map<String, Object> data = Map.of("type", "status", "content", text);
        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
    }

    private void sendText(SseEmitter emitter, String text) throws Exception {
        Map<String, Object> data = Map.of("type", "text", "content", text);
        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
    }

    private void sendError(SseEmitter emitter, String message) throws Exception {
        Map<String, Object> data = Map.of("type", "error", "content", message);
        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        emitter.complete();
    }

    private void sendDone(SseEmitter emitter) throws Exception {
        Map<String, Object> data = Map.of("type", "done");
        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        emitter.complete();
    }
}
