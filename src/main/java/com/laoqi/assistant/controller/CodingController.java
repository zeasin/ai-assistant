package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.CodingRecordEntity;
import com.laoqi.assistant.service.CodePiService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.FeishuCodingBotService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.util.MarkdownUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

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
    private final ExecutorService debugExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "coding-debug");
        t.setDaemon(true);
        return t;
    });

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
        model.addAttribute("piStatus", codePiService.checkPiStatus());
        log.info("[编程AI] 页面加载: connected={}, records={}, dir={}",
                codingBotService.isConnected(), codingBotService.getRecentRecords(20).size(),
                config.getCodingProjectDir());
        return "1.0/coding";
    }

    @GetMapping("/records")
    @ResponseBody
    public Map<String, Object> getRecords(@RequestParam(defaultValue = "50") int limit) {
        var all = codingBotService.getAllRecords();
        int to = Math.min(limit, all.size());
        log.debug("[编程AI] 查询记录: limit={}, total={}, returned={}", limit, all.size(), to);
        return Map.of("ok", true, "records", all.subList(0, to).stream().map(CodingController::toDto).toList());
    }

    /**
     * 把 CodingRecordEntity 转成 Map，并预渲染 response 为 HTML（与笔记栏目一致，规避前端 marked 的转义问题）
     */
    private static Map<String, Object> toDto(CodingRecordEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("time", e.getTime());
        m.put("startTime", e.getStartTime());
        m.put("endTime", e.getEndTime());
        m.put("duration", e.getDuration());
        m.put("aiEngine", e.getAiEngine());
        m.put("message", e.getMessage());
        m.put("response", e.getResponse());
        m.put("responseHtml", MarkdownUtil.toHtml(e.getResponse()));
        m.put("elapsed", e.getElapsed());
        m.put("success", e.getSuccess());
        m.put("source", e.getSource());
        m.put("projectDir", e.getProjectDir());
        return m;
    }

    /**
     * 异步启动手动调试：
     * 1. 立即保存"处理中"记录到 SQLite
     * 2. 后台线程执行 pi CLI
     * 3. 完成后更新记录
     * 4. 前端轮询 /coding/debug/status/{id} 获取结果
     */
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

        log.info("[编程AI] 手动调试(异步): dir={}, msg={}...", projectDir,
                message.length() > 80 ? message.substring(0, 80) + "..." : message);

        // 1. 立即写入"处理中"记录
        Long recordId = codingBotService.saveRecord(
                message, "🔄 正在排查中...", "处理中", false, "debug", projectDir);
        log.info("[编程AI] 手动调试记录已写入: id={}", recordId);

        // 2. 后台执行 pi CLI（不超时限制）
        Long finalRecordId = recordId;
        String finalProjectDir = projectDir;
        debugExecutor.execute(() -> {
            try {
                log.info("[编程AI] 后台排查开始: recordId={}", finalRecordId);
                long startMs = System.currentTimeMillis();
                CodePiService.CodePiResult result = codePiService.analyze(message, finalProjectDir, 1800);
                long endMs = System.currentTimeMillis();

                log.info("[编程AI] 后台排查完成: recordId={}, success={}, elapsed={}",
                        finalRecordId, result.isSuccess(), result.getElapsedStr());

                // 3. 更新记录（含结束时间、用时秒数）
                CodingRecordEntity entity = codingBotService.getRecordById(finalRecordId);
                if (entity != null) {
                    String endTime = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    entity.setEndTime(endTime);
                    entity.setDuration((int) ((endMs - startMs) / 1000));
                    if (result.isSuccess()) {
                        String output = result.getOutput();
                        // 数据库存完整结果（不截断）
                        entity.setResponse(output);
                        entity.setSuccess(true);
                    } else {
                        String err = result.getError() != null ? result.getError() : "未知错误";
                        entity.setResponse("失败: " + err);
                        entity.setSuccess(false);
                    }
                    entity.setElapsed(result.getElapsedStr());
                    codingBotService.updateRecord(entity);
                    log.info("[编程AI] 记录已更新: id={}, success={}, duration={}s",
                            finalRecordId, entity.getSuccess(), entity.getDuration());
                }

                logService.add("编程AI调试", result.isSuccess() ? "成功" : "失败",
                        String.format("耗时%s | dir=%s | recordId=%d",
                                result.getElapsedStr(), finalProjectDir, finalRecordId));
            } catch (Exception e) {
                log.error("[编程AI] 后台排查异常: recordId={}, err={}", finalRecordId, e.getMessage(), e);
                CodingRecordEntity entity = codingBotService.getRecordById(finalRecordId);
                if (entity != null) {
                    entity.setEndTime(java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    entity.setResponse("异常: " + e.getMessage());
                    entity.setSuccess(false);
                    entity.setElapsed("失败");
                    codingBotService.updateRecord(entity);
                }
            }
        });

        // 4. 立即返回 recordId
        return Map.of("ok", true, "recordId", recordId, "message", "排查已启动，请稍候...");
    }

    @GetMapping("/debug/status/{recordId}")
    @ResponseBody
    public Map<String, Object> debugStatus(@PathVariable Long recordId) {
        CodingRecordEntity entity = codingBotService.getRecordById(recordId);
        if (entity == null) {
            return Map.of("ok", false, "error", "记录不存在");
        }
        boolean processing = "处理中".equals(entity.getElapsed());
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("done", !processing);
        result.put("success", entity.getSuccess());
        result.put("response", entity.getResponse());
        result.put("responseHtml", MarkdownUtil.toHtml(entity.getResponse()));
        result.put("elapsed", processing ? "" : entity.getElapsed());
        result.put("startTime", entity.getTime());
        result.put("duration", processing ? null : entity.getDuration());
        result.put("aiEngine", entity.getAiEngine());
        return result;
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