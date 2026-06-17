package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.CodePiService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.FeishuCodingBotService;
import com.laoqi.assistant.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CodingController.class);

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
        var config = configService.load();
        model.addAttribute("config", config);
        model.addAttribute("records", codingBotService.getRecentRecords(20));
        model.addAttribute("connected", codingBotService.isConnected());
        log.info("[编程AI] 页面加载: connected={}, records={}, dir={}",
                codingBotService.isConnected(), codingBotService.getRecentRecords(20).size(),
                config.getCodingProjectDir());
        return "coding";
    }

    @GetMapping("/records")
    @ResponseBody
    public Map<String, Object> getRecords(@RequestParam(defaultValue = "50") int limit) {
        var all = codingBotService.getAllRecords();
        int to = Math.min(limit, all.size());
        log.debug("[编程AI] 查询记录: limit={}, total={}, returned={}", limit, all.size(), to);
        return Map.of("ok", true, "records", all.subList(0, to));
    }

    @PostMapping("/debug")
    @ResponseBody
    public Map<String, Object> debug(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String projectDir = body.get("projectDir");
        if (message == null || message.trim().isEmpty()) {
            log.warn("[编程AI] 手动调试: message 为空");
            return Map.of("ok", false, "error", "message 必填");
        }
        if (projectDir == null || projectDir.trim().isEmpty()) {
            projectDir = configService.load().getCodingProjectDir();
        }
        if (projectDir == null || projectDir.trim().isEmpty()) {
            log.warn("[编程AI] 手动调试: 项目目录未配置");
            return Map.of("ok", false, "error", "请先配置项目目录");
        }

        log.info("[编程AI] 手动调试: dir={}, msg={}...", projectDir,
                message.length() > 80 ? message.substring(0, 80) + "..." : message);

        // 手动调试不设超时限制（用 1800s = 30分钟）
        CodePiService.CodePiResult result = codePiService.analyze(message, projectDir, 1800);
        log.info("[编程AI] 手动调试完成: success={}, elapsed={}, output_len={}",
                result.isSuccess(), result.getElapsedStr(),
                result.isSuccess() ? result.getOutput().length() : 0);

        logService.add("编程AI调试", result.isSuccess() ? "成功" : "失败",
                String.format("耗时%s | dir=%s", result.getElapsedStr(), projectDir));

        // 手动调试也写入 SQLite 记录
        String source = "debug";
        if (result.isSuccess()) {
            String output = result.getOutput();
            if (output.length() > 5000) output = output.substring(0, 5000) + "\n\n...（截断）";
            codingBotService.saveRecord(message, output, result.getElapsedStr(), true, source, projectDir);
            log.info("[编程AI] 手动调试成功记录已保存");
            return Map.of("ok", true, "result", result.getOutput(), "elapsed", result.getElapsedStr());
        } else {
            String errorMsg = result.getError() != null ? result.getError() : "未知错误";
            codingBotService.saveRecord(message, "失败: " + errorMsg, result.getElapsedStr(), false, source, projectDir);
            log.info("[编程AI] 手动调试失败记录已保存");
            return Map.of("ok", false, "error", errorMsg, "elapsed", result.getElapsedStr());
        }
    }

    @PostMapping("/test")
    @ResponseBody
    public Map<String, Object> testFeishu() {
        var config = configService.load();
        String chatId = config.getCodingFeishuChatId();
        if (chatId == null || chatId.isEmpty()) {
            log.warn("[编程AI] 测试消息: 群 Chat ID 未配置");
            return Map.of("ok", false, "error", "请先配置群 Chat ID");
        }
        String appId = config.getCodingFeishuAppId();
        String appSecret = config.getCodingFeishuAppSecret();
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            log.warn("[编程AI] 测试消息: App ID/Secret 未配置");
            return Map.of("ok", false, "error", "请先配置 App ID 和 App Secret");
        }

        log.info("[编程AI] 测试消息: chatId={}, appId={}...", chatId,
                appId.length() > 8 ? appId.substring(0, 8) + "..." : appId);

        // 如果 WebSocket 未连接，尝试启动
        if (!codingBotService.isConnected()) {
            log.info("[编程AI] 测试消息: WebSocket 未连接，尝试启动");
            try {
                codingBotService.startLongConnection(appId, appSecret);
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("[编程AI] 测试消息: 启动失败: {}", e.getMessage());
                return Map.of("ok", false, "error", "启动编程机器人失败: " + e.getMessage());
            }
        }

        if (!codingBotService.isConnected()) {
            log.warn("[编程AI] 测试消息: WebSocket 仍在启动中");
            return Map.of("ok", false, "error", "编程机器人正在启动连接中，请稍后重试");
        }

        // 通过飞书 API 发送测试消息到群聊
        try {
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            codingBotService.sendTestMessage(chatId,
                    "✅ 编程AI 连接测试成功！\n\n编程机器人已就绪，现在可以在群中 @我 并描述你遇到的代码问题，我将自动排查。\n\n⏰ 当前时间: " + now);
            logService.add("编程AI测试", "成功", "已向群 " + chatId + " 发送测试消息");
            log.info("[编程AI] ✅ 测试消息发送成功: chatId={}", chatId);
            return Map.of("ok", true, "message", "测试消息已发送，请检查飞书群");
        } catch (Exception e) {
            log.error("[编程AI] ❌ 测试消息发送失败: {}", e.getMessage());
            logService.add("编程AI测试", "失败", e.getMessage());
            return Map.of("ok", false, "error", "发送测试消息失败: " + e.getMessage());
        }
    }
}