package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AiProxyController {

    private static final Logger log = LoggerFactory.getLogger(AiProxyController.class);

    private final OpenCodeService openCodeService;
    private final LogService logService;
    private String sessionId;

    public AiProxyController(OpenCodeService openCodeService, LogService logService) {
        this.openCodeService = openCodeService;
        this.logService = logService;
    }

    @PostMapping("/api/ai/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String message) {
        try {
            if (!openCodeService.isHealthy()) {
                return Map.of("ok", false, "error", "opencode serve 未启动");
            }
            if (sessionId == null) {
                sessionId = openCodeService.createSession("页面录入");
            }
            String reply = openCodeService.sendMessage(sessionId, message);
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
            if (!openCodeService.isHealthy()) {
                return Map.of("ok", false, "error", "opencode serve 未启动");
            }
            String sid = sessionId;
            if (sid == null) {
                sid = openCodeService.createSession("页面录入");
                sessionId = sid;
            }
            String finalSessionId = sid;

            new Thread(() -> {
                try {
                    String reply = openCodeService.sendMessage(finalSessionId, message);
                    log.info("[AI录入-异步] 处理完成: message={}, reply={}", message, reply);
                    logService.add("AI录入", "完成", message + " => " + reply);
                } catch (Exception e) {
                    log.error("[AI录入-异步] 处理失败: {}", e.getMessage(), e);
                    logService.add("AI录入", "失败", message + " => " + e.getMessage());
                }
            }).start();

            return Map.of("ok", true, "async", true, "reply", "已提交，AI 后台处理中");
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
