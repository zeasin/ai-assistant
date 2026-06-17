package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class AiProxyController {

    private static final Logger log = LoggerFactory.getLogger(AiProxyController.class);
    private static final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-proxy-async");
        t.setDaemon(true);
        return t;
    });

    private final OpenCodeService openCodeService;
    private final LlmService llmService;
    private final ConfigService configService;
    private final LogService logService;

    public AiProxyController(OpenCodeService openCodeService, LlmService llmService,
                             ConfigService configService, LogService logService) {
        this.openCodeService = openCodeService;
        this.llmService = llmService;
        this.configService = configService;
        this.logService = logService;
    }

    private boolean isDirectMode() {
        return "direct".equals(configService.load().getAiProvider());
    }

    private String doAiChat(String message) throws Exception {
        if (isDirectMode()) {
            if (!llmService.isAvailable()) {
                return "⚠️ LLM API Key 未配置，请在配置页填写";
            }
            return llmService.chat("你是一个助手，帮助用户填写页面内容。请用中文回复。", message);
        } else {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动";
            }
            String sessionId = openCodeService.createSession("页面录入");
            return openCodeService.sendMessage(sessionId, message);
        }
    }

    @PostMapping("/api/ai/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String message) {
        try {
            String reply = doAiChat(message);
            logService.add("AI录入", "完成", message);
            return Map.of("ok", true, "reply", reply);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/ai/send-async")
    @ResponseBody
    public Map<String, Object> sendAsync(@RequestParam String message) {
        try {
            asyncExecutor.execute(() -> {
                try {
                    String reply = doAiChat(message);
                    log.info("[AI录入-异步] 处理完成: message={}, reply={}", message, reply);
                    logService.add("AI录入", "完成", message + " => " + reply);
                } catch (Exception e) {
                    log.error("[AI录入-异步] 处理失败: {}", e.getMessage(), e);
                    logService.add("AI录入", "失败", message + " => " + e.getMessage());
                }
            });

            return Map.of("ok", true, "async", true, "reply", "已提交，AI 后台处理中");
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
