package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.ChatSessionService;
import com.laoqi.assistant.service.ChatSessionService.SessionsData;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.util.TimeUtil;
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


    private static final String CODE_PREFIX = "/code ";

    @GetMapping
    public String chatPage(@RequestParam(required = false, defaultValue = "") String id,
                            Model model) {
        SessionsData data = sessionService.load();
        String effectiveMode = "knowledge";

        // 处理 new 的情况 - 创建新会话后重定向到真实 ID
        if ("new".equals(id)) {
            String newSessionId = TimeUtil.sessionId();
            String now = TimeUtil.nowStr();
            ChatSession newSession = new ChatSession();
            newSession.setId(newSessionId);
            newSession.setTitle("新对话");
            newSession.setMode(effectiveMode);
            newSession.setCreated(now);
            newSession.setUpdated(now);
            newSession.setMessages(new ArrayList<>());
            data.sessions.add(0, newSession);
            data.current = newSessionId;
            sessionService.save(data);
            return "redirect:/chat?id=" + newSessionId;
        }

        ChatSession current = null;
        String currentId = "";

        if (!id.isEmpty()) {
            // id 不为空，查找对应会话
            currentId = id;
            for (ChatSession s : data.sessions) {
                if (s.getId().equals(currentId)) {
                    current = s;
                    effectiveMode = s.getMode() != null ? s.getMode() : "knowledge";
                    break;
                }
            }
            // 如果没找到对应的会话，创建一个新会话后重定向
            if (current == null) {
                String newSessionId = TimeUtil.sessionId();
                String now = TimeUtil.nowStr();
                ChatSession newSession = new ChatSession();
                newSession.setId(newSessionId);
                newSession.setTitle("新对话");
                newSession.setMode(effectiveMode);
                newSession.setCreated(now);
                newSession.setUpdated(now);
                newSession.setMessages(new ArrayList<>());
                data.sessions.add(0, newSession);
                data.current = newSessionId;
                sessionService.save(data);
                return "redirect:/chat?id=" + newSessionId;
            }
        } else {
            // id 为空，检查是否有已有会话
            if (!data.sessions.isEmpty()) {
                String targetId = data.current;
                if (targetId != null) {
                    for (ChatSession s : data.sessions) {
                        if (s.getId().equals(targetId)) {
                            return "redirect:/chat?id=" + targetId;
                        }
                    }
                }
                return "redirect:/chat?id=" + data.sessions.get(0).getId();
            } else {
                // 没有会话，创建第一个新会话后重定向
                String newSessionId = TimeUtil.sessionId();
                String now = TimeUtil.nowStr();
                ChatSession newSession = new ChatSession();
                newSession.setId(newSessionId);
                newSession.setTitle("新对话");
                newSession.setMode(effectiveMode);
                newSession.setCreated(now);
                newSession.setUpdated(now);
                newSession.setMessages(new ArrayList<>());
                data.sessions.add(newSession);
                data.current = newSessionId;
                sessionService.save(data);
                return "redirect:/chat?id=" + newSessionId;
            }
        }

        model.addAttribute("sessions", data.sessions);
        model.addAttribute("current", current);
        model.addAttribute("current_id", currentId);
        model.addAttribute("current_mode", effectiveMode);
        return "chat";
    }

    /**
     * 判断是否为编程AI请求：以 /code 开头
     */
    private boolean isCodeRequest(String text) {
        return text.startsWith(CODE_PREFIX);
    }

    private String stripCodePrefix(String text) {
        return text.substring(CODE_PREFIX.length());
    }

    @PostMapping
    public SseEmitter chat(@RequestParam String message,
                            @RequestParam(name = "session_id") String sessionId) {
        log.info("收到对话请求: message={}, sessionId={}", message, sessionId);
        SseEmitter emitter = new SseEmitter(0L); // 不设超时，等待AI回复

        // 注册完成和超时回调
        emitter.onCompletion(() -> log.info("[chat] SSE 连接完成"));
        emitter.onError((ex) -> log.error("[chat] SSE 连接错误", ex));

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // 以 /code 前缀判断是否走编程AI，否则默认走知识库
                boolean isCodeRelated = isCodeRequest(message);
                String mode = isCodeRelated ? "coding" : "knowledge";
                String actualMessage = isCodeRelated ? stripCodePrefix(message) : message;

                if (isCodeRelated) {
                    if (!openCodeService.isCodeHealthy()) {
                        sendError(emitter, "编程AI服务未启动，请确保 opencode serve 已在端口 " + appConfig.getCodePort() + " 运行");
                        return;
                    }
                } else {
                    if (!openCodeService.isHealthy()) {
                        sendError(emitter, "知识库AI服务未启动，请确保 opencode serve 已在端口 " + appConfig.getNotesPort() + " 运行");
                        return;
                    }
                }

                sendStatus(emitter, mode, "⏳ 正在思考...");
                
                String opencodeSessionId = isCodeRelated ? ensureCodeSession(sessionId) : ensureOpenCodeSession(sessionId);
                
                log.info("[chat] 开始发送消息到 opencode, sessionId={}, mode={}, strippedPrefix={}", opencodeSessionId, mode, isCodeRelated);
                String reply;
                if (isCodeRelated) {
                    reply = openCodeService.sendCodeMessage(opencodeSessionId, actualMessage);
                } else {
                    reply = openCodeService.sendMessage(opencodeSessionId, actualMessage);
                }
                log.info("[chat] 收到 opencode 回复, 长度={}", reply != null ? reply.length() : 0);

                if (reply != null && !reply.isEmpty()) {
                    sendText(emitter, mode, reply);
                } else {
                    sendText(emitter, mode, "(AI 未返回回复)");
                }

                sendDone(emitter, mode);

            } catch (Exception e) {
                log.error("对话请求处理失败", e);
                try {
                    sendError(emitter, "AI 服务调用失败: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("发送错误信息失败", ex);
                    try {
                        emitter.completeWithError(ex);
                    } catch (Exception e2) {
                        log.error("无法完成错误发送", e2);
                    }
                }
            }
        });

        return emitter;
    }

    private String ensureCodeSession(String chatSessionId) throws Exception {
        String idleSessionId = openCodeService.findIdleCodeSession();
        if (idleSessionId != null) {
            log.info("[chat-code] 复用已有 session: {}", idleSessionId);
            return idleSessionId;
        }

        log.info("[chat-code] 没有空闲 session，创建新 session");
        return openCodeService.createCodeSession("chat-" + chatSessionId);
    }

    @PostMapping("/session/save")
    @ResponseBody
    public Map<String, Object> saveSession(@RequestParam(name = "session_id") String sessionId,
                                            @RequestParam String role,
                                            @RequestParam String content,
                                            @RequestParam(required = false, defaultValue = "knowledge") String mode) {
        boolean saved = sessionService.saveMessage(sessionId, role, content, mode);
        if (!saved) {
            return Map.of("ok", false, "error", "对话会话不存在或已失效");
        }
        return Map.of("ok", true, "session_id", sessionId);
    }

    @PostMapping("/session/delete")
    @ResponseBody
    public Map<String, Object> deleteSession(@RequestParam(name = "session_id") String sessionId) {
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

    private void sendStatus(SseEmitter emitter, String mode, String text) {
        try {
            Map<String, Object> data = Map.of("type", "status", "content", text, "mode", mode);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("发送状态消息失败", e);
        }
    }

    private void sendText(SseEmitter emitter, String mode, String text) {
        try {
            Map<String, Object> data = Map.of("type", "text", "content", text, "mode", mode);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.error("发送文本消息失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.error("无法完成发射器", ex);
            }
        }
    }

    private void sendDone(SseEmitter emitter, String mode) {
        try {
            Map<String, Object> data = Map.of("type", "done", "mode", mode);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送完成消息失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.error("无法完成发射器", ex);
            }
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            Map<String, Object> data = Map.of("type", "error", "content", message);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送错误信息失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.error("无法完成发射器", ex);
            }
        }
    }
}
