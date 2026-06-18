package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.model.TaskData.TaskItem;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LlmConfigResolver;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class IndexController {

    private final ReportService reportService;
    private final TaskService taskService;
    private final LogService logService;
    private final ConfigService configService;
    private final LlmConfigResolver llmConfigResolver;

    public IndexController(ReportService reportService, TaskService taskService,
                            LogService logService, ConfigService configService,
                            LlmConfigResolver llmConfigResolver) {
        this.reportService = reportService;
        this.taskService = taskService;
        this.logService = logService;
        this.configService = configService;
        this.llmConfigResolver = llmConfigResolver;
    }

    @GetMapping("/")
    public String index(Model model) {
        String todayReport = reportService.readTodayReport();
        String reportTime;
        String error;

        if (todayReport != null) {
            model.addAttribute("report", MarkdownUtil.toHtml(todayReport));
            model.addAttribute("report_time", reportService.getLatestReportTime().isEmpty() ? "今日已生成" : reportService.getLatestReportTime());
            model.addAttribute("report_error", "");
        } else {
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

        List<TaskItem> activeTasks = taskService.getAllTasks().stream()
                .filter(t -> !"done".equals(t.status))
                .collect(Collectors.toList());
        model.addAttribute("todoHigh", activeTasks.stream().filter(t -> "high".equals(t.priority)).collect(Collectors.toList()));
        model.addAttribute("todoMid", activeTasks.stream().filter(t -> "mid".equals(t.priority)).collect(Collectors.toList()));
        model.addAttribute("todoLow", activeTasks.stream().filter(t -> "low".equals(t.priority)).collect(Collectors.toList()));
        model.addAttribute("todoTotal", activeTasks.size());

        LlmProfileEntity defaultLlm = llmConfigResolver.getDefaultProfile();
        if (defaultLlm != null && defaultLlm.getApiKey() != null && !defaultLlm.getApiKey().isEmpty()) {
            model.addAttribute("llmConfigured", true);
            model.addAttribute("llmName", defaultLlm.getName());
            model.addAttribute("llmModel", defaultLlm.getModel());
        } else {
            model.addAttribute("llmConfigured", false);
        }

        try {
            String notesDir = configService.getNotesDir();
            model.addAttribute("notesDirConfigured", true);
            model.addAttribute("notesDir", notesDir);
        } catch (Exception e) {
            model.addAttribute("notesDirConfigured", false);
        }

        return "index";
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
