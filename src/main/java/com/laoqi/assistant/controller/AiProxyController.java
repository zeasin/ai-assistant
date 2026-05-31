package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.LogService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AiProxyController {

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
}
