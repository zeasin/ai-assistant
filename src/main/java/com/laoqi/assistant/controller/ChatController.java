package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.service.ChatSessionService;
import com.laoqi.assistant.service.ChatSessionService.SessionsData;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LlmConfigResolver;
import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.NoteAssistantService;
import com.laoqi.assistant.util.TimeUtil;
import java.util.List;
import java.util.stream.Collectors;
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
    private final LlmConfigResolver llmConfigResolver;

    public ChatController(ChatSessionService sessionService,
                          LlmService llmService, NoteAssistantService noteAssistantService,
                          ConfigService configService,
                          LogService logService, AppConfig appConfig,
                          LlmConfigResolver llmConfigResolver) {
        this.sessionService = sessionService;
        this.llmService = llmService;
        this.noteAssistantService = noteAssistantService;
        this.configService = configService;
        this.logService = logService;
        this.appConfig = appConfig;
        this.llmConfigResolver = llmConfigResolver;
    }

    @GetMapping
    public String chatPage(@RequestParam(required = false, defaultValue = "") String id,
                           @RequestParam(required = false, defaultValue = "knowledge") String mode,
                           Model model) {
        model.addAttribute("chatMode", mode);
        var data = sessionService.load();

        // 用户明确要求新对话
        if ("new".equals(id)) {
            ChatSession newSession = sessionService.createSession("新对话", mode);
            return "redirect:/chat?id=" + newSession.getId() + "&mode=" + mode;
        }

        // 如果没有任何会话，自动创建一个
        if (data.sessions.isEmpty()) {
            ChatSession newSession = sessionService.createSession("新对话", mode);
            return "redirect:/chat?id=" + newSession.getId() + "&mode=" + mode;
        }

        // 有会话但 URL 没有 id 参数，跳转到最近的会话
        if (id.isEmpty() && data.current != null) {
            return "redirect:/chat?id=" + data.current + "&mode=" + mode;
        }

        model.addAttribute("sessions", data.sessions);

        // 确定当前选中的会话 ID：优先使用 URL 参数，否则使用最近的会话
        String currentId = !id.isEmpty() ? id : data.current;
        model.addAttribute("current_id", currentId);
        model.addAttribute("current_mode", mode);

        // 加载当前会话（含消息列表）
        ChatSession session = sessionService.getSession(currentId);
        if (session != null) {
            model.addAttribute("sessionId", session.getId());
            model.addAttribute("sessionTitle", session.getTitle());
            model.addAttribute("current", session);
        }
        model.addAttribute("ai_provider", "direct");
        // 聊天页面只显示文本模型和多模态模型，不显示向量模型
        List<LlmProfileEntity> chatModels = llmConfigResolver.getAllProfiles()
                .stream()
                .filter(p -> !LlmProfileEntity.TYPE_EMBEDDING.equals(p.getModelType()))
                .collect(Collectors.toList());
        model.addAttribute("chat_models", chatModels);
        LlmProfileEntity defaultProfile = llmConfigResolver.getDefaultProfile();
        model.addAttribute("default_model", defaultProfile != null ? defaultProfile.getName() : "");
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
                           @RequestParam(required = false, defaultValue = "knowledge") String mode,
                           @RequestParam(required = false, defaultValue = "") String modelName) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String finalSessionId;
        if (sessionId == null || sessionId.isEmpty()) {
            var data = sessionService.load();
            if (data.current != null && !data.current.isEmpty()) {
                finalSessionId = data.current;
            } else {
                finalSessionId = sessionService.createSession("新对话", mode).getId();
            }
        } else {
            finalSessionId = sessionId;
        }

        chatExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "session", "sessionId", finalSessionId))));

                sendStatus(emitter, mode, "正在处理...");

                sessionService.saveMessage(finalSessionId, "user", message, mode);

                if (!llmService.isAvailable()) {
                    throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
                }

                log.info("[chat] 使用 NoteAssistant（含工具编排 + 历史上下文注入, model={})",
                        modelName.isEmpty() ? "default" : modelName);

                StringBuilder replyBuffer = new StringBuilder();
                String replyText = noteAssistantService.streamChat(finalSessionId, message, mode, modelName, chunk -> {
                    replyBuffer.append(chunk);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "text");
                    data.put("content", chunk);
                    data.put("mode", mode);
                    try {
                        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
                    } catch (Exception e) {
                        log.warn("发送流式数据失败", e);
                    }
                });

                log.info("[chat] 收到回复, 长度={}", replyText.length());
                sessionService.saveMessage(finalSessionId, "assistant", replyText, mode);
                sendDone(emitter, mode);

            } catch (Exception e) {
                log.error("对话请求处理失败", e);
                try {
                    sendError(emitter, resolveErrorMessage(e));
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "status");
            data.put("content", text);
            data.put("mode", mode);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("发送状态消息失败", e);
        }
    }

    private void sendText(SseEmitter emitter, String mode, String text) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "text");
            data.put("content", text);
            data.put("mode", mode);
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "done");
            data.put("mode", mode);
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

    private String resolveErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg != null) {
            if (msg.contains("Insufficient Balance") || msg.contains("insufficient_balance")) {
                return "API 余额不足，请登录 DeepSeek 平台充值后重试。";
            }
            if (msg.contains("Rate limit") || msg.contains("rate_limit")) {
                return "请求过于频繁，请稍后再试。";
            }
            if (msg.contains("invalid_api_key") || msg.contains("Incorrect API key")) {
                return "API Key 无效，请在配置页检查并更新。";
            }
            if (msg.contains("context_length_exceeded") || msg.contains("maximum context length")) {
                return "对话过长，请开启新对话。";
            }
            // 尝试提取 API 返回的 error message
            int idx = msg.indexOf("\"message\":\"");
            if (idx != -1) {
                int start = idx + "\"message\":\"".length();
                int end = msg.indexOf("\"", start);
                if (end != -1) {
                    return "AI 服务错误: " + msg.substring(start, end);
                }
            }
        }
        return "AI 服务调用失败: " + (msg != null ? msg : "未知错误");
    }
}
