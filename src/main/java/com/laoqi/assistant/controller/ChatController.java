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
import com.laoqi.assistant.service.NoteAssistantService;
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
    private final LlmService llmService;
    private final NoteAssistantService noteAssistantService;
    private final ConfigService configService;
    private final LogService logService;
    private final AppConfig appConfig;

    public ChatController(ChatSessionService sessionService,
                          LlmService llmService, NoteAssistantService noteAssistantService,
                          ConfigService configService,
                          LogService logService, AppConfig appConfig) {
        this.sessionService = sessionService;
        this.llmService = llmService;
        this.noteAssistantService = noteAssistantService;
        this.configService = configService;
        this.logService = logService;
        this.appConfig = appConfig;
    }

    @GetMapping
    public String chatPage(@RequestParam(required = false, defaultValue = "") String id,
                           @RequestParam(required = false, defaultValue = "knowledge") String mode,
                           Model model) {
        model.addAttribute("chatMode", mode);
        // 加载会话列表，避免模板中 #lists.size(sessions) 因 null 报错
        var data = sessionService.load();
        model.addAttribute("sessions", data.sessions);

        // 确定当前选中的会话 ID
        String currentId;
        if (!id.isEmpty() && !"new".equals(id)) {
            currentId = id;
        } else {
            currentId = data.current != null ? data.current : "";
        }
        model.addAttribute("current_id", currentId);
        model.addAttribute("current_mode", mode);

        // 加载当前会话（含消息列表）
        if (!currentId.isEmpty()) {
            ChatSession session = sessionService.getSession(currentId);
            if (session != null) {
                model.addAttribute("sessionId", session.getId());
                model.addAttribute("sessionTitle", session.getTitle());
                model.addAttribute("current", session);
            }
        }
        model.addAttribute("ai_provider", "direct");
        return "chat";
    }

    @GetMapping("/history")
    @ResponseBody
    public SessionsData history(@RequestParam(defaultValue = "knowledge") String mode) {
        return sessionService.load();
    }

    @GetMapping("/session/{id}")
    @ResponseBody
    public ChatSession getSession(@PathVariable String id) {
        return sessionService.getSession(id);
    }

    @DeleteMapping("/session/{id}")
    @ResponseBody
    public Map<String, Object> deleteSession(@PathVariable String id) {
        try {
            sessionService.deleteSession(id);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/send")
    @ResponseBody
    public SseEmitter send(@RequestParam String message,
                           @RequestParam(required = false, defaultValue = "") String sessionId,
                           @RequestParam(required = false, defaultValue = "knowledge") String mode) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String finalSessionId = (sessionId == null || sessionId.isEmpty()) ?
                sessionService.createSession("新对话", mode).getId() : sessionId;

        chatExecutor.execute(() -> {
            try {
                // 先发送 sessionId，让前端记住
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "session", "sessionId", finalSessionId))));

                sendStatus(emitter, mode, "正在处理...");

                sessionService.saveMessage(finalSessionId, "user", message, mode);

                // 构建上下文：历史 + 语义检索
                String context = sessionService.buildHistoryContext(finalSessionId, mode, message);
                StringBuilder fullText = new StringBuilder();
                if (context != null) {
                    fullText.append(context).append("\n\n---\n\n");
                }
                fullText.append("用户最新消息:\n").append(message);
                String fullMessage = fullText.toString();

                String replyText;
                if (!llmService.isAvailable()) {
                    throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
                }
                // 用 NoteAssistantService（含工具编排能力），AI 自动判断是否使用工具
                log.info("[chat] 使用 NoteAssistant（含工具编排）");
                replyText = noteAssistantService.chat(finalSessionId, message);

                log.info("[chat] 收到回复, 长度={}", replyText.length());
                sessionService.saveMessage(finalSessionId, "assistant", replyText, mode);
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

    @PostMapping("/clear")
    @ResponseBody
    public Map<String, Object> clearHistory(@RequestParam(defaultValue = "knowledge") String mode) {
        sessionService.clearSession(mode);
        return Map.of("ok", true);
    }

    @GetMapping("/export/{id}")
    @ResponseBody
    public Map<String, Object> exportSession(@PathVariable String id) {
        try {
            ChatSession session = sessionService.getSession(id);
            if (session == null) return Map.of("ok", false, "error", "会话不存在");
            String title = session.getTitle() != null ? session.getTitle() : "对话导出";
            String date = TimeUtil.todayStr();
            StringBuilder sb = new StringBuilder();
            sb.append("---\ntitle: ").append(title).append("\ndate: ").append(date).append("\n---\n\n");
            for (ChatMessage msg : session.getMessages()) {
                sb.append("**").append("user".equals(msg.getRole()) ? "👤 用户" : "🤖 AI").append("**\n");
                sb.append(msg.getContent()).append("\n\n");
            }
            return Map.of("ok", true, "title", title, "content", sb.toString(), "path",
                    "对话/" + title + "/" + date + ".md");
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ========== SSE 辅助方法 ==========

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
                emitter.complete();
            } catch (Exception ex) {
                log.error("无法完成发射器", ex);
            }
        }
    }

    private void sendError(SseEmitter emitter, String error) {
        try {
            Map<String, Object> data = Map.of("type", "error", "content", error);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送错误消息失败", e);
            try {
                emitter.completeWithError(new RuntimeException(error));
            } catch (Exception ex) {
                log.error("无法完成错误发送", ex);
            }
        }
    }
}
