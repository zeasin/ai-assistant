package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TodoService;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class IndexController {

    private final ReportService reportService;
    private final TodoService todoService;
    private final LogService logService;

    public IndexController(ReportService reportService, TodoService todoService,
                            LogService logService) {
        this.reportService = reportService;
        this.todoService = todoService;
        this.logService = logService;
    }

    @GetMapping("/")
    public String index(Model model) {
        // 优先从笔记库读取今日综合日报文件
        String todayReport = reportService.readTodayReport();
        String reportTime;
        String error;

        if (todayReport != null) {
            // 文件存在，使用文件内容
            model.addAttribute("report", MarkdownUtil.toHtml(todayReport));
            model.addAttribute("report_time", reportService.getLatestReportTime().isEmpty() ? "今日已生成" : reportService.getLatestReportTime());
            model.addAttribute("report_error", "");
        } else {
            // 文件不存在，回退到内存中的生成结果
            String report = reportService.getLatestReport();
            String rt = reportService.getLatestReportTime();
            String err = reportService.getLatestError();
            if (report != null && !report.isEmpty()) {
                model.addAttribute("report", MarkdownUtil.toHtml(report));
            } else {
                model.addAttribute("report", "");
            }
            model.addAttribute("report_time", rt.isEmpty() ? "尚未生成" : rt);
            model.addAttribute("report_error", err);
        }

        model.addAttribute("todos", todoService.parse());
        return "index";
    }

    @PostMapping("/generate")
    @ResponseBody
    public Map<String, Object> generate() {
        try {
            var r = reportService.generate();
            if (r.report != null) {
                String today = TimeUtil.todayStr();
                String wd = TimeUtil.weekdayCn(TimeUtil.now());
                String title = TimeUtil.greetingEmoji() + " 老齐" + TimeUtil.greetingText() + " · " + today + " · " + wd;
                reportService.saveComprehensiveReport(r.report);
                todoService.clearTempReminders();
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
}
