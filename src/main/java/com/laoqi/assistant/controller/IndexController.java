package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class IndexController {

    private final ReportService reportService;
    private final LogService logService;
    private final KnowledgeBaseService kbService;

    public IndexController(ReportService reportService,
                            LogService logService,
                            KnowledgeBaseService kbService) {
        this.reportService = reportService;
        this.logService = logService;
        this.kbService = kbService;
    }

    @GetMapping("/")
    public String index() {
        // 首页重定向到聊天窗口
        var firstKb = kbService.getFirst();
        if (firstKb != null) {
            return "redirect:/chat?kbId=" + firstKb.getId();
        }
        return "redirect:/config";
    }

    @PostMapping("/generate")
    @ResponseBody
    public Map<String, Object> generate() {
        try {
            var r = reportService.generate();
            if (r.report != null) {
                reportService.saveComprehensiveReport(r.report);
                logService.add("手动生成日报", "成功");
                return Map.of("ok", true);
            } else {
                logService.add("手动生成日报", "失败", r.error);
                return Map.of("ok", false, "error", r.error != null ? r.error : "AI 分析不可用");
            }
        } catch (Exception e) {
            logService.add("手动生成日报", "失败", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/api/report/prompt")
    @ResponseBody
    public Map<String, Object> getPrompt() {
        return Map.of("ok", true, "prompt", reportService.readPrompt());
    }

    @PostMapping("/api/report/prompt")
    @ResponseBody
    public Map<String, Object> savePrompt(@RequestBody Map<String, String> body) {
        reportService.writePrompt(body.getOrDefault("prompt", ""));
        logService.add("综合日报", "保存提示词", "成功");
        return Map.of("ok", true);
    }
}
