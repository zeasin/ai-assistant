package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.ChatSessionService;
import com.laoqi.assistant.service.ChatSessionService.SessionsData;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LlmService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService chatExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-sse");
        t.setDaemon(true);
        return t;
    });

    private final ChatSessionService sessionService;
    private final OpenCodeService openCodeService;
    private final LlmService llmService;
    private final ConfigService configService;
    private final LogService logService;
    private final AppConfig appConfig;

    public ChatController(ChatSessionService sessionService, OpenCodeService openCodeService,
                          LlmService llmService, ConfigService configService,
                          LogService logService, AppConfig appConfig) {
        this.sessionService = sessionService;
        this.openCodeService = openCodeService;
        this.llmService = llmService;
        this.configService = configService;
        this.logService = logService;
        this.appConfig = appConfig;
    }

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
        model.addAttribute("ai_provider", configService.load().getAiProvider());
        return "chat";
    }

    @PostMapping
    public SseEmitter chat(@RequestParam String message,
                            @RequestParam(name = "session_id") String sessionId) {
        log.info("收到对话请求: message={}, sessionId={}", message, sessionId);
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> log.info("[chat] SSE 连接完成"));
        emitter.onError((ex) -> log.error("[chat] SSE 连接错误", ex));

        String mode = "knowledge";
        sessionService.saveMessage(sessionId, "user", message, mode);

        // 判断使用哪种 AI 模式
        var config = configService.load();
        boolean useDirectLlm = "direct".equals(config.getAiProvider());

        chatExecutor.execute(() -> {
            try {
                sendStatus(emitter, mode, useDirectLlm ? "🧠 LLM 直连思考中..." : "⏳ 正在思考...");

                String context = sessionService.buildHistoryContext(sessionId, mode, message);

                StringBuilder fullText = new StringBuilder();
                if (context != null) {
                    log.info("[chat] 恢复对话历史上下文, sessionId={}, mode={}", sessionId, mode);
                    fullText.append(context).append("\n\n---\n\n");
                }
                fullText.append("用户最新消息:\n").append(message);
                String fullMessage = fullText.toString();

                String replyText;
                if (useDirectLlm) {
                    // 新模式：Java 直连 DeepSeek
                    log.info("[chat] 使用 LLM 直连模式");
                    if (!llmService.isAvailable()) {
                        throw new IllegalStateException("LLM API Key 未配置，请在 application.yml 中设置 app.llm.api-key");
                    }
                    replyText = llmService.chat("你是一个知识库助手，基于笔记库上下文回答问题。请用中文回答。", fullMessage);
                } else {
                    // 旧模式：走 opencode serve
                    log.info("[chat] 使用 opencode 模式");
                    String opencodeSessionId = openCodeService.createSession("chat-" + sessionId);
                    String reply = openCodeService.sendMessage(opencodeSessionId, fullMessage);
                    replyText = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回回复)";
                }

                log.info("[chat] 收到回复, 长度={}", replyText.length());
                sessionService.saveMessage(sessionId, "assistant", replyText, mode);
                sendText(emitter, mode, replyText);
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
