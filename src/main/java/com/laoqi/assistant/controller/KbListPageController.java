package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.service.TaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class KbListPageController {

    private final KnowledgeBaseService kbService;
    private final TaskService taskService;
    private final ReminderService reminderService;

    public KbListPageController(KnowledgeBaseService kbService,
                                 TaskService taskService, ReminderService reminderService) {
        this.kbService = kbService;
        this.taskService = taskService;
        this.reminderService = reminderService;
    }

    @GetMapping("/kb")
    public String kbListPage(Model model) {
        List<KnowledgeBaseEntity> allKbs = kbService.getAll();
        List<Map<String, Object>> kbInfos = new ArrayList<>();

        for (KnowledgeBaseEntity kb : allKbs) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", kb.getId());
            info.put("name", kb.getName());
            info.put("notesDir", kb.getNotesDir());

            try {
                long activeTaskCount = taskService.getAllTasks(kb.getNotesDir()).stream()
                        .filter(t -> !"done".equals(t.status))
                        .count();
                info.put("taskCount", activeTaskCount);
            } catch (Exception e) {
                info.put("taskCount", 0L);
            }

            try {
                int reminderCount = reminderService.getAllReminders(kb.getNotesDir()).size();
                info.put("reminderCount", reminderCount);
            } catch (Exception e) {
                info.put("reminderCount", 0);
            }

            info.put("labels", parseLabels(kb.getLabels()));
            kbInfos.add(info);
        }

        model.addAttribute("kbInfos", kbInfos);
        return "kb_list";
    }

    private Map<String, String> defaultLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tasks", "任务");
        labels.put("reminders", "提醒");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }

    private Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) return defaultLabels();
        try {
            com.fasterxml.jackson.core.type.TypeReference<Map<String, String>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<>() {};
            Map<String, String> parsed = com.laoqi.assistant.util.FileUtil.readJson(labelsJson, typeRef, null);
            if (parsed == null || parsed.isEmpty()) return defaultLabels();
            Map<String, String> result = defaultLabels();
            result.putAll(parsed);
            return result;
        } catch (Exception e) {
            return defaultLabels();
        }
    }
}
