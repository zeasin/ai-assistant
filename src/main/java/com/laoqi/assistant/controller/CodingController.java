package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.CodePiService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.FeishuCodingBotService;
import com.laoqi.assistant.service.LogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 编程AI 独立页面控制器
 * 路径前缀: /coding
 */
@Controller
@RequestMapping("/coding")
public class CodingController {

    private final ConfigService configService;
    private final FeishuCodingBotService codingBotService;
    private final CodePiService codePiService;
    private final LogService logService;

    public CodingController(ConfigService configService,
                            FeishuCodingBotService codingBotService,
                            CodePiService codePiService,
                            LogService logService) {
        this.configService = configService;
        this.codingBotService = codingBotService;
        this.codePiService = codePiService;
        this.logService = logService;
    }

    @GetMapping
    public String codingPage(Model model) {
        model.addAttribute("config", configService.load());
        model.addAttribute("records", codingBotService.getRecentRecords(20));
        model.addAttribute("connected", codingBotService.isConnected());
        return "coding";
    }

    @GetMapping("/records")
    @ResponseBody
    public Map<String, Object> getRecords(@RequestParam(defaultValue = "50") int limit) {
        var all = codingBotService.getAllRecords();
        int to = Math.min(limit, all.size());
        return Map.of("ok", true, "records", all.subList(0, to));
    }

    @PostMapping("/debug")
    @ResponseBody
    public Map<String, Object> debug(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String projectDir = body.get("projectDir");
        if (message == null || message.trim().isEmpty()) {
            return Map.of("ok", false, "error", "message 必填");
        }
        if (projectDir == null || projectDir.trim().isEmpty()) {
            projectDir = configService.load().getCodingProjectDir();
        }
        if (projectDir == null || projectDir.trim().isEmpty()) {
            return Map.of("ok", false, "error", "请先配置项目目录");
        }

        int timeout = configService.load().getCodingPiTimeout() != null
                ? configService.load().getCodingPiTimeout() : 300;

        CodePiService.CodePiResult result = codePiService.analyze(message, projectDir, timeout);
        logService.add("编程AI调试", result.isSuccess() ? "成功" : "失败",
                String.format("耗时%s", result.getElapsedStr()));

        if (result.isSuccess()) {
            return Map.of("ok", true, "result", result.getOutput(), "elapsed", result.getElapsedStr());
        } else {
            return Map.of("ok", false, "error", result.getError(), "elapsed", result.getElapsedStr());
        }
    }

    @PostMapping("/test")
    @ResponseBody
    public Map<String, Object> testFeishu() {
        // 通过飞书发送测试消息
        var config = configService.load();
        String chatId = config.getCodingFeishuChatId();
        if (chatId == null || chatId.isEmpty()) {
            return Map.of("ok", false, "error", "请先配置群 Chat ID");
        }
        if (!codingBotService.isConnected()) {
            return Map.of("ok", false, "error", "编程机器人未连接，请检查 App ID/Secret 配置");
        }
        return Map.of("ok", true, "message", "测试消息已尝试发送，请检查飞书群");
    }
}