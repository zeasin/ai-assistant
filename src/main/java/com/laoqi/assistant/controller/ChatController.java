package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.model.TaskData.TaskItem;
import com.laoqi.assistant.service.*;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.db.SessionDbService;
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
import java.util.stream.Collectors;

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
    private static final int PAGE_SIZE = 60;

    private final KnowledgeBaseService kbService;
    private final SessionDbService sessionDbService;
    private final MessageDbService messageDbService;
    private final SessionService sessionService;
    private final LlmService llmService;
    private final NoteAssistantService noteAssistantService;
    private final LlmConfigResolver llmConfigResolver;
    private final LogService logService;
    private final TaskService taskService;
    private final AgentTraceService agentTraceService;

    public ChatController(KnowledgeBaseService kbService,
                          SessionDbService sessionDbService,
                          MessageDbService messageDbService,
                          SessionService sessionService,
                          LlmService llmService,
                          NoteAssistantService noteAssistantService,
                          LlmConfigResolver llmConfigResolver,
                          LogService logService,
                          TaskService taskService,
                          AgentTraceService agentTraceService) {
        this.kbService = kbService;
        this.sessionDbService = sessionDbService;
        this.messageDbService = messageDbService;
        this.sessionService = sessionService;
        this.llmService = llmService;
        this.noteAssistantService = noteAssistantService;
        this.llmConfigResolver = llmConfigResolver;
        this.logService = logService;
        this.taskService = taskService;
        this.agentTraceService = agentTraceService;
    }

    // ========== 页面路由 ==========

    @GetMapping
    public String chatPage(@RequestParam(required = false) Long kbId) {
        // 检查大模型是否已配置
        if (!llmService.isAvailable()) {
            return "redirect:/config#ai-model-section";
        }

        // 重定向到新版 /kb/{id}/chat 路由
        KnowledgeBaseEntity kb = null;
        if (kbId != null) {
            kb = kbService.getById(kbId);
        }
        if (kb == null) {
            kb = kbService.getFirst();
        }
        if (kb != null) {
            return "redirect:/kb/" + kb.getId() + "/chat";
        }
        return "redirect:/config";
    }

    // ========== API: 加载消息（分页） ==========

    @GetMapping("/api/kb/{kbId}/messages")
    @ResponseBody
    public Map<String, Object> kbMessages(@PathVariable Long kbId,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "60") int limit) {
        List<MessageEntity> msgs = messageDbService.listByKb(kbId.intValue(), offset, limit);
        long total = messageDbService.countByKb(kbId.intValue());

        // 翻转时间顺序（DB 返回 DESC，转成 ASC 给前端）
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            MessageEntity me = msgs.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", me.getRole());
            m.put("content", me.getContent());
            m.put("time", me.getCreatedAt());
            m.put("mode", me.getMode());
            messages.add(m);
        }

        return Map.of("ok", true, "messages", messages, "total", total, "offset", offset);
    }

    // ========== API: 搜索消息 ==========

    @GetMapping("/api/kb/{kbId}/search")
    @ResponseBody
    public Map<String, Object> searchMessages(@PathVariable Long kbId,
                                               @RequestParam String q,
                                               @RequestParam(defaultValue = "30") int limit) {
        if (q == null || q.isBlank()) {
            return Map.of("ok", true, "messages", List.of());
        }
        List<MessageEntity> msgs = messageDbService.searchByKb(kbId.intValue(), q, limit);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (MessageEntity me : msgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", me.getRole());
            m.put("content", me.getContent());
            m.put("time", me.getCreatedAt());
            m.put("mode", me.getMode());
            messages.add(m);
        }
        return Map.of("ok", true, "messages", messages, "query", q);
    }

    // ========== API: 发送消息（SSE 流式） ==========

    @PostMapping("/send")
    @ResponseBody
    public SseEmitter send(@RequestParam String message,
                           @RequestParam(required = false) Long kbId,
                           @RequestParam(required = false, defaultValue = "knowledge") String mode,
                           @RequestParam(required = false, defaultValue = "") String modelName) {
        SseEmitter emitter = new SseEmitter(300_000L);

        // 确定 KB
        Long resolvedKbId = kbId;
        if (resolvedKbId == null) {
            KnowledgeBaseEntity first = kbService.getFirst();
            if (first != null) resolvedKbId = first.getId();
        }
        if (resolvedKbId == null) {
            try {
                sendError(emitter, "未配置任何知识库，请先到配置页设置");
            } catch (Exception ignored) {}
            return emitter;
        }
        final Long finalKbId = resolvedKbId;

        // 获取或创建会话
        String sessionId = getOrCreateKbSession(finalKbId, mode);

        // 客户端断开/超时标志，避免往已完成的 emitter 继续发数据
        final boolean[] emitterDone = {false};
        emitter.onCompletion(() -> emitterDone[0] = true);
        emitter.onTimeout(() -> emitterDone[0] = true);
        emitter.onError(e -> emitterDone[0] = true);

        chatExecutor.execute(() -> {
            try {
                if (emitterDone[0]) return;
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "session", "sessionId", sessionId))));

                if (emitterDone[0]) return;
                sendStatus(emitter, mode, "正在处理...");

                sessionService.saveMessage(sessionId, "user", message, mode, "web");

                if (!llmService.isAvailable()) {
                    throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
                }

                log.info("[chat] KB={} 使用 NoteAssistant（含工具编排 + 历史上下文注入, model={})",
                        finalKbId, modelName.isEmpty() ? "default" : modelName);

                // 启动心跳：每 5 秒发送一次 keepalive
                final boolean[] heartbeatDone = {false};
                Thread heartbeat = new Thread(() -> {
                    while (!heartbeatDone[0] && !emitterDone[0]) {
                        try {
                            Thread.sleep(5000);
                            if (!heartbeatDone[0] && !emitterDone[0]) {
                                emitter.send(SseEmitter.event()
                                        .data(mapper.writeValueAsString(Map.of("type", "heartbeat"))));
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            break;
                        }
                    }
                }, "chat-heartbeat");
                heartbeat.setDaemon(true);
                heartbeat.start();

                // 兜底：AI 流式期间无新状态时，按时间递进显示进度
                final boolean[] firstChunkArrived = {false};
                final long[] lastStatusTime = {System.currentTimeMillis()};
                Thread thinkingStatus = new Thread(() -> {
                    while (!firstChunkArrived[0] && !emitterDone[0]) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                        if (!firstChunkArrived[0] && !emitterDone[0]) {
                            long elapsed = System.currentTimeMillis() - lastStatusTime[0];
                            if (elapsed >= 3000) {
                                String msg;
                                if (elapsed < 6000) {
                                    msg = "⏳ 正在分析获取到的信息...";
                                } else if (elapsed < 10000) {
                                    msg = "✍️ AI 正在组织回复...";
                                } else if (elapsed < 15000) {
                                    msg = "📝 即将完成...";
                                } else {
                                    msg = "⏳ 处理中，请稍候...";
                                }
                                log.info("[chat] 兜底状态: {}", msg);
                                sendStatus(emitter, mode, msg);
                            }
                        }
                    }
                }, "chat-thinking-status");
                thinkingStatus.setDaemon(true);
                thinkingStatus.start();

                StringBuilder replyBuffer = new StringBuilder();
                noteAssistantService.streamChat(sessionId, message, mode, finalKbId, modelName, chunk -> {
                    if (emitterDone[0]) return;
                    firstChunkArrived[0] = true;
                    replyBuffer.append(chunk);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "text");
                    data.put("content", chunk);
                    data.put("mode", mode);
                    try {
                        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
                    } catch (Exception e) {
                        log.warn("发送流式数据失败", e);
                        emitterDone[0] = true;
                    }
                }, status -> {
                    if (!emitterDone[0]) {
                        lastStatusTime[0] = System.currentTimeMillis();
                        log.info("[chat] 状态更新: {}", status);
                        sendStatus(emitter, mode, status);
                    }
                });

                heartbeatDone[0] = true;
                if (emitterDone[0]) return;
                String replyText = replyBuffer.toString();
                log.info("[chat] 收到回复, 长度={}", replyText.length());
                sessionService.saveMessage(sessionId, "assistant", replyText, mode, "web");
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

    // ========== API: 清空对话 ==========

    @DeleteMapping("/api/kb/{kbId}/clear")
    @ResponseBody
    public Map<String, Object> clearKbMessages(@PathVariable Long kbId) {
        List<SessionEntity> sessions = sessionDbService.listByKb(kbId.intValue());
        for (SessionEntity se : sessions) {
            sessionService.deleteSession(se.getId());
        }
        logService.add("对话", "清空", "清空知识库(KB=" + kbId + ")的聊天记录");
        return Map.of("ok", true);
    }

    // ========== API: 导出对话 ==========

    @GetMapping("/api/kb/{kbId}/export")
    @ResponseBody
    public Map<String, Object> exportKbMessages(@PathVariable Long kbId) {
        List<MessageEntity> msgs = messageDbService.listByKb(kbId.intValue(), 0, 99999);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (MessageEntity me : msgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", me.getRole());
            m.put("content", me.getContent());
            m.put("time", me.getCreatedAt());
            messages.add(m);
        }

        KnowledgeBaseEntity kb = kbService.getById(kbId);
        String title = (kb != null ? kb.getName() : "知识库") + "对话导出";
        String date = TimeUtil.todayStr();
        StringBuilder sb = new StringBuilder();
        sb.append("---\ntitle: ").append(title).append("\ndate: ").append(date).append("\n---\n\n");

        // 翻转时间顺序（DB 返回降序，导出时按时间升序）
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            sb.append("**").append("user".equals(m.get("role")) ? "👤 用户" : "🤖 AI").append("**\n");
            sb.append(m.get("content")).append("\n\n");
        }

        return Map.of("ok", true, "title", title, "content", sb.toString());
    }

    // ========== 辅助方法 ==========

    private String getOrCreateKbSession(Long kbId, String mode) {
        SessionEntity latest = sessionDbService.findLatestByKb(kbId.intValue());
        if (latest != null) return latest.getId();

        String id = UUID.randomUUID().toString().substring(0, 12);
        String now = TimeUtil.nowStr();
        SessionEntity se = new SessionEntity();
        se.setId(id);
        se.setSource("web");
        se.setTitle("连续对话");
        se.setMode(mode != null ? mode : "knowledge");
        se.setKbId(kbId.intValue());
        se.setCreatedAt(now);
        se.setUpdatedAt(now);
        sessionDbService.save(se);
        return id;
    }

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

    private void sendDone(SseEmitter emitter, String mode) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "done");
            data.put("mode", mode);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送完成消息失败", e);
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    private void sendError(SseEmitter emitter, String error) {
        try {
            Map<String, Object> data = Map.of("type", "error", "content", error);
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送错误消息失败", e);
            try { emitter.completeWithError(new RuntimeException(error)); } catch (Exception ignored) {}
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

    // ========== Agent 决策追踪 API ==========

    @GetMapping("/api/trace/{sessionId}")
    @ResponseBody
    public Map<String, Object> getTrace(@PathVariable String sessionId) {
        var steps = agentTraceService.getTrace(sessionId);
        var formatted = agentTraceService.formatTrace(sessionId);
        return Map.of("ok", true, "steps", steps, "formatted", formatted);
    }
}
