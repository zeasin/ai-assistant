package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.*;
import com.laoqi.assistant.util.MarkdownUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class IndexController {

    private final KnowledgeBaseService kbService;
    private final TaskService taskService;
    private final ReminderService reminderService;
    private final ReportService reportService;
    private final LlmService llmService;
    private final LlmConfigResolver llmConfigResolver;

    public IndexController(KnowledgeBaseService kbService,
                           TaskService taskService,
                           ReminderService reminderService,
                           ReportService reportService,
                           LlmService llmService,
                           LlmConfigResolver llmConfigResolver) {
        this.kbService = kbService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
        this.llmService = llmService;
        this.llmConfigResolver = llmConfigResolver;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<KnowledgeBaseEntity> allKbs = kbService.getAll();

        // 系统状态
        model.addAttribute("llmConfigured", llmService.isAvailable());
        var defaultProfile = llmConfigResolver.getDefaultProfile();
        model.addAttribute("llmName", defaultProfile != null ? defaultProfile.getName() : "");
        model.addAttribute("llmModel", defaultProfile != null ? defaultProfile.getModel() : "");
        model.addAttribute("kbCount", allKbs.size());

        // 各KB的日报和任务
        List<Map<String, Object>> kbSummaries = new ArrayList<>();
        int totalTasks = 0;
        int totalReminders = 0;

        for (KnowledgeBaseEntity kb : allKbs) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", kb.getId());
            summary.put("name", kb.getName());

            // 任务统计
            try {
                long taskCount = taskService.getAllTasks(kb.getNotesDir()).stream()
                        .filter(t -> !"done".equals(t.status))
                        .count();
                summary.put("taskCount", (int) taskCount);
                totalTasks += (int) taskCount;
            } catch (Exception e) {
                summary.put("taskCount", 0);
            }

            // 提醒统计
            try {
                int reminderCount = reminderService.getAllReminders(kb.getNotesDir()).size();
                summary.put("reminderCount", reminderCount);
                totalReminders += reminderCount;
            } catch (Exception e) {
                summary.put("reminderCount", 0);
            }

            // 日报
            try {
                String notesDir = kb.getNotesDir();
                if (notesDir != null && !notesDir.isBlank()) {
                    String report = reportService.readLatestReport(notesDir);
                    String reportDate = reportService.getLatestReportDate(notesDir);
                    summary.put("hasReport", report != null && !report.isEmpty());
                    summary.put("reportHtml", report != null ? MarkdownUtil.toHtml(report) : "");
                    summary.put("reportDate", reportDate);
                } else {
                    summary.put("hasReport", false);
                    summary.put("reportHtml", "");
                    summary.put("reportDate", null);
                }
            } catch (Exception e) {
                summary.put("hasReport", false);
                summary.put("reportHtml", "");
                summary.put("reportDate", null);
            }

            kbSummaries.add(summary);
        }

        model.addAttribute("kbSummaries", kbSummaries);
        model.addAttribute("totalTasks", totalTasks);
        model.addAttribute("totalReminders", totalReminders);

        return "index";
    }
}
