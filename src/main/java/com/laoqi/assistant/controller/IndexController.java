package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TodoService;
import com.laoqi.assistant.util.MarkdownUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class IndexController {

    private final ReportService reportService;
    private final TodoService todoService;
    private final LogService logService;

    // In-memory report cache (same as V1)
    private String cachedReport = "";
    private String cachedTime = "";
    private String cachedError = "";

    public IndexController(ReportService reportService, TodoService todoService,
                            LogService logService) {
        this.reportService = reportService;
        this.todoService = todoService;
        this.logService = logService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("report", MarkdownUtil.toHtml(cachedReport));
        model.addAttribute("report_time", cachedTime.isEmpty() ? "尚未生成" : cachedTime);
        model.addAttribute("report_error", cachedError);
        model.addAttribute("todos", todoService.parse());
        return "index";
    }

    @PostMapping("/generate")
    @ResponseBody
    public Map<String, Object> generate() {
        try {
            var r = reportService.generate();
            if (r.report != null) {
                cachedReport = r.report;
                cachedTime = com.laoqi.assistant.util.TimeUtil.nowStr();
                cachedError = "";

                // Push to Feishu
                String today = com.laoqi.assistant.util.TimeUtil.todayStr();
                String wd = com.laoqi.assistant.util.TimeUtil.weekdayCn(com.laoqi.assistant.util.TimeUtil.now());
                String title = "🌅 老齐早安 · " + today + " · " + wd;
                reportService.saveComprehensiveReport(r.report);
                todoService.clearTempReminders();
                logService.add("手动生成日报", "成功");

                return Map.of("ok", true);
            } else {
                cachedError = r.error != null ? r.error : "AI 分析不可用";
                logService.add("手动生成日报", "失败", r.error);
                return Map.of("ok", false, "error", r.error != null ? r.error : "AI 分析不可用");
            }
        } catch (Exception e) {
            cachedError = e.getMessage();
            logService.add("手动生成日报", "失败", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}