package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.util.MarkdownUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class KnowledgeBaseController {

    private static final TypeReference<Map<String, String>> LABELS_TYPE = new TypeReference<>() {};

    private final KnowledgeBaseService kbService;
    private final ModuleService moduleService;
    private final LogService logService;
    private final TaskService taskService;
    private final ReminderService reminderService;
    private final ReportService reportService;

    public KnowledgeBaseController(KnowledgeBaseService kbService, ModuleService moduleService,
                                   LogService logService, TaskService taskService,
                                   ReminderService reminderService, ReportService reportService) {
        this.kbService = kbService;
        this.moduleService = moduleService;
        this.logService = logService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
    }

    // ========== 页面路由 ==========

    @GetMapping("/kb/{id}")
    public String overview(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("modules", moduleService.getModulesByKb(id));

        long activeTaskCount = taskService.getAllTasks().stream()
                .filter(t -> !"done".equals(t.status))
                .count();
        model.put("taskCount", activeTaskCount);
        model.put("reminderCount", reminderService.getAllReminders().size());

        String todayReport = reportService.readTodayReport();
        if (todayReport != null) {
            model.put("report", MarkdownUtil.toHtml(todayReport));
            model.put("report_time", reportService.getLatestReportTime().isEmpty() ? "今日已生成" : reportService.getLatestReportTime());
            model.put("report_error", "");
        } else {
            String report = reportService.getLatestReport();
            String rt = reportService.getLatestReportTime();
            String err = reportService.getLatestError();
            if (report != null && !report.isEmpty()) {
                model.put("report", MarkdownUtil.toHtml(report));
            } else {
                model.put("report", "");
            }
            model.put("report_time", rt.isEmpty() ? "尚未生成" : rt);
            model.put("report_error", err);
        }

        return "kb_overview";
    }

    @GetMapping("/kb/{id}/tasks")
    public String tasks(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "tasks";
    }

    @GetMapping("/kb/{id}/reminders")
    public String reminders(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "reminders";
    }

    @GetMapping("/kb/{id}/modules")
    public String modulesPage(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("kbModules", moduleService.getModulesByKb(id));
        return "kb_modules";
    }

    @GetMapping("/kb/{id}/notes")
    public String notes(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "browse";
    }

    @GetMapping("/kb/{id}/config")
    public String config(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "ai_guide";
    }

    // ========== REST API ==========

    @ResponseBody
    @GetMapping("/api/kb/list")
    public Map<String, Object> list() {
        List<KnowledgeBaseEntity> list = kbService.getAll();
        List<Map<String, Object>> result = list.stream().map(this::toMap).collect(Collectors.toList());
        return Map.of("ok", true, "list", result);
    }

    @ResponseBody
    @GetMapping("/api/kb/current")
    public Map<String, Object> current() {
        KnowledgeBaseEntity kb = kbService.getActiveKb();
        if (kb == null) {
            kb = kbService.getFirst();
        }
        if (kb == null) {
            return Map.of("ok", false, "error", "未配置任何知识库");
        }
        return Map.of("ok", true, "kb", toMap(kb));
    }

    @ResponseBody
    @PostMapping("/api/kb/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String notesDir = (String) body.get("notesDir");

        if (name == null || name.isBlank() || notesDir == null || notesDir.isBlank()) {
            return Map.of("ok", false, "error", "名称和笔记库路径不能为空");
        }

        kbService.save(body);
        logService.add("知识库", "保存", "知识库已保存: " + name);
        return Map.of("ok", true);
    }

    @ResponseBody
    @DeleteMapping("/api/kb/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        kbService.delete(id);
        logService.add("知识库", "删除", "知识库已删除: id=" + id);
        return Map.of("ok", true);
    }

    @ResponseBody
    @PostMapping("/api/kb/active/{id}")
    public Map<String, Object> setActive(@PathVariable Long id) {
        kbService.setActive(id);
        KnowledgeBaseEntity kb = kbService.getById(id);
        logService.add("知识库", "切换", "切换到知识库: " + (kb != null ? kb.getName() : id));
        return Map.of("ok", true);
    }

    @ResponseBody
    @PostMapping("/api/kb/reorder")
    public Map<String, Object> reorder(@RequestBody List<Long> ids) {
        kbService.reorder(ids);
        logService.add("知识库", "排序", "知识库排序已更新");
        return Map.of("ok", true);
    }

    private Map<String, Object> toMap(KnowledgeBaseEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("notesDir", e.getNotesDir());
        m.put("labels", e.getLabels());
        m.put("isActive", e.getIsActive());
        m.put("sortOrder", e.getSortOrder());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) return defaultLabels();
        try {
            Map<String, String> parsed = com.laoqi.assistant.util.FileUtil.readJson(labelsJson, LABELS_TYPE, null);
            if (parsed == null || parsed.isEmpty()) return defaultLabels();
            Map<String, String> result = defaultLabels();
            result.putAll(parsed);
            return result;
        } catch (Exception e) {
            return defaultLabels();
        }
    }

    private Map<String, String> defaultLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("tasks", "任务");
        labels.put("reminders", "提醒");
        labels.put("modules", "模块");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }
}
